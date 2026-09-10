package io.github.aedev.flow.ui.screens.player.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.isOriginalAudioTrack
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.Locale

/**
 * Approximate download size for one quality, or null when no estimate is available — the caller
 * omits the line entirely rather than showing a placeholder.
 */
@Composable
fun approxDownloadSizeLabel(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val gigabytes = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gigabytes >= 1.0) {
        stringResource(R.string.download_size_estimate_gb, String.format(Locale.getDefault(), "%.2f", gigabytes))
    } else {
        val megabytes = bytes / (1024.0 * 1024.0)
        stringResource(R.string.download_size_estimate_mb, String.format(Locale.getDefault(), "%.1f", megabytes))
    }
}

/** PipePipeExtractor's audioLocale is a plain language-tag string, not a java.util.Locale. */
private fun String.toDisplayLanguage(): String = Locale.forLanguageTag(replace('_', '-')).displayLanguage

object DownloadStreamHelpers {
    fun audioBitrateKbps(stream: AudioStream): Int {
        val raw = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate
        return if (raw > 1000) raw / 1000 else raw.coerceAtLeast(0)
    }

    fun audioFormatLabel(
        stream: AudioStream,
        unknownLabel: String = "",
    ): String {
        val mime =
            stream.format
                ?.mimeType
                .orEmpty()
                .lowercase()
        val name =
            stream.format
                ?.name
                .orEmpty()
                .lowercase()
        return when {
            "opus" in mime || "opus" in name -> "OPUS"
            "webm" in mime || "webm" in name -> "WEBM"
            "mp4" in mime || "m4a" in name -> "M4A"
            "mpeg" in mime || "mp3" in name -> "MP3"
            name.isNotBlank() -> name.uppercase()
            else -> unknownLabel
        }
    }

    fun audioFileExtension(stream: AudioStream): String {
        val mime =
            stream.format
                ?.mimeType
                .orEmpty()
                .lowercase()
        val name =
            stream.format
                ?.name
                .orEmpty()
                .lowercase()
        return when {
            "webm" in mime || "webm" in name -> "webm"
            "ogg" in mime || "opus" in name -> "ogg"
            "mpeg" in mime || "mp3" in name -> "mp3"
            else -> "m4a"
        }
    }

    fun audioLanguageLabel(stream: AudioStream): String? =
        stream.audioTrackName?.takeIf { it.isNotBlank() }
            ?: stream.audioLocale?.toDisplayLanguage()?.takeIf { it.isNotBlank() }
            ?: stream.audioTrackId?.takeIf { it.isNotBlank() }

    /**
     * PipePipeExtractor dropped AudioTrackType (ORIGINAL/DUBBED/SECONDARY/DESCRIPTIVE) - only
     * original-vs-not is knowable now, see [io.github.aedev.flow.player.stream.isOriginalAudioTrack].
     */
    fun audioTrackTypeLabel(
        stream: AudioStream,
        originalLabel: String,
        dubbedLabel: String,
    ): String = if (stream.isOriginalAudioTrack()) originalLabel else dubbedLabel

    private fun audioFormatSortRank(stream: AudioStream): Int =
        when (audioFormatLabel(stream)) {
            "OPUS", "WEBM" -> 0
            "M4A" -> 1
            "MP3" -> 2
            else -> 3
        }

    fun mergeAudioDownloadStreams(
        innerTubeStreams: List<AudioStream>,
        extractorStreams: List<AudioStream>,
    ): List<AudioStream> =
        (innerTubeStreams + extractorStreams)
            .filter { it.getContent().isNotBlank() }
            .distinctBy { stream ->
                listOf(
                    audioFormatLabel(stream),
                    audioBitrateKbps(stream).toString(),
                    stream.audioTrackId.orEmpty(),
                    stream.audioLocale.orEmpty(),
                    stream.isOriginalAudioTrack().toString(),
                ).joinToString("|")
            }.sortedWith(
                compareBy<AudioStream> { audioFormatSortRank(it) }
                    .thenByDescending { audioBitrateKbps(it) }
                    .thenBy { it.audioLocale?.toDisplayLanguage().orEmpty() },
            )

    fun pickCompatibleAudioForVideo(
        videoCodecKey: String,
        allAudio: List<AudioStream>,
        preferredLang: String?,
    ): AudioStream? {
        if (allAudio.isEmpty()) return null
        val isMp4Container = videoCodecKey == "h264" || videoCodecKey == "hevc"

        fun isAacCompatible(a: AudioStream): Boolean {
            val fmt = (a.format?.name ?: "").lowercase()
            val mime = (a.format?.mimeType ?: "").lowercase()
            return !fmt.contains("opus") && !fmt.contains("vorbis") &&
                !fmt.contains("webm") && !mime.contains("opus") &&
                !mime.contains("vorbis") && !mime.contains("webm")
        }

        val langFilteredAudio =
            if (!preferredLang.isNullOrEmpty() && preferredLang != "original") {
                val langMatches =
                    allAudio.filter {
                        it.audioLocale.equals(preferredLang, ignoreCase = true) ||
                            it.audioLocale?.startsWith(preferredLang, ignoreCase = true) == true
                    }
                if (langMatches.isNotEmpty()) langMatches else allAudio
            } else {
                val originals = allAudio.filter { it.isOriginalAudioTrack() }
                if (originals.isNotEmpty()) originals else allAudio
            }

        return if (isMp4Container) {
            langFilteredAudio.filter { isAacCompatible(it) }.maxByOrNull { it.bitrate }
                ?: allAudio.filter { isAacCompatible(it) }.maxByOrNull { it.bitrate }
        } else {
            val opusFilter: (AudioStream) -> Boolean = { a ->
                val fmt = a.format?.name ?: ""
                val mime = a.format?.mimeType ?: ""
                fmt.contains("webm", true) || mime.contains("audio/webm", true) ||
                    fmt.contains("opus", true) || mime.contains("opus", true)
            }
            langFilteredAudio.filter(opusFilter).maxByOrNull { it.bitrate }
                ?: allAudio.filter(opusFilter).maxByOrNull { it.bitrate }
                ?: langFilteredAudio.maxByOrNull { it.bitrate }
                ?: allAudio.maxByOrNull { it.bitrate }
        }
    }
}
