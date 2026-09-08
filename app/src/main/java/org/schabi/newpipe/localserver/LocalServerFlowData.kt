package org.schabi.newpipe.localserver

import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video as FlowVideo
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.InteractionType
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ChannelReelIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Local Server's subscriptions/watch-history read and write Flow's own native storage directly
 * (no bridge interface, no separate copy) - these just adapt Flow's bare-ID-keyed models to the
 * URL-keyed [InfoItem] shape the rest of Local Server's rendering code expects, and provide a
 * blocking entry point since request handling here runs on a plain background pool thread
 * (never the main thread), not inside a coroutine.
 */

private fun HistoryDbHelper.subscriptionRepository() = SubscriptionRepository.getInstance(appContext)

private fun HistoryDbHelper.viewHistory() = ViewHistory.getInstance(appContext)

/** Subscriptions as [InfoItem]s, same shape the old SQLite-backed `getSubscriptions()` returned. */
fun HistoryDbHelper.nativeSubscriptions(): List<InfoItem> = runBlocking {
    subscriptionRepository().getAllSubscriptions().first().map { sub ->
        val item = ChannelInfoItem(0, channelIdToUrl(sub.channelId), sub.channelName)
        if (sub.channelThumbnail.isNotEmpty()) {
            item.setThumbnails(listOf(Image(sub.channelThumbnail, -1, -1, ResolutionLevel.UNKNOWN)))
        }
        item
    }
}

fun HistoryDbHelper.nativeIsSubscribed(channelUrl: String?): Boolean {
    val channelId = channelUrlToId(channelUrl) ?: return false
    return runBlocking { subscriptionRepository().isSubscribed(channelId).first() }
}

/** Subscribes, or refreshes name/avatar if already subscribed - never resets tracked state. */
fun HistoryDbHelper.nativeAddSubscription(channelUrl: String, channelName: String?, channelAvatar: String?) {
    val channelId = channelUrlToId(channelUrl) ?: return
    runBlocking {
        subscriptionRepository().subscribeOrUpdateInfo(channelId, channelName ?: "", channelAvatar ?: "")
    }
}

fun HistoryDbHelper.nativeRemoveSubscription(channelUrl: String) {
    val channelId = channelUrlToId(channelUrl) ?: return
    runBlocking { subscriptionRepository().unsubscribe(channelId) }
}

/** Channel blocking reads/writes Flow's native FlowNeuroEngine block list directly - its own
 * ranking already excludes blocked channels (see FlowNeuroEngine.kt), so blocking here also
 * applies to Flow's native recommendations, not just Local Server's. */
fun HistoryDbHelper.nativeIsChannelBlocked(channelUrl: String?): Boolean {
    val channelId = channelUrlToId(channelUrl) ?: return false
    return runBlocking {
        ensureFlowNeuroInitialized()
        FlowNeuroEngine.getInstance(appContext).getBlockedChannels().contains(channelId)
    }
}

fun HistoryDbHelper.nativeBlockedChannelIds(): Set<String> = runBlocking {
    ensureFlowNeuroInitialized()
    FlowNeuroEngine.getInstance(appContext).getBlockedChannels()
}

fun HistoryDbHelper.nativeBlockChannel(channelUrl: String) {
    val channelId = channelUrlToId(channelUrl) ?: return
    runBlocking {
        ensureFlowNeuroInitialized()
        FlowNeuroEngine.blockChannel(channelId)
    }
}

fun HistoryDbHelper.nativeUnblockChannel(channelUrl: String) {
    val channelId = channelUrlToId(channelUrl) ?: return
    runBlocking {
        ensureFlowNeuroInitialized()
        FlowNeuroEngine.unblockChannel(channelId)
    }
}

/** Bare video IDs the user has watched, as full watch URLs - matches old `getWatchedUrls()` shape. */
fun HistoryDbHelper.nativeWatchedUrls(): Set<String> = runBlocking {
    viewHistory().getAllWatchedVideoIds().map { videoIdToUrl(it) }.toSet()
}

