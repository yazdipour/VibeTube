package com.smarttube.web.auth

import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.youtube.AuthPollResult
import com.smarttube.web.youtube.PendingDeviceLogin
import com.smarttube.web.youtube.StoredYouTubeSession

object AuthMappers {
    fun toStatusResponse(session: StoredYouTubeSession?): AuthStatusResponse = AuthStatusResponse(
        signedIn = session != null,
        provider = session?.let { "google" },
        user = session?.account?.let {
            AuthUserDto(
                id = it.pageId ?: "youtube-account",
                displayName = it.displayName ?: (it.channelName ?: "YouTube account"),
                email = it.email ?: "Not available",
                avatarUrl = it.avatarUrl ?: "",
            )
        },
        stub = liveMetadata("Auth status", if (session != null) "youtube-device-auth" else "backend-session"),
    )

    fun toLoginResponse(login: PendingDeviceLogin, provider: String): AuthLoginResponse = AuthLoginResponse(
        provider = provider,
        device = AuthDeviceCodeDto(
            loginId = login.loginId,
            userCode = login.userCode,
            verificationUrl = login.verificationUrl,
            intervalSeconds = login.intervalSeconds,
            expiresAtEpochSecond = login.expiresAtEpochSecond,
        ),
        stub = liveMetadata("Auth login start", "youtube-device-auth"),
    )

    fun toLoginPollResponse(poll: AuthPollResult, status: AuthStatusResponse?): AuthLoginPollResponse = AuthLoginPollResponse(
        completed = poll.completed,
        authorizationPending = poll.authorizationPending,
        error = poll.error,
        status = status,
        stub = liveMetadata("Auth login poll", "youtube-device-auth"),
    )
}
