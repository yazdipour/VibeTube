package com.smarttube.web.youtube

data class StoredYouTubeAccount(
    val displayName: String? = null,
    val email: String? = null,
    val channelName: String? = null,
    val avatarUrl: String? = null,
    val pageId: String? = null,
)

data class StoredYouTubeSession(
    val refreshToken: String,
    val accessToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAtEpochSecond: Long? = null,
    val clientId: String,
    val clientSecret: String,
    val account: StoredYouTubeAccount? = null,
)

data class PendingDeviceLogin(
    val loginId: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long,
    val expiresAtEpochSecond: Long,
    val clientId: String,
    val clientSecret: String,
)

data class YouTubeAuthResult(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
)

data class YouTubeAccountProfile(
    val displayName: String?,
    val email: String?,
    val channelName: String?,
    val avatarUrl: String?,
    val pageId: String?,
)