private fun HistoryDbHelper.playlistRepository() = PlaylistRepository(appContext)

/** Playlist bookmarks ("save this YouTube playlist") and Watch Later both live in Flow's native
 * PlaylistRepository (Room) - Watch Later is a hardcoded internal playlist there, the same table a
 * playlist saved from Flow's own UI lands in. */
fun HistoryDbHelper.nativeIsPlaylistBookmarked(playlistUrl: String?): Boolean {
    val playlistId = playlistUrlToId(playlistUrl) ?: return false
    return runBlocking { playlistRepository().isExternalPlaylistSaved(playlistId) }
}

fun HistoryDbHelper.nativeBookmarkPlaylist(playlistUrl: String, name: String?, thumbnailUrl: String?) {
    val playlistId = playlistUrlToId(playlistUrl) ?: return
    runBlocking { playlistRepository().saveExternalVideoPlaylist(playlistId, name ?: "", "", thumbnailUrl ?: "") }
}

fun HistoryDbHelper.nativeUnbookmarkPlaylist(playlistUrl: String) {
    val playlistId = playlistUrlToId(playlistUrl) ?: return
    runBlocking { playlistRepository().unsaveExternalPlaylist(playlistId) }
}

/** Bookmarked playlists as [InfoItem]s, same shape the old SQLite-backed `getBookmarkedPlaylists()`
 * returned. PlaylistEntity has no uploader field, so unlike the old table this doesn't carry one -
 * renderGrid() never displays it for playlist cards anyway (typeBadge takes that slot instead). */
fun HistoryDbHelper.nativeBookmarkedPlaylists(): List<InfoItem> = runBlocking {
    playlistRepository().getSavedVideoPlaylistsFlow().first().map { info ->
        val item = PlaylistInfoItem(0, playlistIdToUrl(info.id), info.name)
        if (info.thumbnailUrl.isNotEmpty()) {
            item.setThumbnails(listOf(Image(info.thumbnailUrl, -1, -1, ResolutionLevel.UNKNOWN)))
        }
        item
    }
}

fun HistoryDbHelper.nativeIsWatchLater(videoUrl: String): Boolean {
    val videoId = LocalHttpServer.getVideoId(videoUrl)
    if (videoId.isEmpty()) return false
    return runBlocking { playlistRepository().isInWatchLater(videoId) }
}

/** HTTP action params (Watch Later, like/dislike) never carry duration/viewCount/uploadDate -
 * only what the triggering button's own markup has on hand - so those go in as unknown, matching
 * the `-1`/empty convention [StreamInfoItem.toFlowVideo] uses elsewhere in this file for
 * genuinely-missing data. */
private fun buildFlowVideoFromParams(videoId: String, title: String, uploader: String, thumbnailUrl: String?, uploaderUrl: String?) = FlowVideo(
    id = videoId,
    title = title,
    channelName = uploader,
    channelId = channelUrlToId(uploaderUrl) ?: "",
    thumbnailUrl = thumbnailUrl ?: "",
    duration = 0,
    viewCount = -1,
    uploadDate = "",
)

/** Also reports a SAVED signal to FlowNeuroEngine, matching what Flow's own native Watch Later
 * button does (QuickActionsViewModel.toggleWatchLater) - best-effort, same as
 * [reportFlowNeuroInteraction]. */
fun HistoryDbHelper.nativeAddWatchLater(url: String, title: String, uploader: String, thumbnailUrl: String?, uploaderUrl: String?) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    val video = buildFlowVideoFromParams(videoId, title, uploader, thumbnailUrl, uploaderUrl)
    runBlocking {
        playlistRepository().addToWatchLater(video)
        ensureFlowNeuroInitialized()
        try {
            FlowNeuroEngine.onVideoInteraction(video, InteractionType.SAVED)
        } catch (e: Exception) {
            LocalHttpServer.log("FlowNeuro SAVED signal error: " + e.message)
        }
    }
}

fun HistoryDbHelper.nativeRemoveWatchLater(url: String) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    runBlocking { playlistRepository().removeFromWatchLater(videoId) }
}

