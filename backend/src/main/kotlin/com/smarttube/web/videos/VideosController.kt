package com.smarttube.web.videos

import com.smarttube.web.youtube.YouTubeHttpClient
import com.smarttube.web.youtube.YouTubeVideoService
import com.smarttube.web.youtube.SubtitleTrack
import com.smarttube.web.youtube.VideoFormatsInfo
import com.smarttube.web.youtube.VideoFormat
import com.smarttube.web.youtube.VideoStreamInfo
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/api/videos")
class VideosController(
    private val videoService: YouTubeVideoService,
    private val httpClient: YouTubeHttpClient,
) {
    @GetMapping("/{videoId}")
    fun getVideo(@PathVariable videoId: String, @RequestParam(defaultValue = "bestvideo+bestaudio/best") format: String): VideoStreamResponse = 
        videoService.getVideoStreamUrl(videoId, format).toResponse()

    @GetMapping("/{videoId}/formats")
    fun getVideoFormats(@PathVariable videoId: String): VideoFormatsResponse = 
        videoService.getAvailableFormats(videoId).toResponse()

    @GetMapping("/{videoId}/subtitle", produces = ["text/vtt; charset=UTF-8"])
    fun getSubtitle(
        @PathVariable videoId: String,
        @RequestParam url: String,
    ): ResponseEntity<String> = ResponseEntity
        .ok()
        .contentType(MediaType.parseMediaType("text/vtt; charset=UTF-8"))
        .body(videoService.getSubtitleContent(url))

    @GetMapping("/{videoId}/manifest.mpd", produces = ["application/dash+xml; charset=UTF-8"])
    fun getDashManifest(@PathVariable videoId: String): ResponseEntity<String> = ResponseEntity
        .ok()
        .contentType(MediaType.parseMediaType("application/dash+xml; charset=UTF-8"))
        .body(videoService.getDashManifest(videoId))

    @GetMapping("/{videoId}/media")
    fun getMedia(
        @PathVariable videoId: String,
        @RequestParam url: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> {
        val upstreamHeaders = buildUpstreamMediaHeaders(headers)
        val upstream = httpClient.getBytesResponse(
            url,
            headers = upstreamHeaders,
        )
        val payload = upstream.body()
        val contentType = upstream.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null)
        if (upstream.statusCode() !in 200..299) {
            return ResponseEntity.status(upstream.statusCode())
                .headers(copyUpstreamResponseHeaders(upstream.headers().map()))
                .body(payload)
        }
        val resolvedContentType = contentType ?: guessContentType(url, payload)
        return if (isHlsContent(url, resolvedContentType, payload)) {
            val manifest = payload.toString(StandardCharsets.UTF_8)
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(rewriteManifest(videoId, url, manifest))
        } else {
            ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .contentType(MediaType.parseMediaType(resolvedContentType))
                .body(payload)
        }
    }
    
    companion object {
        private fun VideoStreamInfo.toResponse() = VideoStreamResponse(
            videoId = videoId,
            title = title,
            author = author,
            channelId = channelId,
            lengthSeconds = lengthSeconds,
            streamUrl = toPublicMediaUrl(videoId, streamUrl),
            hlsManifestUrl = hlsManifestUrl?.let { toPublicMediaUrl(videoId, it) },
            dashManifestUrl = dashManifestUrl,
            formats = formats.map { it.toDto(videoId) },
            subtitles = subtitles.map { it.toDto(videoId) },
        )

        private fun VideoFormatsInfo.toResponse() = VideoFormatsResponse(
            videoId = videoId,
            title = title,
            author = author,
            lengthSeconds = lengthSeconds,
            formats = formats.map { it.toDto(videoId) },
        )

        private fun SubtitleTrack.toDto(videoId: String) = SubtitleTrackDto(
            language = language,
            label = label,
            url = "/api/videos/${encodePathSegment(videoId)}/subtitle?url=${encodeQueryValue(url)}",
            automatic = automatic,
            default = default,
        )

        private fun VideoFormat.toDto(videoId: String) = VideoFormatDto(
            formatId = formatId,
            itag = itag,
            url = toPublicMediaUrl(videoId, url),
            mimeType = mimeType,
            qualityLabel = qualityLabel,
            bitrate = bitrate,
        )

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun proxyMediaUrl(videoId: String, url: String): String =
            "/api/videos/${encodePathSegment(videoId)}/media?url=${encodeQueryValue(url)}"

        private fun toPublicMediaUrl(videoId: String, url: String): String =
            if (url.startsWith("http://") || url.startsWith("https://")) proxyMediaUrl(videoId, url) else url

        private const val WEB_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        private val FORWARDED_REQUEST_HEADERS = listOf(
            HttpHeaders.RANGE,
            HttpHeaders.ACCEPT,
            HttpHeaders.IF_RANGE,
            HttpHeaders.ACCEPT_ENCODING,
        )

        private val COPIED_RESPONSE_HEADERS = listOf(
            HttpHeaders.ACCEPT_RANGES,
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_RANGE,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ETAG,
            HttpHeaders.LAST_MODIFIED,
            HttpHeaders.VARY,
        )

        private fun buildUpstreamMediaHeaders(headers: HttpHeaders): Map<String, String> = buildMap {
            put("User-Agent", WEB_USER_AGENT)
            FORWARDED_REQUEST_HEADERS.forEach { headerName ->
                headers.getFirst(headerName)?.takeIf { it.isNotBlank() }?.let { put(headerName, it) }
            }
        }

        private fun copyUpstreamResponseHeaders(headers: Map<String, List<String>>): HttpHeaders =
            HttpHeaders().apply {
                COPIED_RESPONSE_HEADERS.forEach { headerName ->
                    headers[headerName]?.forEach { value -> add(headerName, value) }
                }
            }

        private fun isHlsContent(url: String, contentType: String, payload: ByteArray): Boolean {
            val sniffedPrefix = payload.copyOfRange(0, minOf(payload.size, 7))
                .toString(StandardCharsets.UTF_8)
            return contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("application/x-mpegURL", ignoreCase = true) ||
                contentType.startsWith("text/", ignoreCase = true) && sniffedPrefix.startsWith("#EXTM3U") ||
                sniffedPrefix.startsWith("#EXTM3U")
        }

        private fun rewriteManifest(videoId: String, sourceUrl: String, manifest: String): String {
            val baseUri = URI.create(sourceUrl)
            return manifest.lineSequence()
                .map { line ->
                    when {
                        line.isBlank() -> line
                        line.startsWith("#") -> rewriteTaggedManifestLine(videoId, baseUri, line)
                        else -> proxyMediaUrl(videoId, baseUri.resolve(line.trim()).toString())
                    }
                }
                .joinToString("\n")
        }

        private fun rewriteTaggedManifestLine(videoId: String, baseUri: URI, line: String): String =
            Regex("""URI="([^"]+)"""")
                .replace(line) { match ->
                    val resolved = baseUri.resolve(match.groupValues[1]).toString()
                    """URI="${proxyMediaUrl(videoId, resolved)}""""
        }

        private fun guessContentType(url: String, payload: ByteArray): String =
            when {
                payload.copyOfRange(0, minOf(payload.size, 7)).toString(StandardCharsets.UTF_8).startsWith("#EXTM3U") -> "application/vnd.apple.mpegurl"
                url.contains(".ts") -> "video/mp2t"
                url.contains(".m4s") -> "video/iso.segment"
                url.contains(".mp4") -> "video/mp4"
                else -> "application/octet-stream"
            }
    }
}

data class VideoStreamResponse(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String?,
    val lengthSeconds: Int,
    val streamUrl: String,
    val hlsManifestUrl: String?,
    val dashManifestUrl: String?,
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
