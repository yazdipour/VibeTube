package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class YouTubeAuthService(
    private val httpClient: YouTubeHttpClient,
    private val properties: YouTubeProperties,
    private val sessionStore: YouTubeSessionStore,
) {
    private val pendingLogins = ConcurrentHashMap<String, PendingDeviceLogin>()

    fun startDeviceLogin(): PendingDeviceLogin {
        val clientId = properties.oauthClientId?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "YOUTUBE_OAUTH_CLIENT_ID is not set. Set this env var with your Google OAuth client id to enable login.")
        val clientSecret = properties.oauthClientSecret?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "YOUTUBE_OAUTH_CLIENT_SECRET is not set. Set this env var with your Google OAuth client secret to enable login.")

        val response = httpClient.postJson(
            "https://oauth2.googleapis.com/device/code",
            mapOf(
                "client_id" to clientId,
                "scope" to SCOPES,
            ),
        )

        val login = PendingDeviceLogin(
            loginId = UUID.randomUUID().toString(),
            deviceCode = response.requiredText("device_code"),
            userCode = response.requiredText("user_code"),
            verificationUrl = response.text("verification_url") ?: properties.activateUrl,
            intervalSeconds = response.long("interval") ?: 5L,
            expiresAtEpochSecond = Instant.now().epochSecond + (response.long("expires_in") ?: 1800L),
            clientId = clientId,
            clientSecret = clientSecret,
        )

        pendingLogins[login.loginId] = login
        return login
    }

    fun pollDeviceLogin(loginId: String): AuthPollResult {
        val pending = pendingLogins[loginId]
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No pending login found for id $loginId")

        if (pending.expiresAtEpochSecond <= Instant.now().epochSecond) {
            pendingLogins.remove(loginId)
            return AuthPollResult(completed = false, authorizationPending = false, error = "expired_token")
        }

        val tokenResponse = httpClient.postJsonAllowError(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to pending.deviceCode,
                "client_id" to pending.clientId,
                "client_secret" to pending.clientSecret,
            ),
        )

        val error = tokenResponse.text("error")
        if (error != null) {
            val authorizationPending = error == "authorization_pending" || error == "slow_down"
            if (!authorizationPending) {
                pendingLogins.remove(loginId)
            }
            return AuthPollResult(completed = false, authorizationPending = authorizationPending, error = error)
        }

        val auth = tokenResponse.toAuthResult()
        val account = fetchAccountProfile(auth.accessToken, pending)
        sessionStore.save(
            StoredYouTubeSession(
                refreshToken = auth.refreshToken ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube did not return a refresh token"),
                accessToken = auth.accessToken,
                tokenType = auth.tokenType,
                expiresAtEpochSecond = Instant.now().epochSecond + auth.expiresInSeconds,
                clientId = pending.clientId,
                clientSecret = pending.clientSecret,
                account = account?.let {
                    StoredYouTubeAccount(
                        displayName = it.displayName,
                        email = it.email,
                        channelName = it.channelName,
                        avatarUrl = it.avatarUrl,
                        pageId = it.pageId,
                    )
                },
            ),
        )
        pendingLogins.remove(loginId)

        return AuthPollResult(completed = true, authorizationPending = false, error = null)
    }

    fun getSession(): StoredYouTubeSession? = sessionStore.load()

    fun clearSession() {
        sessionStore.clear()
    }

    fun getAuthorizedSession(): StoredYouTubeSession {
        val session = sessionStore.load()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in")
        val now = Instant.now().epochSecond
        if (session.accessToken != null && session.expiresAtEpochSecond != null && session.expiresAtEpochSecond > now + 60) {
            return session
        }

        val refreshed = refreshAccessToken(session)
        sessionStore.save(refreshed)
        return refreshed
    }

    private fun refreshAccessToken(session: StoredYouTubeSession): StoredYouTubeSession {
        val tokenResponse = httpClient.postJson(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "refresh_token" to session.refreshToken,
                "client_id" to session.clientId,
                "client_secret" to session.clientSecret,
                "grant_type" to "refresh_token",
            ),
        )

        val refreshed = tokenResponse.toAuthResult()
        val account = session.account ?: fetchAccountProfile(refreshed.accessToken, null)?.let {
            StoredYouTubeAccount(
                displayName = it.displayName,
                email = it.email,
                channelName = it.channelName,
                avatarUrl = it.avatarUrl,
                pageId = it.pageId,
            )
        }

        return session.copy(
            accessToken = refreshed.accessToken,
            tokenType = refreshed.tokenType,
            expiresAtEpochSecond = Instant.now().epochSecond + refreshed.expiresInSeconds,
            account = account,
        )
    }

    private fun fetchAccountProfile(accessToken: String, pending: PendingDeviceLogin?): YouTubeAccountProfile? {
        val body = mapOf(
            "context" to mapOf(
                "client" to linkedMapOf(
                    "clientName" to TV_CLIENT_NAME,
                    "clientVersion" to TV_CLIENT_VERSION,
                    "clientScreen" to "WATCH",
                    "userAgent" to TV_USER_AGENT,
                    "acceptLanguage" to "en",
                    "acceptRegion" to "US",
                    "utcOffsetMinutes" to "0",
                ),
                "user" to mapOf(
                    "enableSafetyMode" to false,
                    "lockedSafetyMode" to false,
                ),
            ),
            "accountReadMask" to mapOf(
                "returnOwner" to true,
                "returnBrandAccounts" to true,
                "returnPersonaAccounts" to false,
            ),
        )

        val (_, json) = httpClient.postJsonWithStatus(
            "https://www.youtube.com/youtubei/v1/account/accounts_list",
            body,
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "User-Agent" to TV_USER_AGENT,
                "X-YouTube-Client-Name" to TV_CLIENT_NAME_ID,
                "X-YouTube-Client-Version" to TV_CLIENT_VERSION,
                "Referer" to "https://www.youtube.com/tv",
            ),
        )

        val items = json.path("contents").firstOrNull()
            ?.path("accountSectionListRenderer")
            ?.path("contents")
            ?.firstOrNull()
            ?.path("accountItemSectionRenderer")
            ?.path("contents")
            ?: return null

        val selected = items.firstOrNull { it.path("accountItem").path("isSelected").asBoolean(false) }
            ?.path("accountItem")
            ?: items.firstOrNull()?.path("accountItem")
            ?: return null

        return YouTubeAccountProfile(
            displayName = selected.pathText("accountName"),
            email = selected.pathText("accountByline"),
            channelName = selected.pathText("channelHandle"),
            avatarUrl = selected.path("accountPhoto").path("thumbnails").lastOrNull()?.path("url")?.asText(null),
            pageId = selected.path("serviceEndpoint")
                .path("selectActiveIdentityEndpoint")
                .path("supportedTokens")
                .firstOrNull()
                ?.path("pageIdToken")
                ?.path("pageId")
                ?.asText(null),
        )
    }

    companion object {
        private const val TV_CLIENT_NAME = "TVHTML5"
        private const val TV_CLIENT_NAME_ID = "7"
        private const val TV_CLIENT_VERSION = "7.20260311.12.00"
        private const val TV_USER_AGENT = "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; Fire OS 6.0) Cobalt/22.lts.3.306369-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/13, Amazon_ATV_mediatek8695_2019/NS6294 (Amazon, AFTMM, Wireless) com.amazon.firetv.youtube/22.3.r2.v66.0"
        private const val SCOPES = "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.force-ssl"
    }
}