/** Watch Later items as [InfoItem]s, same shape the old SQLite-backed `getWatchLaterItems()`
 * returned. Video-only: every add-to-Watch-Later call site already only ever adds videos (see
 * renderWatchLaterButton()), so the old table's dead "playlist" row type isn't carried forward. */
fun HistoryDbHelper.nativeWatchLaterItems(): List<InfoItem> = runBlocking {
    playlistRepository().getVideoOnlyWatchLaterFlow().first().map { it.toStreamInfoItem(0) }
}

private fun HistoryDbHelper.likedVideosRepository() = LikedVideosRepository.getInstance(appContext)

/** Like/dislike state and its FlowNeuroEngine signal both live in Flow's native
 * LikedVideosRepository (DataStore) - the same store Flow's own watch-page like/dislike buttons
 * read/write (VideoPlayerViewModel.likeVideo/dislikeVideo). HTTP params carry only what the
 * button's own markup has on hand, so the FlowVideo built here follows the same `-1`/empty
 * convention [nativeAddWatchLater] uses for genuinely-missing fields. */
fun HistoryDbHelper.nativeLikeState(videoUrl: String): String? {
    val videoId = LocalHttpServer.getVideoId(videoUrl)
    if (videoId.isEmpty()) return null
    return runBlocking { likedVideosRepository().getLikeState(videoId).first() }
}

fun HistoryDbHelper.nativeLikeVideo(url: String, title: String, uploader: String, thumbnailUrl: String?, uploaderUrl: String?) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    runBlocking {
        likedVideosRepository().likeVideo(LikedVideoInfo(videoId = videoId, title = title, thumbnail = thumbnailUrl ?: "", channelName = uploader))
        reportRatingSignal(videoId, title, uploader, thumbnailUrl, uploaderUrl, InteractionType.LIKED)
    }
}

fun HistoryDbHelper.nativeDislikeVideo(url: String, title: String, uploader: String, thumbnailUrl: String?, uploaderUrl: String?) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    runBlocking {
        likedVideosRepository().dislikeVideo(videoId)
        reportRatingSignal(videoId, title, uploader, thumbnailUrl, uploaderUrl, InteractionType.DISLIKED)
    }
}

fun HistoryDbHelper.nativeRemoveLikeState(url: String) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    runBlocking { likedVideosRepository().removeLikeState(videoId) }
}

private suspend fun HistoryDbHelper.reportRatingSignal(
    videoId: String, title: String, uploader: String, thumbnailUrl: String?, uploaderUrl: String?, type: InteractionType,
) {
    ensureFlowNeuroInitialized()
    try {
        val video = buildFlowVideoFromParams(videoId, title, uploader, thumbnailUrl, uploaderUrl)
        FlowNeuroEngine.onVideoInteraction(video, type)
    } catch (e: Exception) {
        LocalHttpServer.log("FlowNeuro $type signal error: " + e.message)
    }
}

private fun HistoryDbHelper.searchHistoryRepository() = SearchHistoryRepository(appContext)

/** Search history lives in Flow's native SearchHistoryRepository (DataStore) - the same store
 * Flow's own search bar reads/writes. Local Server's HTTP contract is a plain query-string list,
 * so [nativeDeleteSearchQuery] looks the item id up by query text rather than exposing ids over
 * the wire. */
fun HistoryDbHelper.nativeAddSearchQuery(query: String?) {
    if (query.isNullOrBlank()) return
    runBlocking { searchHistoryRepository().saveSearchQuery(query.trim()) }
}

fun HistoryDbHelper.nativeSearchHistory(): List<String> = runBlocking {
    searchHistoryRepository().getRecentSearches(10).map { it.query }
}

fun HistoryDbHelper.nativeDeleteSearchQuery(query: String?) {
    if (query.isNullOrBlank()) return
    runBlocking {
        val repo = searchHistoryRepository()
        val item = repo.getSearchHistoryFlow().first().find { it.query == query }
        if (item != null) repo.deleteSearchItem(item.id)
    }
}

