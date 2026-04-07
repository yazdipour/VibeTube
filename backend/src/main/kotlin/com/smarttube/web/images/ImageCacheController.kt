package com.smarttube.web.images

import com.smarttube.web.youtube.YouTubeHttpClient
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/image-cache")
class ImageCacheController(
    private val imageCacheService: ImageCacheService,
) {
    @GetMapping
    fun getImage(@RequestParam url: String): ResponseEntity<ByteArray> {
        val image = imageCacheService.get(url)
        return ResponseEntity.ok()
            .contentType(image.contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .body(image.bytes)
    }
}

@Service
class ImageCacheService(
    private val httpClient: YouTubeHttpClient,
) {
    private val cache = ConcurrentHashMap<String, CachedImage>()

    fun get(url: String): CachedImage {
        validateImageUrl(url)
        val now = System.currentTimeMillis()
        val cached = cache[url]

        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) {
            return cached
        }

        val (bytes, contentTypeHeader) = httpClient.getBytes(url, headers = mapOf("Accept" to "image/*"))
        val image = CachedImage(
            bytes = bytes,
            contentType = parseContentType(contentTypeHeader),
            cachedAtMs = now,
        )
        cache[url] = image
        evictOldEntries()
        return image
    }

    private fun validateImageUrl(url: String) {
        val uri = runCatching { URI.create(url) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image URL")
        val host = uri.host?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image URL")
        val allowed = uri.scheme == "https" && ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }

        if (!allowed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Image host is not allowed")
        }
    }

    private fun parseContentType(contentTypeHeader: String?): MediaType =
        runCatching {
            contentTypeHeader
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?.let(MediaType::parseMediaType)
        }.getOrNull() ?: MediaType.APPLICATION_OCTET_STREAM

    private fun evictOldEntries() {
        if (cache.size <= MAX_CACHE_ENTRIES) {
            return
        }

        cache.entries
            .sortedBy { it.value.cachedAtMs }
            .take(cache.size - MAX_CACHE_ENTRIES)
            .forEach { cache.remove(it.key) }
    }

    companion object {
        private val ALLOWED_HOST_SUFFIXES = setOf("ggpht.com", "googleusercontent.com", "ytimg.com")
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_CACHE_ENTRIES = 512
    }
}

data class CachedImage(
    val bytes: ByteArray,
    val contentType: MediaType,
    val cachedAtMs: Long,
)
