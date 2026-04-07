package com.smarttube.web.youtube

import com.smarttube.web.videos.VideoFormatDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class YouTubeVideoService(
    private val authService: YouTubeAuthService,
    private val httpClient: YouTubeHttpClient,
    private val properties: YouTubeProperties,
    @Value("\${ytdlp.service.url:http://yt-dlp:8081}") private val ytdlpUrl: String,
) {
    private val restTemplate = RestTemplate()
    
    private fun getStreamBaseUrl(): String {
        return properties.ytdlpExternalUrl ?: ytdlpUrl
    }
    
    fun getVideoStreamUrl(videoId: String, formatSelector: String = "bestvideo+bestaudio/best"): VideoStreamInfo {
        return try {
            val infoResponse = restTemplate.getForObject(
                "$ytdlpUrl/video/$videoId/info",
                YtdlpVideoInfoResponse::class.java
            )
            
            if (infoResponse == null) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch video info")
            }
            
            if (infoResponse.error != null) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, infoResponse.error)
            }
            
            val streamUrl = "${getStreamBaseUrl()}/video/$videoId?format=${urlEncode(formatSelector)}"
            val subtitles = infoResponse.subtitles.orEmpty().map { it.toSubtitleTrack(videoId, getStreamBaseUrl()) }
            
            VideoStreamInfo(
                videoId = videoId,
                title = infoResponse.title ?: "Unknown",
                author = infoResponse.author ?: "Unknown",
                lengthSeconds = infoResponse.lengthSeconds ?: 0,
                streamUrl = streamUrl,
                hlsManifestUrl = null,
                formats = emptyList(),
                subtitles = subtitles,
            )
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Video extraction failed: ${e.message}")
        }
    }

    fun getAvailableFormats(videoId: String): VideoFormatsInfo {
        return try {
            val formatsResponse = restTemplate.getForObject(
                "$ytdlpUrl/video/$videoId/formats",
                YtdlpFormatsResponse::class.java
            )
            
            if (formatsResponse == null) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch video formats")
            }
            
            if (formatsResponse.error != null) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, formatsResponse.error)
            }
            
            val videoFormats = formatsResponse.formats?.map { ytdlpFormat ->
                VideoFormat(
                    formatId = ytdlpFormat.format_id ?: "",
                    itag = ytdlpFormat.format_id?.toIntOrNull() ?: 0,
                    url = "${getStreamBaseUrl()}/video/${videoId}?format=${urlEncode(ytdlpFormat.format_id ?: "")}",
                    mimeType = ytdlpFormat.mimeType ?: "video/${ytdlpFormat.ext}",
                    qualityLabel = ytdlpFormat.qualityLabel ?: "${ytdlpFormat.resolution} ${ytdlpFormat.fps}fps",
                    bitrate = 0
                )
            } ?: emptyList()
            
            VideoFormatsInfo(
                videoId = videoId,
                title = formatsResponse.title ?: "Unknown",
                author = formatsResponse.author ?: "Unknown",
                lengthSeconds = formatsResponse.lengthSeconds ?: 0,
                formats = videoFormats
            )
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Format extraction failed: ${e.message}")
        }
    }
}

data class YtdlpVideoInfoResponse(
    val videoId: String?,
    val title: String?,
    val author: String?,
    val lengthSeconds: Int?,
    val thumbnailUrl: String?,
    val subtitles: List<YtdlpSubtitleTrack>?,
    val error: String?,
)

data class YtdlpSubtitleTrack(
    val language: String?,
    val label: String?,
    val automatic: Boolean?,
    val default: Boolean?,
)

data class YtdlpVideoResponse(
    val videoId: String?,
    val title: String?,
    val author: String?,
    val lengthSeconds: Int?,
    val streamUrl: String?,
    val hlsManifestUrl: String?,
    val thumbnailUrl: String?,
    val error: String?,
)

data class YtdlpFormat(
    val format_id: String?,
    val ext: String?,
    val resolution: String?,
    val fps: String?,
    val url: String?,
    val mimeType: String?,
    val qualityLabel: String?,
    val bitrate: Long?,
)

data class YtdlpFormatsResponse(
    val videoId: String?,
    val title: String?,
    val author: String?,
    val lengthSeconds: Int?,
    val formats: List<YtdlpFormat>?,
    val error: String?,
)

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
    val lengthSeconds: Int,
    val streamUrl: String,
    val hlsManifestUrl: String?,
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

private fun YtdlpSubtitleTrack.toSubtitleTrack(videoId: String, baseUrl: String): SubtitleTrack {
    val language = language?.takeIf { it.isNotBlank() } ?: "en"
    val automatic = automatic ?: false
    return SubtitleTrack(
        language = language,
        label = label?.takeIf { it.isNotBlank() } ?: language,
        url = "$baseUrl/video/$videoId/subtitle?lang=${urlEncode(language)}&automatic=$automatic",
        automatic = automatic,
        default = default ?: false,
    )
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