private fun HistoryDbHelper.playerPreferences() = PlayerPreferences(appContext)

/** hideWatched/hideShorts/videoQuality now read/write Flow's native PlayerPreferences (DataStore).
 * hideWatched maps to Flow's "home feed" half of its split home/subscriptions toggle (Local
 * Server has one combined feed, not two separate screens). videoQuality maps to Flow's Wi-Fi
 * quality slot (Local Server only ever serves over LAN, so the cellular slot never applies). */
fun HistoryDbHelper.nativeHideWatched(): Boolean = runBlocking { playerPreferences().hideWatchedVideosFromHome.first() }

fun HistoryDbHelper.nativeSetHideWatched(enabled: Boolean) {
    runBlocking { playerPreferences().setHideWatchedVideosFromHome(enabled) }
}

fun HistoryDbHelper.nativeHideShorts(): Boolean = runBlocking { !playerPreferences().shortsContentEnabled.first() }

fun HistoryDbHelper.nativeSetHideShorts(hide: Boolean) {
    runBlocking { playerPreferences().setShortsContentEnabled(!hide) }
}

fun HistoryDbHelper.nativeVideoQuality(): String = runBlocking { playerPreferences().defaultQualityWifi.first().label }

fun HistoryDbHelper.nativeSetVideoQuality(quality: String) {
    runBlocking { playerPreferences().setDefaultQualityWifi(VideoQuality.fromString(quality)) }
}

/**
 * Create-or-touch a history entry's metadata without disturbing any already-saved playback
 * position. Call when a watch page loads. Same parameter shape as the old SQLite-backed
 * `saveToHistory(title, url, uploader, thumbnailUrl, serviceId, uploaderUrl, uploaderAvatar)` -
 * `serviceId`/`uploaderAvatar` have no destination column in Flow's native history table and are
 * dropped (Flow's own history screen never showed a channel avatar either, so this isn't a
 * regression versus the app's own UI).
 */
fun HistoryDbHelper.nativeSaveToHistory(
    title: String,
    url: String,
    uploader: String,
    thumbnailUrl: String,
    @Suppress("UNUSED_PARAMETER") serviceId: Int,
    uploaderUrl: String?,
    @Suppress("UNUSED_PARAMETER") uploaderAvatar: String?,
) {
    val videoId = LocalHttpServer.getVideoId(url)
    if (videoId.isEmpty()) return
    runBlocking {
        viewHistory().touchHistoryEntry(
            videoId = videoId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            channelName = uploader,
            channelId = channelUrlToId(uploaderUrl) ?: "",
        )
    }
}

/** Updates only the progress columns (position/duration) - never clobbers title/thumbnail/channel. */
fun HistoryDbHelper.nativeUpdateWatchProgress(videoUrl: String, percentWatched: Int, durationSeconds: Int) {
    val videoId = LocalHttpServer.getVideoId(videoUrl)
    if (videoId.isEmpty()) return
    val durationMs = durationSeconds.toLong() * 1000
    val positionMs = (durationMs * percentWatched.coerceIn(0, 100)) / 100
    runBlocking { viewHistory().updatePlaybackProgress(videoId, positionMs, durationMs) }
}

/** History rows rendered as [InfoItem]s, same shape the old SQLite-backed `getHistory()` returned. */
fun HistoryDbHelper.nativeHistory(): List<InfoItem> = runBlocking {
    viewHistory().getAllHistory().first().map { entry ->
        val item = StreamInfoItem(0, videoIdToUrl(entry.videoId), entry.title, StreamType.VIDEO_STREAM)
        item.setUploaderName(entry.channelName)
        item.setUploaderUrl(if (entry.channelId.isNotEmpty()) channelIdToUrl(entry.channelId) else "")
        if (entry.thumbnailUrl.isNotEmpty()) {
            item.setThumbnails(listOf(Image(entry.thumbnailUrl, -1, -1, ResolutionLevel.UNKNOWN)))
        }
        item
    }
}

