package com.smarttube.web.videos

import com.smarttube.web.youtube.YouTubeHttpClient
import com.smarttube.web.youtube.YouTubeVideoService
import com.smarttube.web.youtube.SubtitleTrack
import com.smarttube.web.youtube.VideoFormatsInfo
import com.smarttube.web.youtube.VideoFormat
import com.smarttube.web.youtube.VideoStreamInfo
import org.springframework.http.HttpStatusCode
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
import java.net.URLDecoder
import java.net.http.HttpResponse

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
    fun getDashManifest(
        @PathVariable videoId: String,
        @RequestParam(required = false) itag: Int?,
    ): ResponseEntity<String> = ResponseEntity
        .ok()
        .contentType(MediaType.parseMediaType("application/dash+xml; charset=UTF-8"))
        .body(videoService.getDashManifest(videoId, itag))

    @GetMapping("/{videoId}/adaptive/{itag}")
    fun getAdaptiveMedia(
        @PathVariable videoId: String,
        @PathVariable itag: Int,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        proxyMedia(videoId, videoService.getAdaptiveStreamUrl(videoId, itag), headers, videoService.getAdaptiveStreamUrls(videoId, itag))

    @GetMapping("/{videoId}/media")
    fun getMedia(
        @PathVariable videoId: String,
        @RequestParam url: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> = proxyMedia(videoId, url, headers)

    private fun proxyMedia(
        videoId: String,
        url: String,
        requestHeaders: HttpHeaders,
        alternateUrls: List<String> = emptyList(),
    ): ResponseEntity<Any> {
        val upstream = fetchUpstreamMedia(url, requestHeaders, alternateUrls)
        val payload = upstream.body()
        val contentType = upstream.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null)
        val responseHeaders = copyUpstreamResponseHeaders(upstream.headers().map())
        if (upstream.statusCode() !in 200..299) {
            return ResponseEntity.status(HttpStatusCode.valueOf(upstream.statusCode()))
                .headers(responseHeaders)
                .body(payload)
        }
        val resolvedContentType = contentType ?: guessContentType(url, payload)
        return if (isHlsContent(resolvedContentType, payload)) {
            val manifest = payload.toString(StandardCharsets.UTF_8)
            responseHeaders.remove(HttpHeaders.CONTENT_LENGTH)
            responseHeaders.remove(HttpHeaders.CONTENT_RANGE)
            responseHeaders.remove(HttpHeaders.ACCEPT_RANGES)
            ResponseEntity.status(HttpStatusCode.valueOf(upstream.statusCode()))
                .headers(responseHeaders)
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(rewriteManifest(videoId, url, manifest))
        } else {
            responseHeaders[HttpHeaders.CACHE_CONTROL] = listOf(
                responseHeaders.getFirst(HttpHeaders.CACHE_CONTROL) ?: "public, max-age=300",
            )
            ResponseEntity.status(HttpStatusCode.valueOf(upstream.statusCode()))
                .headers(responseHeaders)
                .contentType(MediaType.parseMediaType(resolvedContentType))
                .body(payload)
        }
    }

    private fun fetchUpstreamMedia(url: String, headers: HttpHeaders, alternateUrls: List<String> = emptyList()): HttpResponse<ByteArray> {
        val attemptedUrls = linkedSetOf(url)
        val candidates = sequenceOf(url)
            .plus(alternateUrls.asSequence())
            .filter { attemptedUrls.add(it) }

        var lastResponse: HttpResponse<ByteArray>? = null
        for (candidateUrl in candidates) {
            val upstreamHeaders = buildUpstreamMediaHeaders(candidateUrl, headers)
            val upstream = httpClient.getBytesResponse(candidateUrl, headers = upstreamHeaders)
            lastResponse = upstream
            if (upstream.statusCode() != 403 || !isGoogleVideoUrl(candidateUrl)) {
                return upstream
            }
        }
        return lastResponse ?: httpClient.getBytesResponse(url, headers = buildUpstreamMediaHeaders(url, headers))
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
        private const val IOS_USER_AGENT = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val ANDROID_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; Pixel 8 Pro Build/AP1A.240305.019.A1) gzip"

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

        private fun buildUpstreamMediaHeaders(url: String, headers: HttpHeaders): Map<String, String> = buildMap {
            put("User-Agent", resolveMediaUserAgent(url))
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            val forwardedRequestHeaders = if (isHlsManifestUrl(url)) {
                FORWARDED_REQUEST_HEADERS.filterNot {
                    it == HttpHeaders.RANGE || it == HttpHeaders.IF_RANGE || it == HttpHeaders.ACCEPT_ENCODING
                }
            } else {
                FORWARDED_REQUEST_HEADERS
            }
            forwardedRequestHeaders.forEach { headerName ->
                headers.getFirst(headerName)?.takeIf { it.isNotBlank() }?.let { put(headerName, it) }
            }
            if (isHlsManifestUrl(url)) {
                put(HttpHeaders.ACCEPT_ENCODING, "identity")
            }
        }

        private fun resolveMediaUserAgent(url: String): String {
            val client = extractQueryParam(url, "c")?.uppercase()
            return when (client) {
                "IOS" -> IOS_USER_AGENT
                "ANDROID" -> ANDROID_USER_AGENT
                else -> WEB_USER_AGENT
            }
        }

        private fun extractQueryParam(url: String, key: String): String? {
            val query = runCatching { URI.create(url).rawQuery }.getOrNull() ?: return null
            return query.split('&')
                .asSequence()
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val name = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8)
                    if (name != key) return@mapNotNull null
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8)
                }
                .firstOrNull()
        }

        private fun isGoogleVideoUrl(url: String): Boolean =
            runCatching { URI.create(url).host.orEmpty() }.getOrNull()?.contains("googlevideo.com") == true

        private fun isHlsManifestUrl(url: String): Boolean =
            url.contains("manifest.googlevideo.com/api/manifest/", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true)

        private fun copyUpstreamResponseHeaders(headers: Map<String, List<String>>): HttpHeaders =
            HttpHeaders().apply {
                COPIED_RESPONSE_HEADERS.forEach { headerName ->
                    headers[headerName]?.forEach { value -> add(headerName, value) }
                }
            }

        private fun isHlsContent(contentType: String, payload: ByteArray): Boolean {
            val sniffedPrefix = payload.copyOfRange(0, minOf(payload.size, 7))
                .toString(StandardCharsets.UTF_8)
            return contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("application/x-mpegURL", ignoreCase = true) ||
                contentType.startsWith("text/", ignoreCase = true) && sniffedPrefix.startsWith("#EXTM3U") ||
                sniffedPrefix.startsWith("#EXTM3U")
        }

        private fun rewriteManifest(videoId: String, sourceUrl: String, manifest: String): String {
            val baseUri = URI.create(sourceUrl)
            val lines = manifest.lineSequence().toList()
            return if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:") }) {
                rewriteMasterManifest(videoId, baseUri, lines)
            } else {
                rewriteMediaManifest(videoId, baseUri, lines)
            }
        }

        private fun rewriteMasterManifest(videoId: String, baseUri: URI, lines: List<String>): String {
            val preferredAudioGroups = selectPreferredAudioGroups(lines)
            val rewritten = mutableListOf<String>()
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                when {
                    line.isBlank() -> rewritten += line
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val attributes = parseM3uAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                        val groupId = attributes["GROUP-ID"]
                        val type = attributes["TYPE"]?.uppercase()
                        if (type != "AUDIO" || groupId == null || preferredAudioGroups.isEmpty() || groupId in preferredAudioGroups) {
                            rewritten += rewriteTaggedManifestLine(videoId, baseUri, line)
                        }
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        val attributes = parseM3uAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                        var uriIndex = index + 1
                        while (uriIndex < lines.size && lines[uriIndex].startsWith("#")) {
                            uriIndex += 1
                        }
                        val streamUri = lines.getOrNull(uriIndex)
                        if (streamUri != null && shouldKeepMasterVariant(attributes, preferredAudioGroups)) {
                            rewritten += line
                            rewritten += proxyMediaUrl(videoId, baseUri.resolve(streamUri.trim()).toString())
                        }
                        index = if (streamUri != null) uriIndex else index
                    }
                    line.startsWith("#") -> rewritten += rewriteTaggedManifestLine(videoId, baseUri, line)
                    else -> rewritten += proxyMediaUrl(videoId, baseUri.resolve(line.trim()).toString())
                }
                index += 1
            }
            return rewritten.joinToString("\n")
        }

        private fun rewriteMediaManifest(videoId: String, baseUri: URI, lines: List<String>): String =
            lines.joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> rewriteTaggedManifestLine(videoId, baseUri, line)
                    else -> proxyMediaUrl(videoId, baseUri.resolve(line.trim()).toString())
                }
            }

        private fun rewriteTaggedManifestLine(videoId: String, baseUri: URI, line: String): String =
            Regex("""URI="([^"]+)"""")
                .replace(line) { match ->
                    val resolved = baseUri.resolve(match.groupValues[1]).toString()
                    """URI="${proxyMediaUrl(videoId, resolved)}""""
        }

        private fun shouldKeepMasterVariant(
            attributes: Map<String, String>,
            preferredAudioGroups: Set<String>,
        ): Boolean {
            val audioGroup = attributes["AUDIO"]
            val codecs = attributes["CODECS"].orEmpty()
            return (preferredAudioGroups.isEmpty() || audioGroup == null || audioGroup in preferredAudioGroups) &&
                isPreferredVideoCodec(codecs)
        }

        private fun selectPreferredAudioGroups(lines: List<String>): Set<String> {
            val audioTracks = lines
                .filter { it.startsWith("#EXT-X-MEDIA:") }
                .map { parseM3uAttributes(it.removePrefix("#EXT-X-MEDIA:")) }
                .filter { it["TYPE"]?.uppercase() == "AUDIO" }

            val originalGroups = audioTracks
                .filter { isOriginalAudioTrack(it) }
                .mapNotNull { it["GROUP-ID"] }
                .toSet()
            if (originalGroups.isNotEmpty()) {
                return originalGroups
            }

            val nonDubbedGroups = audioTracks
                .filterNot { isDubbedAudioTrack(it) }
                .mapNotNull { it["GROUP-ID"] }
                .toSet()
            if (nonDubbedGroups.isNotEmpty()) {
                return nonDubbedGroups
            }

            return audioTracks.mapNotNull { it["GROUP-ID"] }.toSet()
        }

        private fun isOriginalAudioTrack(attributes: Map<String, String>): Boolean =
            audioTrackDescriptor(attributes).contains("original", ignoreCase = true)

        private fun isDubbedAudioTrack(attributes: Map<String, String>): Boolean =
            audioTrackDescriptor(attributes).contains("dubbed", ignoreCase = true)

        private fun audioTrackDescriptor(attributes: Map<String, String>): String =
            listOfNotNull(
                attributes["NAME"],
                attributes["LANGUAGE"],
                attributes["YT-EXT-XTAGS"],
                attributes["YT-EXT-AUDIO-CONTENT-ID"],
            ).joinToString(" ")

        private fun isPreferredVideoCodec(codecs: String): Boolean =
            !codecs.contains("vp09", ignoreCase = true)

        private fun parseM3uAttributes(raw: String): Map<String, String> {
            val values = linkedMapOf<String, String>()
            val parts = Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)""").split(raw)
            for (part in parts) {
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    continue
                }
                val key = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim().trim('"')
                values[key] = value
            }
            return values
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
