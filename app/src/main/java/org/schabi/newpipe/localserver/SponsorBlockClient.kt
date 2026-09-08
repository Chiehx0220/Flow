package org.schabi.newpipe.localserver

import io.github.aedev.flow.data.repository.SponsorBlockRepository
import kotlinx.coroutines.runBlocking

/**
 * Bridges to Flow's native SponsorBlockRepository (same sponsor.ajay.app API, same non-privacy-
 * preserving direct-by-videoID lookup this file used to do itself) instead of maintaining a
 * separate HTTP client - this also picks up AppProxyManager's proxy handling, which the old
 * standalone OkHttp client never did.
 *
 * Flow's native repository only fetches 6 of the 8 SponsorBlock categories (no preview/filler)
 * and doesn't filter by actionType, so POI (single-instant) segments are dropped here to keep
 * every returned Segment a real skippable range - the marker/skip-button UI this feeds assumes
 * that.
 */
object SponsorBlockClient {
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        // SponsorBlock's own category id (e.g. "sponsor", "selfpromo", "interaction") - matches
        // what HtmlRendererWatch's marker-color CSS classes and category labels key off of.
        val category: String,
    )

    /** Blocking - call from a background thread (every LocalHttpServer request already is one). */
    @JvmStatic
    fun fetchSegments(videoId: String): List<Segment> {
        if (videoId.isBlank()) return emptyList()
        return try {
            runBlocking {
                SponsorBlockRepository().getSegments(videoId)
                    .filter { it.actionType == "skip" }
                    .map { Segment((it.startTime * 1000).toLong(), (it.endTime * 1000).toLong(), it.category) }
            }
        } catch (e: Exception) {
            LocalHttpServer.log("SponsorBlock fetch failed for $videoId: ${e.message}")
            emptyList()
        }
    }
}