fun HistoryDbHelper.nativeRemoveFromHistory(videoUrl: String) {
    val videoId = LocalHttpServer.getVideoId(videoUrl)
    if (videoId.isEmpty()) return
    runBlocking { viewHistory().clearVideoHistory(videoId) }
}

fun HistoryDbHelper.nativeClearHistory() {
    runBlocking { viewHistory().clearAllHistory() }
}

/**
 * Local Server's home-feed candidates and ranking now come straight from Flow's own native
 * `YouTubeRepository`/`FlowNeuroEngine` (both plain, directly-constructible classes - no Hilt
 * graph involved) instead of a separately-ported copy. `toFlowVideo()`/`toStreamInfoItem()`
 * convert at the boundary between NewPipeExtractor's [InfoItem] hierarchy (Local Server's native
 * type, shared across every page - search/channel/playlist/comments/watch/home) and Flow's
 * video-only [FlowVideo] display model.
 */

// Singleton so its channelAvatarCache/videoAvatarStackCache LRU caches persist across requests.
@Volatile
private var youTubeRepositoryInstance: YouTubeRepository? = null

private fun HistoryDbHelper.youTubeRepository(): YouTubeRepository =
    youTubeRepositoryInstance ?: synchronized(this) {
        youTubeRepositoryInstance ?: YouTubeRepository(PlayerPreferences(appContext), ChannelReelIndex())
            .also { youTubeRepositoryInstance = it }
    }

@Volatile
private var flowNeuroInitialized = false

private suspend fun HistoryDbHelper.ensureFlowNeuroInitialized() {
    if (flowNeuroInitialized) return
    FlowNeuroEngine.initialize(appContext)
    flowNeuroInitialized = true
}

// serviceId is unused in both mappings below (the extractor objects already carry everything
// needed) but is kept as a parameter for symmetry with the rest of this module's serviceId-taking
// converters.

/**
 * Search-result / listing candidates (StreamInfoItem) never carry tags or a description - those
 * fields only come back on the full watch-page StreamInfo (see the overload below).
 */
fun StreamInfoItem.toFlowVideo(@Suppress("UNUSED_PARAMETER") serviceId: Int): FlowVideo {
    val durationSeconds = this.duration.coerceAtLeast(0).toInt()
    return FlowVideo(
        id = LocalHttpServer.getVideoId(this.url),
        title = this.name ?: "",
        channelName = this.uploaderName ?: this.name ?: "",
        channelId = channelUrlToId(this.uploaderUrl) ?: "",
        thumbnailUrl = HtmlRendererCommon.getThumbnailUrl(this.thumbnails),
        duration = durationSeconds,
        viewCount = this.viewCount.coerceAtLeast(-1),
        likeCount = 0,
        uploadDate = this.textualUploadDate ?: "",
        description = "",
        channelThumbnailUrl = HtmlRendererCommon.getThumbnailUrl(this.uploaderAvatars),
        tags = emptyList(),
        isLive = this.streamType == StreamType.LIVE_STREAM || this.streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = durationSeconds in 1..120,
    )
}

/** Full watch-page detail (StreamInfo) - carries description and tags, unlike StreamInfoItem. */
fun StreamInfo.toFlowVideo(@Suppress("UNUSED_PARAMETER") serviceId: Int): FlowVideo {
    val durationSeconds = this.duration.coerceAtLeast(0).toInt()
    return FlowVideo(
        id = LocalHttpServer.getVideoId(this.url),
        title = this.name ?: "",
        channelName = this.uploaderName ?: "",
        channelId = channelUrlToId(this.uploaderUrl) ?: "",
        thumbnailUrl = HtmlRendererCommon.getThumbnailUrl(this.thumbnails),
        duration = durationSeconds,
        viewCount = this.viewCount.coerceAtLeast(-1),
        likeCount = this.likeCount.coerceAtLeast(0),
        uploadDate = this.textualUploadDate ?: "",
        description = this.description?.content ?: "",
        channelThumbnailUrl = HtmlRendererCommon.getThumbnailUrl(this.uploaderAvatars),
        tags = this.tags ?: emptyList(),
        isLive = this.streamType == StreamType.LIVE_STREAM || this.streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = durationSeconds in 1..120,
    )
}

