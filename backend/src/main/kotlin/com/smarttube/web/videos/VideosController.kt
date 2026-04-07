package com.smarttube.web.videos

import com.smarttube.web.youtube.YouTubeVideoService
import com.smarttube.web.youtube.SubtitleTrack
import com.smarttube.web.youtube.VideoFormatsInfo
import com.smarttube.web.youtube.VideoStreamInfo
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/videos")
class VideosController(
    private val videoService: YouTubeVideoService,
) {
    @GetMapping("/{videoId}")
    fun getVideo(@PathVariable videoId: String, @RequestParam(defaultValue = "bestvideo+bestaudio/best") format: String): VideoStreamResponse = 
        videoService.getVideoStreamUrl(videoId, format).toResponse()

    @GetMapping("/{videoId}/formats")
    fun getVideoFormats(@PathVariable videoId: String): VideoFormatsResponse = 
        videoService.getAvailableFormats(videoId).toResponse()
    
    companion object {
        private fun VideoStreamInfo.toResponse() = VideoStreamResponse(
            videoId = videoId,
            title = title,
            author = author,
            lengthSeconds = lengthSeconds,
            streamUrl = streamUrl,
            hlsManifestUrl = hlsManifestUrl,
            formats = formats.map { it.toDto() },
            subtitles = subtitles.map { it.toDto() },
        )

        private fun SubtitleTrack.toDto() = SubtitleTrackDto(
            language = language,
            label = label,
            url = url,
            automatic = automatic,
            default = default,
        )
    }
}

data class VideoStreamResponse(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Int,
    val streamUrl: String,
    val hlsManifestUrl: String?,
    val formats: List<VideoFormatDto>,
    val subtitles: List<SubtitleTrackDto>,
)

data class SubtitleTrackDto(
    val language: String,
    val label: String,
    val url: String,
    val automatic: Boolean,
    val default: Boolean,
)

data class VideoFormatDto(
    val formatId: String,
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val bitrate: Long,
)

data class VideoFormatsResponse(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Int,
    val formats: List<VideoFormatDto>,
)

private fun VideoFormatsInfo.toResponse() = VideoFormatsResponse(
    videoId = videoId,
    title = title,
    author = author,
    lengthSeconds = lengthSeconds,
    formats = formats.map { it.toDto() }
)
