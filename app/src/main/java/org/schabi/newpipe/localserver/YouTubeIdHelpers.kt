package org.schabi.newpipe.localserver

/**
 * Local Server keys videos/channels by their full YouTube URL; Flow's native subscription and
 * watch-history storage keys them by bare ID. These convert between the two at the boundary
 * where Local Server reads/writes Flow's native data.
 */

fun channelIdToUrl(channelId: String): String = "https://www.youtube.com/channel/$channelId"

/**
 * Extracts a bare channel ID from a channel URL. Handles the canonical `/channel/UC...` form
 * (what NewPipeExtractor's YouTube uploader/channel URLs resolve to in the vast majority of
 * cases this module encounters them) as well as `/@handle`, `/c/name`, `/user/name` as a
 * fallback, so a vanity URL still yields *a* stable, consistently-reproducible key rather than
 * silently failing - even though it won't necessarily match the canonical UC id Flow's native
 * subscribe flow resolves for the same channel via a full page extraction.
 */
fun channelUrlToId(url: String?): String? {
    if (url == null) return null
    var normalized = url.trim()
    if (normalized.contains("m.youtube.com")) {
        normalized = normalized.replace("m.youtube.com", "www.youtube.com")
    }
    if (normalized.endsWith("/")) normalized = normalized.dropLast(1)
    for (marker in listOf("/channel/", "/@", "/c/", "/user/")) {
        val idx = normalized.indexOf(marker)
        if (idx != -1) {
            val id = normalized.substring(idx + marker.length).substringBefore("?")
            if (id.isNotEmpty()) return if (marker == "/@") "@$id" else id
        }
    }
    return null
}

fun videoIdToUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

fun playlistIdToUrl(playlistId: String): String = "https://www.youtube.com/playlist?list=$playlistId"

/** Extracts the bare `list=` id from a playlist URL - also NewPipeExtractor's own playlist id for
 * the same URL, so it lines up with whatever id Flow's native UI would save the same playlist
 * under. */
fun playlistUrlToId(url: String?): String? {
    if (url == null) return null
    val idx = url.indexOf("list=")
    if (idx == -1) return null
    return url.substring(idx + 5).substringBefore("&").takeIf { it.isNotEmpty() }
}