/** Converts a Flow-repository-sourced [FlowVideo] back into the [InfoItem] shape Local Server's
 * existing HTML rendering code (HtmlRenderer*) already knows how to draw. */
fun FlowVideo.toStreamInfoItem(serviceId: Int): StreamInfoItem {
    val item = StreamInfoItem(serviceId, videoIdToUrl(this.id), this.title, StreamType.VIDEO_STREAM)
    item.setUploaderName(this.channelName)
    item.setUploaderUrl(if (this.channelId.isNotEmpty()) channelIdToUrl(this.channelId) else "")
    if (this.thumbnailUrl.isNotEmpty()) {
        item.setThumbnails(listOf(Image(this.thumbnailUrl, -1, -1, ResolutionLevel.UNKNOWN)))
    }
    if (this.channelThumbnailUrl.isNotEmpty()) {
        item.setUploaderAvatars(listOf(Image(this.channelThumbnailUrl, -1, -1, ResolutionLevel.UNKNOWN)))
    }
    item.setDuration(this.duration.toLong())
    item.setViewCount(this.viewCount)
    item.setTextualUploadDate(this.uploadDate)
    if (this.description.isNotEmpty()) item.setShortDescription(this.description)
    return item
}

// 5-min TTL cache for the assembled/ranked home feed, keyed by serviceId+feedMode.
private const val HOME_FEED_CACHE_TTL_MS = 5 * 60 * 1000L
private data class HomeFeedCacheEntry(val items: List<InfoItem>, val timestampMs: Long)
private val homeFeedCache = java.util.concurrent.ConcurrentHashMap<String, HomeFeedCacheEntry>()

/** One round of FlowNeuro discovery queries, fetched concurrently. `resetDepth` restarts the
 * engine's internal query-depth tracking (fresh session) vs. advancing it (load more); query
 * count matches Flow's own wave1 (HomeViewModel.kt). Shared by [buildAndRankHomeFeed] and
 * [continueDiscoveryFeed]. */
