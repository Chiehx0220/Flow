package org.schabi.newpipe.localserver

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * JSON serialization for the `/api/v1/...` endpoints added in Fathom<->Flow integration Stage 2
 * (see the plan at C:\Users\Administrator\.claude\plans\witty-inventing-seahorse.md). Field names
 * deliberately mirror the Flow fork's `io.github.aedev.flow.data.model.Models.kt` (`Video`,
 * `Channel`, `Playlist`, `Comment`, `SearchResult`) as closely as this extractor's data allows -
 * the whole point of Stage 2 is minimizing the reshaping work whenever Stage 4 actually rewires
 * Flow's repository layer to call this API, so drifting from those names defeats the purpose.
 *
 * Deliberately separate from HtmlRenderer*.kt and does not touch/reuse any of those functions -
 * this machine can't compile-check Kotlin changes (see feedback_windows_gradle_subprocess), so
 * refactoring the already-working, user-verified HTML handlers to share code with this brand new,
 * unverified JSON layer would risk silently breaking pages that currently work. Some duplicated
 * extraction calls against the same stable extractor APIs is the safer trade until this has a
 * real build+test cycle behind it.
 */
object ApiRenderer {

    // channelId here is the *full channel URL* (e.g. "https://www.youtube.com/channel/UC...."),
    // not a bare ID - PipePipeExtractor (and this whole app) deals in full URLs everywhere, there
    // is no separate bare-ID concept surfaced anywhere in localtube today. Flow's own model just
    // calls the field "channelId"; documenting the actual shape here since Stage 4 will need to
    // know this when it wires Flow's repository layer up to these responses.
    @JvmStatic
    fun videoJson(item: InfoItem, serviceId: Int): JSONObject {
        val json = JSONObject()
        val streamItem = item as? StreamInfoItem
        json.put("id", LocalHttpServer.getVideoId(item.url))
        json.put("title", item.name ?: "")
        json.put("channelName", streamItem?.uploaderName ?: item.name ?: "")
        json.put("channelId", streamItem?.uploaderUrl ?: "")
        json.put("thumbnailUrl", HtmlRendererCommon.getThumbnailUrl(item.thumbnailUrl))
        json.put("channelThumbnailUrl", HtmlRendererCommon.getThumbnailUrl(streamItem?.uploaderAvatarUrl))
        if (streamItem != null) {
            json.put("duration", streamItem.duration.coerceAtLeast(0).toInt())
            json.put("viewCount", streamItem.viewCount.coerceAtLeast(-1))
            json.put("uploadDate", streamItem.textualUploadDate ?: "")
            json.put("isLive", streamItem.streamType == StreamType.LIVE_STREAM || streamItem.streamType == StreamType.AUDIO_LIVE_STREAM)
            json.put("isShort", streamItem.duration in 1..120)
        } else {
            json.put("duration", 0)
            json.put("viewCount", -1)
            json.put("uploadDate", "")
            json.put("isLive", false)
            json.put("isShort", false)
        }
        return json
    }