data class AuthPollResult(
    val completed: Boolean,
    val authorizationPending: Boolean,
    val error: String?,
)

private fun JsonNode.text(fieldName: String): String? = path(fieldName).asText(null)

private fun JsonNode.long(fieldName: String): Long? = path(fieldName).takeIf { !it.isMissingNode && !it.isNull }?.asLong()

private fun JsonNode.requiredText(fieldName: String): String = text(fieldName)
    ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube response is missing '$fieldName'")

private fun JsonNode.toAuthResult(): YouTubeAuthResult =
    YouTubeAuthResult(
        accessToken = requiredText("access_token"),
        tokenType = text("token_type") ?: "Bearer",
        expiresInSeconds = long("expires_in") ?: 3600L,
        refreshToken = text("refresh_token"),
    )

private fun JsonNode.pathText(fieldName: String): String? {
    val node = path(fieldName)
    if (node.isMissingNode || node.isNull) {
        return null
    }
    return node.path("simpleText").asText(null)
        ?: node.path("runs").firstOrNull()?.path("text")?.asText(null)
        ?: node.asText(null)
}

private fun JsonNode.firstOrNull(): JsonNode? = if (isArray && size() > 0) get(0) else null

private fun JsonNode.lastOrNull(): JsonNode? = if (isArray && size() > 0) get(size() - 1) else null
