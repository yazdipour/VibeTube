package com.smarttube.web.youtube

interface YouTubePlaybackProvider {
    fun getVideoStreamUrl(videoId: String, formatSelector: String = "bestvideo+bestaudio/best"): VideoStreamInfo?

    fun getAvailableFormats(videoId: String): VideoFormatsInfo?

    fun getDashManifest(videoId: String): String?

    fun getSubtitleContent(captionUrl: String): String
}