    // Full watch-page detail: everything videoJson() has, plus the fields only StreamInfo (not
    // the lighter StreamInfoItem used in listings) actually carries - description, like count,
    // related videos, and the playback URLs. Deliberately does NOT re-resolve stream/manifest
    // URLs itself - it points at this server's own existing /stream, /manifest, /subtitles proxy
    // routes (already fully working, PoToken-aware, DASH-manifest-generating - see
    // handleStreamProxy/handleManifestProxy/handleSubtitlesProxy), matching the plan's explicit
    // note that Stage 2 reuses that proxy logic as-is rather than duplicating it.
    @JvmStatic
    fun videoDetailJson(info: StreamInfo, serviceId: Int): JSONObject {
        val json = JSONObject()
        val infoUrlEncoded = HtmlRendererCommon.encodeUrl(info.url)
        json.put("id", LocalHttpServer.getVideoId(info.url))
        json.put("title", info.name ?: "")
        json.put("channelName", info.uploaderName ?: "")
        json.put("channelId", info.uploaderUrl ?: "")
        json.put("thumbnailUrl", HtmlRendererCommon.getThumbnailUrl(info.thumbnails))
        json.put("channelThumbnailUrl", HtmlRendererCommon.getThumbnailUrl(info.uploaderAvatars))
        json.put("duration", info.duration.coerceAtLeast(0).toInt())
        json.put("viewCount", info.viewCount.coerceAtLeast(-1))
        json.put("likeCount", info.likeCount.coerceAtLeast(0))
        json.put("uploadDate", info.textualUploadDate ?: "")
        json.put("description", info.description?.content ?: "")
        json.put("isLive", info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM)
        json.put("isShort", info.duration in 1..120)

        val related = JSONArray()
        if (!info.relatedItems.isNullOrEmpty()) {
            for (relatedItem in info.relatedItems) {
                if (relatedItem is StreamInfoItem) {
                    related.put(videoJson(relatedItem, serviceId))
                }
            }
        }
        json.put("relatedVideos", related)

        // Not part of Flow's Video model - Flow's ExoPlayer needs somewhere to actually point,
        // and this server's proxy routes are it. isDash is true whenever the extractor didn't
        // give a direct progressive URL, matching how HtmlRendererWatch.kt itself already decides
        // between the two source types for the HTML <video> tag.
        val playback = JSONObject()
        val hasVideo = info.videoStreams.isNotEmpty() || info.videoOnlyStreams.isNotEmpty() || !info.hlsUrl.isNullOrEmpty()
        playback.put("isDash", hasVideo)
        playback.put("manifestUrl", "/manifest?serviceId=$serviceId&id=$infoUrlEncoded")
        playback.put("streamUrl", "/stream?serviceId=$serviceId&id=$infoUrlEncoded")

        // Download quality choices, added for the Stage 4 download-feature rewrite. Deliberately
        // limited to *progressive* (video+audio already combined) streams: each is a single
        // /stream?itag=<itag> URL a client can download directly with no muxing step, unlike the
        // video-only/audio-only adaptive streams handleStreamProxy also knows how to serve. One
        // "audio" entry (the highest-bitrate audio-only stream, if any) is included for an
        // audio-only download option. This reuses handleStreamProxy's existing itag-based stream
        // selection as-is (see the itag branch there) - no new extraction or stream-serving logic,
        // just exposing a slice of what StreamInfo already extracted.
        val formats = JSONArray()
        for (stream in info.videoStreams) {
            if (stream.isVideoOnly) continue
            val format = JSONObject()
            format.put("itag", stream.itag)
            format.put("kind", "video")
            format.put("resolution", stream.resolution ?: "")
            format.put("format", stream.format?.suffix ?: "")
            format.put("bitrate", stream.bitrate)
            formats.put(format)
        }
        info.audioStreams?.maxByOrNull { it.averageBitrate }?.let { bestAudio ->
            val format = JSONObject()
            format.put("itag", bestAudio.itag)
            format.put("kind", "audio")
            format.put("resolution", "")
            format.put("format", bestAudio.format?.suffix ?: "")
            format.put("bitrate", bestAudio.averageBitrate)
            formats.put(format)
        }
        playback.put("formats", formats)

        json.put("playback", playback)

        // Caption/subtitle track list, added alongside the download-formats work above for the
        // same reason: handleSubtitlesProxy (/subtitles?serviceId=&id=&lang=&auto=) already fully
        // works and is already used by this server's own HTML player (HtmlRendererWatch.kt) - it
        // just needed the *list* of available tracks exposed over the JSON API so a client can
        // build a picker menu, the same gap `formats` closed for downloads. No new extraction.
        val subtitles = JSONArray()
        try {
            for (sub in info.subtitles.orEmpty()) {
                val track = JSONObject()
                track.put("languageTag", sub.languageTag ?: "")
                track.put("displayName", sub.displayLanguageName ?: sub.languageTag ?: "")
                track.put("isAutoGenerated", sub.isAutoGenerated)
                track.put(
                    "url",
                    "/subtitles?serviceId=$serviceId&id=$infoUrlEncoded" +
                        "&lang=${HtmlRendererCommon.encodeUrl(sub.languageTag ?: "")}" +
                        "&auto=${sub.isAutoGenerated}",
                )
                subtitles.put(track)
            }
        } catch (e: Exception) {
            // info.subtitles can throw for services/videos with no captions at all - same
            // best-effort handling handleSubtitlesProxy itself already uses around this same call.
        }
        json.put("subtitles", subtitles)

        return json
    }

