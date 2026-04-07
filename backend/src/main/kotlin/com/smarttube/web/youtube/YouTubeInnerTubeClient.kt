package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class YouTubeInnerTubeClient(
    private val authService: YouTubeAuthService,
    private val httpClient: YouTubeHttpClient,
) : YouTubeInnerTubeApi {
    override fun browse(browseId: String, params: String?): JsonNode =
        post(
            endpoint = "browse",
            body = buildMap {
                put("browseId", browseId)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            },
        )

    override fun editPlaylist(playlistId: String, actions: List<Map<String, String>>) {
        post(
            endpoint = "browse/edit_playlist",
            body = mapOf(
                "playlistId" to playlistId,
                "actions" to actions,
            ),
        )
    }

    private fun post(endpoint: String, body: Map<String, Any>): JsonNode {
        val session = authService.getAuthorizedSession()
        val url = httpClient.buildUrl(
            "https://www.youtube.com/youtubei/v1/$endpoint",
            mapOf(
                "key" to TV_API_KEY,
                "prettyPrint" to "false",
            ),
        )

        val payload = linkedMapOf<String, Any>(
            "context" to mapOf(
                "client" to linkedMapOf(
                    "clientName" to TV_CLIENT_NAME,
                    "clientVersion" to TV_CLIENT_VERSION,
                    "clientScreen" to "WATCH",
                    "platform" to "TV",
                    "userAgent" to TV_USER_AGENT,
                    "acceptLanguage" to "en",
                    "acceptRegion" to "US",
                    "utcOffsetMinutes" to 0,
                    "visitorData" to TV_VISITOR_DATA,
                ),
                "user" to mapOf(
                    "enableSafetyMode" to false,
                    "lockedSafetyMode" to false,
                ),
                "request" to mapOf(
                    "useSsl" to true,
                ),
            ),
        )
        payload.putAll(body)

        val (status, json) = httpClient.postJsonWithStatus(
            url,
            payload,
            headers = mapOf(
                "Authorization" to "${session.tokenType} ${session.accessToken}",
                "User-Agent" to TV_USER_AGENT,
                "X-Goog-Api-Key" to TV_API_KEY,
                "X-Goog-Visitor-Id" to TV_VISITOR_DATA,
                "X-YouTube-Client-Name" to TV_CLIENT_NAME_ID,
                "X-YouTube-Client-Version" to TV_CLIENT_VERSION,
                "Referer" to "https://www.youtube.com/tv",
                "Origin" to "https://www.youtube.com",
                "Accept" to "application/json",
            ),
        )

        if (status !in 200..299) {
            val errorMessage = json.path("error").path("message").asText("Unknown YouTube InnerTube error")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, errorMessage)
        }

        return json
    }

    companion object {
        private const val TV_API_KEY = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8"
        private const val TV_CLIENT_NAME = "TVHTML5"
        private const val TV_CLIENT_NAME_ID = "7"
        private const val TV_CLIENT_VERSION = "7.20260311.12.00"
        private const val TV_VISITOR_DATA = "CgtoWjE4em93QVFFbyiUu5-FBg%3D%3D"
        private const val TV_USER_AGENT = "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; Fire OS 6.0) Cobalt/22.lts.3.306369-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/13, Amazon_ATV_mediatek8695_2019/NS6294 (Amazon, AFTMM, Wireless) com.amazon.firetv.youtube/22.3.r2.v66.0"
    }
}

interface YouTubeInnerTubeApi {
    fun browse(browseId: String, params: String? = null): JsonNode

    fun editPlaylist(playlistId: String, actions: List<Map<String, String>>)
}