private suspend fun CoroutineScope.fetchDiscoveryVideos(repo: YouTubeRepository, resetDepth: Boolean): List<FlowVideo> =
    runCatching {
        val queries = FlowNeuroEngine.generateDiscoveryQueries(resetDepth = resetDepth)
        queries.take(3).map { query ->
            async { runCatching { repo.searchVideos(query).first }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }.getOrDefault(emptyList())

/** Dedupes by video id (first occurrence wins), ranks via FlowNeuroEngine (falls back to
 * unranked order on failure), converts to [StreamInfoItem]. Shared tail of [buildAndRankHomeFeed],
 * [continueDiscoveryFeed], and [buildShortsCandidates]. */
private suspend fun HistoryDbHelper.rankAndConvert(videos: List<FlowVideo>, serviceId: Int): List<StreamInfoItem> {
    val deduped = LinkedHashMap<String, FlowVideo>()
    for (v in videos) if (v.id.isNotBlank()) deduped.putIfAbsent(v.id, v)
    if (deduped.isEmpty()) return emptyList()

    val subIds = subscriptionRepository().getAllSubscriptionIds()
    val ranked = runCatching { FlowNeuroEngine.rank(deduped.values.toList(), subIds) }
        .getOrDefault(deduped.values.toList())
    return ranked.map { it.toStreamInfoItem(serviceId) }
}

/**
 * Assembles the home-feed candidate pool from Flow's own sources - trending kiosk, FlowNeuro
 * discovery queries, and (`feedMode == "mix"`) the subscription feed - then ranks via
 * FlowNeuroEngine. Skips HomeViewModel's quota/dedup/blending algorithm (Compose-state-coupled,
 * not directly callable); a simple id-dedup + `FlowNeuroEngine.rank()` substitutes.
 *
 * Second value: whether [continueDiscoveryFeed] has more to fetch. No real NewPipeExtractor Page
 * involved - YouTube's trending kiosk has no continuation (`YoutubeTrendingExtractor` never sets
 * a next page), so "load more" comes from a deeper discovery-query round instead.
 */
fun HistoryDbHelper.buildAndRankHomeFeed(serviceId: Int, feedMode: String): Pair<List<InfoItem>, Boolean> = runBlocking {
    val cacheKey = "$serviceId:$feedMode"
    val cached = homeFeedCache[cacheKey]
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.timestampMs < HOME_FEED_CACHE_TTL_MS) {
        return@runBlocking cached.items to cached.items.isNotEmpty()
    }

    ensureFlowNeuroInitialized()
    val repo = youTubeRepository()

    // Dedup priority in rankAndConvert(): subs > discovery > trending.
    val pool = mutableListOf<FlowVideo>()
    supervisorScope {
        val trendingDeferred = async { runCatching { repo.getTrendingVideos("").first }.getOrDefault(emptyList()) }
        val discoveryDeferred = async { fetchDiscoveryVideos(repo, resetDepth = true) }
        val subsDeferred = async {
            if (feedMode != "mix") return@async emptyList()
            runCatching {
                val subIds = subscriptionRepository().getAllSubscriptionIds()
                if (subIds.isEmpty()) emptyList() else repo.getSubscriptionFeed(subIds.toList())
            }.getOrDefault(emptyList())
        }

        pool += subsDeferred.await()
        pool += discoveryDeferred.await()
        pool += trendingDeferred.await()
    }

    val result = rankAndConvert(pool, serviceId)
    if (result.isEmpty()) return@runBlocking emptyList<InfoItem>() to false

    homeFeedCache[cacheKey] = HomeFeedCacheEntry(result, now)
    result to true
}

/** `feedMode == "subs"` home feed - Flow's native subscription feed (same
 * `YouTubeRepository.getSubscriptionFeed()` lane [buildAndRankHomeFeed] uses for "mix"), ranked
 * via FlowNeuroEngine, cached the same way. Replaces the old executor-based scrape of up to 10
 * random subscribed channels (`fetchChannelUploads()`). */
fun HistoryDbHelper.buildSubsOnlyFeed(serviceId: Int): List<InfoItem> = runBlocking {
    val cacheKey = "$serviceId:subs"
    val cached = homeFeedCache[cacheKey]
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.timestampMs < HOME_FEED_CACHE_TTL_MS) {
        return@runBlocking cached.items
    }

    ensureFlowNeuroInitialized()
    val subIds = subscriptionRepository().getAllSubscriptionIds()
    if (subIds.isEmpty()) return@runBlocking emptyList()

    val videos = runCatching { youTubeRepository().getSubscriptionFeed(subIds.toList()) }.getOrDefault(emptyList())
    val result = rankAndConvert(videos, serviceId)
    homeFeedCache[cacheKey] = HomeFeedCacheEntry(result, now)
    result
}

/** "Load more" for the home feed - next (deeper) round of discovery queries, ranked the same
 * way as [buildAndRankHomeFeed]. Uncached. No subscription-feed re-pull, matching Flow's own
 * "load more" (subs lane is first-load only). Shares FlowNeuroEngine's discovery-depth counter
 * with Flow's native home screen. */
fun HistoryDbHelper.continueDiscoveryFeed(serviceId: Int): Pair<List<InfoItem>, Boolean> = runBlocking {
    ensureFlowNeuroInitialized()
    val repo = youTubeRepository()

    val videos = fetchDiscoveryVideos(repo, resetDepth = false)
    val result = rankAndConvert(videos, serviceId)
    result to result.isNotEmpty()
}

// 5-min TTL cache for buildShortsCandidates(), keyed by serviceId.
private const val SHORTS_POOL_CACHE_TTL_MS = 5 * 60 * 1000L
private data class ShortsPoolCacheEntry(val items: List<StreamInfoItem>, val timestampMs: Long)
private val shortsPoolCache = java.util.concurrent.ConcurrentHashMap<Int, ShortsPoolCacheEntry>()

