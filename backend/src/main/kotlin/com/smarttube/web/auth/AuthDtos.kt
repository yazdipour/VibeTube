package com.smarttube.web.auth

import com.smarttube.web.stub.StubMetadata

data class AuthUserDto(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String,
)

data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

data class AuthLoginRequest(
    val provider: String = "google",
)

data class AuthDeviceCodeDto(
    val loginId: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long,
    val expiresAtEpochSecond: Long,
)

data class AuthStatusResponse(
    val signedIn: Boolean,
    val provider: String?,
    val user: AuthUserDto?,
    val stub: StubMetadata,
)

data class AuthLoginResponse(
    val provider: String,
    val device: AuthDeviceCodeDto,
    val stub: StubMetadata,
)

data class AuthLoginPollResponse(
    val completed: Boolean,
    val authorizationPending: Boolean,
    val error: String?,
    val status: AuthStatusResponse?,
    val stub: StubMetadata,
)

data class AuthLogoutResponse(
    val signedOut: Boolean,
    val stub: StubMetadata,
)
