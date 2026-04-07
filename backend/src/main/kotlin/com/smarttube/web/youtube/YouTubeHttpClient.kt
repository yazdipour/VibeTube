package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class YouTubeHttpClient(
    private val objectMapper: ObjectMapper,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()

        headers.forEach(requestBuilder::header)

        val response = send(requestBuilder.build())
        ensureSuccess(response, "GET", url)
        return response.body()
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonNode =
        objectMapper.readTree(getText(url, headers))

    fun postJson(url: String, body: Any, headers: Map<String, String> = emptyMap()): JsonNode {
        val payload = objectMapper.writeValueAsString(body)
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        headers.forEach(requestBuilder::header)

        val response = send(requestBuilder.build())
        ensureSuccess(response, "POST", url)
        return objectMapper.readTree(response.body())
    }

    fun postJsonAllowError(url: String, body: Any, headers: Map<String, String> = emptyMap()): JsonNode {
        val payload = objectMapper.writeValueAsString(body)
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        headers.forEach(requestBuilder::header)

        val response = send(requestBuilder.build())
        return objectMapper.readTree(response.body())
    }

    fun postJsonWithStatus(url: String, body: Any, headers: Map<String, String> = emptyMap()): Pair<Int, JsonNode> {
        val payload = objectMapper.writeValueAsString(body)
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        headers.forEach(requestBuilder::header)

        val response = send(requestBuilder.build())
        return response.statusCode() to objectMapper.readTree(response.body())
    }

    fun deleteJsonWithStatus(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, JsonNode> {
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .DELETE()

        headers.forEach(requestBuilder::header)

        val response = send(requestBuilder.build())
        return response.statusCode() to objectMapper.readTree(response.body())
    }

    fun buildUrl(baseUrl: String, query: Map<String, String>): String {
        if (query.isEmpty()) {
            return baseUrl
        }

        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        return "$baseUrl?$queryString"
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Request to YouTube failed: ${error.message}",
                error,
            )
        }

    private fun ensureSuccess(response: HttpResponse<String>, method: String, url: String) {
        if (response.statusCode() in 200..299) {
            return
        }

        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "YouTube request failed for $method $url with status ${response.statusCode()}",
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
