package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.smarttube.web.stub.liveMetadata
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class YouTubeSubscriptionService(
    private val authService: YouTubeAuthService,
    private val httpClient: YouTubeHttpClient,
) {
    fun subscribe(channelId: String): Boolean {
        val session = authService.getAuthorizedSession()
        postJson(
            "https://www.googleapis.com/youtube/v3/subscriptions",
            mapOf("part" to "snippet"),
            mapOf(
                "snippet" to mapOf(
                    "resourceId" to mapOf(
                        "kind" to "youtube#channel",
                        "channelId" to channelId,
                    ),
                ),
            ),
            session,
        )
        return true
    }

    fun unsubscribe(subscriptionId: String): Boolean {
        val session = authService.getAuthorizedSession()
        deleteJson(
            "https://www.googleapis.com/youtube/v3/subscriptions",
            mapOf("id" to subscriptionId),
            session,
        )
        return true
    }

    fun findSubscriptionId(channelId: String): String? {
        val session = authService.getAuthorizedSession()
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/subscriptions",
            mapOf(
                "part" to "id",
                "mine" to "true",
                "forChannelId" to channelId,
                "maxResults" to "1",
            ),
            session,
        )
        return json.path("items").firstOrNull()?.path("id")?.asText()
    }

    private fun getJson(baseUrl: String, query: Map<String, String>, session: StoredYouTubeSession): JsonNode {
        val url = httpClient.buildUrl(baseUrl, query)
        return httpClient.getJson(
            url,
            headers = mapOf(
                "Authorization" to "${session.tokenType} ${session.accessToken}",
                "Accept" to "application/json",
            ),
        )
    }

    private fun postJson(baseUrl: String, query: Map<String, String>, body: Any, session: StoredYouTubeSession): JsonNode {
        val url = httpClient.buildUrl(baseUrl, query)
        val (status, json) = httpClient.postJsonWithStatus(
            url,
            body,
            headers = mapOf(
                "Authorization" to "${session.tokenType} ${session.accessToken}",
                "Accept" to "application/json",
            ),
        )
        if (status !in 200..299) {
            val errorMessage = json.path("error").path("message").asText("Unknown YouTube API error")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, errorMessage)
        }
        return json
    }

    private fun deleteJson(baseUrl: String, query: Map<String, String>, session: StoredYouTubeSession): JsonNode {
        val url = httpClient.buildUrl(baseUrl, query)
        val (status, json) = httpClient.deleteJsonWithStatus(
            url,
            headers = mapOf(
                "Authorization" to "${session.tokenType} ${session.accessToken}",
                "Accept" to "application/json",
            ),
        )
        if (status !in 200..299) {
            val errorMessage = json.path("error").path("message").asText("Unknown YouTube API error")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, errorMessage)
        }
        return json
    }

    private fun JsonNode.firstOrNull(): JsonNode? = if (isArray && size() > 0) get(0) else null
}
