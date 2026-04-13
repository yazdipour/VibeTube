package com.smarttube.web.youtube

interface YouTubePlaybackProvider {
    fun getVideoStreamUrl(videoId: String, formatSelector: String = "bestvideo+bestaudio/best"): VideoStreamInfo?

    fun getAvailableFormats(videoId: String): VideoFormatsInfo?

    fun getDashManifest(videoId: String, videoItag: Int? = null): String?

    fun getAdaptiveStreamUrl(videoId: String, itag: Int): String?

    fun getAdaptiveStreamUrls(videoId: String, itag: Int): List<String>

    fun getSubtitleContent(captionUrl: String): String
}
