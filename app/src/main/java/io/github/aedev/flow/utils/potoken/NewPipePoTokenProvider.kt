package io.github.aedev.flow.utils.potoken

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult
import java.util.function.Function

/**
 * PipePipeExtractor replaced the old per-client [org.schabi.newpipe.extractor.services.youtube.PoTokenProvider]
 * interface (web/webEmbed/android/ios) with a single resolver function registered via
 * [NewPipe.setYoutubePoTokenResolver], scoped to one MWEB player request. Flow's WebView-based
 * BotGuard solver ([PoTokenGenerator]) is untouched - only this adapter shape changed.
 */
object NewPipePoTokenProvider : Function<String, YoutubePoTokenResult?> {
    private const val TAG = "NewPipePoTokenProvider"

    private val poTokenGenerator = PoTokenGenerator
    private val visitorDataLock = Any()
    private var webPoTokenVisitorData: String? = null

    override fun apply(videoId: String): YoutubePoTokenResult? {
        val visitorData = ensureVisitorData() ?: return null
        val poTokenResult =
            try {
                poTokenGenerator.getWebClientPoToken(videoId, visitorData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate extractor poToken for $videoId: ${e.message}", e)
                null
            } ?: return null

        val clientVersion = YoutubeParsingHelper.getClientVersion()
        return YoutubePoTokenResult(
            visitorData,
            clientVersion,
            poTokenResult.playerRequestPoToken,
        )
    }

    private fun ensureVisitorData(): String? {
        synchronized(visitorDataLock) {
            webPoTokenVisitorData?.takeIf { it.isNotBlank() }?.let { return it }

            return runCatching {
                val requestInfo = InnertubeClientRequestInfo.ofWebClient()
                requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                YoutubeParsingHelper.getVisitorDataFromInnertube(
                    requestInfo,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false,
                )
            }.onFailure { e ->
                Log.w(TAG, "Failed to fetch extractor visitor data: ${e.message}", e)
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.also { webPoTokenVisitorData = it }
        }
    }
}