/** Trending + a round of FlowNeuro discovery queries, ranked via FlowNeuroEngine - the Shorts
 * feed's counterpart to [buildAndRankHomeFeed]. Replaces the old preferred-keywords search
 * branch in `buildAndScoreShortsPool()`: `preferredKeywords` has no settings-page UI to ever set
 * it, so that branch never actually ran. */
fun HistoryDbHelper.buildShortsCandidates(serviceId: Int): List<StreamInfoItem> = runBlocking {
    val cached = shortsPoolCache[serviceId]
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.timestampMs < SHORTS_POOL_CACHE_TTL_MS) {
        return@runBlocking cached.items
    }

    ensureFlowNeuroInitialized()
    val repo = youTubeRepository()

    val pool = mutableListOf<FlowVideo>()
    supervisorScope {
        val trendingDeferred = async { runCatching { repo.getTrendingVideos("").first }.getOrDefault(emptyList()) }
        val discoveryDeferred = async { fetchDiscoveryVideos(repo, resetDepth = false) }
        pool += discoveryDeferred.await()
        pool += trendingDeferred.await()
    }

    val result = rankAndConvert(pool, serviceId)
    shortsPoolCache[serviceId] = ShortsPoolCacheEntry(result, now)
    result
}

/** Real trending kiosk, unranked - used by handleApiHome()'s "raw" JSON feed (as opposed to
 * handleApiRecommendations(), which uses [buildAndRankHomeFeed]'s FlowNeuro-ranked pool). No
 * pagination available (YoutubeTrendingExtractor never sets a next page). */
fun HistoryDbHelper.fetchTrendingItems(serviceId: Int): List<StreamInfoItem> = runBlocking {
    youTubeRepository().getTrendingVideos("").first.map { it.toStreamInfoItem(serviceId) }
}

/**
 * Reorders `items` using Flow's native FlowNeuro engine - never changes WHICH items are present,
 * only their order. Falls back to the original order on any failure (a personalization hiccup
 * should never break the feed).
 */
fun HistoryDbHelper.rankWithFlowNeuro(items: List<InfoItem>, serviceId: Int): List<InfoItem> {
    val streamItems = items.filterIsInstance<StreamInfoItem>()
    if (streamItems.isEmpty()) return items
    return try {
        runBlocking {
            ensureFlowNeuroInitialized()
            val userSubs = subscriptionRepository().getAllSubscriptionIds()

            val videoById = LinkedHashMap<String, StreamInfoItem>()
            val videos = ArrayList<FlowVideo>(streamItems.size)
            for (item in streamItems) {
                val video = item.toFlowVideo(serviceId)
                if (video.id.isNotBlank() && !videoById.containsKey(video.id)) {
                    videoById[video.id] = item
                    videos.add(video)
                }
            }
            if (videos.isEmpty()) return@runBlocking items

            val ranked = FlowNeuroEngine.rank(videos, userSubs)
            val rankedItems: List<InfoItem> = ranked.mapNotNull { videoById[it.id] }

            val rankedSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InfoItem, Boolean>())
            rankedSet.addAll(rankedItems)
            val leftovers = items.filter { it !in rankedSet }

            rankedItems + leftovers
        }
    } catch (e: Exception) {
        LocalHttpServer.log("FlowNeuro ranking failed, falling back to original order: " + e.message)
        items
    }
}

/** Reports a click/watch/etc. signal to Flow's native FlowNeuro engine. Best-effort - a
 * personalization-learning failure must never break playback or the calling endpoint. */
fun HistoryDbHelper.reportFlowNeuroInteraction(
    info: StreamInfo,
    serviceId: Int,
    type: InteractionType,
    percentWatched: Float = 0f,
) {
    try {
        runBlocking {
            ensureFlowNeuroInitialized()
            FlowNeuroEngine.onVideoInteraction(info.toFlowVideo(serviceId), type, percentWatched)
        }
    } catch (e: Exception) {
        LocalHttpServer.log("FlowNeuro interaction-signal error: " + e.message)
    }
}
