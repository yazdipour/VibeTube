package com.smarttube.web.youtube

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class YouTubeVideoServiceTest {
    @Test
    fun `get video stream uses innertube provider`() {
        val expected = sampleStreamInfo()
        val provider = FakePlaybackProvider(stream = expected)

        val service = YouTubeVideoService(provider)

        val actual = service.getVideoStreamUrl("abc123")

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `get available formats uses innertube provider`() {
        val expected = sampleFormatsInfo()
        val provider = FakePlaybackProvider(formats = expected)

        val service = YouTubeVideoService(provider)

        val actual = service.getAvailableFormats("abc123")

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `get subtitle content uses innertube provider`() {
        val provider = FakePlaybackProvider(subtitle = "WEBVTT")

        val service = YouTubeVideoService(provider)

        assertThat(service.getSubtitleContent("https://example.com/caption"))
            .isEqualTo("WEBVTT")
    }

    @Test
    fun `get adaptive stream uses innertube provider`() {
        val provider = FakePlaybackProvider(adaptiveUrl = "https://example.com/adaptive.mp4")

        val service = YouTubeVideoService(provider)

        assertThat(service.getAdaptiveStreamUrl("abc123", 137))
            .isEqualTo("https://example.com/adaptive.mp4")
    }

    @Test
    fun `get adaptive stream urls uses innertube provider`() {
        val provider = FakePlaybackProvider(adaptiveUrls = listOf("https://example.com/a.mp4", "https://example.com/b.mp4"))

        val service = YouTubeVideoService(provider)

        assertThat(service.getAdaptiveStreamUrls("abc123", 137))
            .containsExactly("https://example.com/a.mp4", "https://example.com/b.mp4")
    }

    @Test
    fun `get video stream preserves provider failure`() {
        val provider = FakePlaybackProvider(
            streamError = ResponseStatusException(HttpStatus.BAD_GATEWAY, "innertube failed"),
        )

        val service = YouTubeVideoService(provider)

        assertThatThrownBy { service.getVideoStreamUrl("abc123") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("innertube failed")
    }

    private fun sampleStreamInfo() = VideoStreamInfo(
        videoId = "abc123",
        title = "video",
        author = "author",
        channelId = "channel123",
        lengthSeconds = 123,
        streamUrl = "https://example.com/master.m3u8",
        hlsManifestUrl = "https://example.com/master.m3u8",
        dashManifestUrl = null,
        formats = emptyList(),
        subtitles = emptyList(),
    )

    private fun sampleFormatsInfo() = VideoFormatsInfo(
        videoId = "abc123",
        title = "video",
        author = "author",
        lengthSeconds = 123,
        formats = listOf(
            VideoFormat(
                formatId = "1080p",
                itag = 1080,
                url = "https://example.com/1080.m3u8",
                mimeType = "application/x-mpegURL",
                qualityLabel = "1080p",
                bitrate = 1_000L,
            ),
        ),
    )

    private class FakePlaybackProvider(
        private val stream: VideoStreamInfo? = null,
        private val formats: VideoFormatsInfo? = null,
        private val adaptiveUrl: String? = null,
        private val adaptiveUrls: List<String> = adaptiveUrl?.let(::listOf) ?: emptyList(),
        private val subtitle: String = "",
        private val streamError: RuntimeException? = null,
        private val formatsError: RuntimeException? = null,
        private val subtitleError: RuntimeException? = null,
    ) : YouTubePlaybackProvider {
        override fun getVideoStreamUrl(videoId: String, formatSelector: String): VideoStreamInfo? {
            streamError?.let { throw it }
            return stream
        }

        override fun getAvailableFormats(videoId: String): VideoFormatsInfo? {
            formatsError?.let { throw it }
            return formats
        }

        override fun getDashManifest(videoId: String, videoItag: Int?): String? = null

        override fun getAdaptiveStreamUrl(videoId: String, itag: Int): String? = adaptiveUrl

        override fun getAdaptiveStreamUrls(videoId: String, itag: Int): List<String> = adaptiveUrls

        override fun getSubtitleContent(captionUrl: String): String {
            subtitleError?.let { throw it }
            return subtitle
        }
    }
}
