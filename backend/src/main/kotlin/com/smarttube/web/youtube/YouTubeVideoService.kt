package com.smarttube.web.youtube

import com.smarttube.web.videos.VideoFormatDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class YouTubeVideoService(
    private val innerTubePlaybackProvider: YouTubePlaybackProvider,
) {
    fun getVideoStreamUrl(videoId: String, formatSelector: String = DEFAULT_FORMAT_SELECTOR): VideoStreamInfo =
        innerTubePlaybackProvider.getVideoStreamUrl(videoId, formatSelector)
            ?: throw noPlayableStream("Video extraction failed")

    fun getAvailableFormats(videoId: String): VideoFormatsInfo =
        innerTubePlaybackProvider.getAvailableFormats(videoId)
            ?: throw noPlayableStream("Format extraction failed")

    fun getDashManifest(videoId: String, videoItag: Int? = null): String =
        innerTubePlaybackProvider.getDashManifest(videoId, videoItag)
            ?: throw noPlayableStream("DASH manifest extraction failed")

    fun getAdaptiveStreamUrl(videoId: String, itag: Int): String =
        innerTubePlaybackProvider.getAdaptiveStreamUrl(videoId, itag)
            ?: throw noPlayableStream("Adaptive stream extraction failed")

    fun getAdaptiveStreamUrls(videoId: String, itag: Int): List<String> =
        innerTubePlaybackProvider.getAdaptiveStreamUrls(videoId, itag)

    fun getSubtitleContent(captionUrl: String): String =
        innerTubePlaybackProvider.getSubtitleContent(captionUrl)

    companion object {
        private const val DEFAULT_FORMAT_SELECTOR = "bestvideo+bestaudio/best"
    }
}

data class VideoFormatsInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Int,
    val formats: List<VideoFormat>,
)

data class VideoStreamInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String?,
    val lengthSeconds: Int,
    val streamUrl: String,
    val hlsManifestUrl: String?,
    val dashManifestUrl: String?,
    val formats: List<VideoFormat>,
    val subtitles: List<SubtitleTrack>,
)

data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String,
    val automatic: Boolean,
    val default: Boolean,
)

data class VideoFormat(
    val formatId: String,
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val bitrate: Long,
) {
    fun toDto() = VideoFormatDto(
        formatId = formatId,
        itag = itag,
        url = url,
        mimeType = mimeType,
        qualityLabel = qualityLabel,
        bitrate = bitrate,
    )
}

internal fun noPlayableStream(prefix: String): ResponseStatusException =
    ResponseStatusException(HttpStatus.BAD_GATEWAY, "$prefix: No playable stream available")
