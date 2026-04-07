package com.smarttube.web.youtube

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "youtube")
data class YouTubeProperties(
    val dataDir: String = "./data",
    val activateUrl: String = "https://www.youtube.com/activate",
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    val ytdlpExternalUrl: String? = null,
    val innertubeApiKey: String? = null,
)