    @JvmStatic
    fun channelJson(channel: ChannelExtractor, isSubscribed: Boolean): JSONObject {
        val json = JSONObject()
        json.put("id", channel.linkHandler.url)
        json.put("name", channel.name ?: "")
        json.put("thumbnailUrl", HtmlRendererCommon.getThumbnailUrl(channel.avatars))
        json.put("subscriberCount", channel.subscriberCount.coerceAtLeast(-1))
        json.put("description", channel.description ?: "")
        json.put("isSubscribed", isSubscribed)
        json.put("url", channel.linkHandler.url)
        return json
    }

    @JvmStatic
    fun channelInfoItemJson(item: ChannelInfoItem): JSONObject {
        val json = JSONObject()
        json.put("id", item.url)
        json.put("name", item.name ?: "")
        json.put("thumbnailUrl", HtmlRendererCommon.getThumbnailUrl(item.thumbnailUrl))
        json.put("subscriberCount", item.subscriberCount.coerceAtLeast(-1))
        json.put("url", item.url)
        return json
    }

    @JvmStatic
    fun playlistInfoItemJson(item: PlaylistInfoItem): JSONObject {
        val json = JSONObject()
        json.put("id", item.url)
        json.put("name", item.name ?: "")
        json.put("thumbnailUrl", HtmlRendererCommon.getThumbnailUrl(item.thumbnailUrl))
        json.put("videoCount", item.streamCount.coerceAtLeast(0).toInt())
        json.put("isLocal", false)
        return json
    }

    @JvmStatic
    fun commentJson(item: CommentsInfoItem): JSONObject {
        val json = JSONObject()
        json.put("id", item.commentId ?: "")
        json.put("author", item.uploaderName ?: "")
        // Empty, not the placeholder URL, when there's no real avatar.
        json.put(
            "authorThumbnail",
            if (HtmlRendererCommon.hasThumbnail(item.uploaderAvatarUrl)) HtmlRendererCommon.getThumbnailUrl(item.uploaderAvatarUrl) else "",
        )
        json.put("text", item.commentText ?: "")
        json.put("likeCount", item.likeCount.coerceAtLeast(0))
        json.put("publishedTime", item.textualUploadDate ?: "")
        json.put("isPinned", item.isPinned)
        json.put("authorChannelId", item.uploaderUrl ?: "")
        json.put("replyCount", item.replyCount.coerceAtLeast(0))
        json.put("continuationToken", serializePageOrNull(item.replies))
        return json
    }

    // Every paginated endpoint returns its nextPage the same way: the existing
    // HtmlRendererCommon.serializePage()/deserializePage() Base64 round-trip already used by
    // every HTML listing page's "Load More" link - reused as-is here (not reinvented) so a
    // client can pass a nextPage token straight back as a query param the exact same way the
    // HTML pages' own pagination links already do.
    @JvmStatic
    fun serializePageOrNull(page: Page?): String? = HtmlRendererCommon.serializePage(page)

    @JvmStatic
    fun infoItemsToJson(items: List<InfoItem>, serviceId: Int): JSONArray {
        val array = JSONArray()
        for (item in items) {
            when (item) {
                is StreamInfoItem -> array.put(videoJson(item, serviceId))
                is ChannelInfoItem -> array.put(channelInfoItemJson(item))
                is PlaylistInfoItem -> array.put(playlistInfoItemJson(item))
                else -> array.put(videoJson(item, serviceId))
            }
        }
        return array
    }

    // Mirrors Flow's SearchResult(videos, channels, playlists) shape - splits a mixed InfoItem
    // list into the three buckets by type rather than leaving the caller to do it.
    @JvmStatic
    fun searchResultJson(items: List<InfoItem>, serviceId: Int, nextPage: Page?): JSONObject {
        val videos = JSONArray()
        val channels = JSONArray()
        val playlists = JSONArray()
        for (item in items) {
            when (item) {
                is StreamInfoItem -> videos.put(videoJson(item, serviceId))
                is ChannelInfoItem -> channels.put(channelInfoItemJson(item))
                is PlaylistInfoItem -> playlists.put(playlistInfoItemJson(item))
                else -> videos.put(videoJson(item, serviceId))
            }
        }
        val json = JSONObject()
        json.put("videos", videos)
        json.put("channels", channels)
        json.put("playlists", playlists)
        json.put("nextPage", serializePageOrNull(nextPage))
        return json
    }

    @JvmStatic
    fun errorJson(message: String?): String {
        val json = JSONObject()
        json.put("error", message ?: "Unknown error")
        return json.toString()
    }
}
