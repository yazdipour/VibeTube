package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.smarttube.web.playlists.CreatePlaylistRequest
import com.smarttube.web.playlists.PlaylistCreateResponse
import com.smarttube.web.playlists.PlaylistDetailsResponse
import com.smarttube.web.playlists.PlaylistDto
import com.smarttube.web.playlists.PlaylistItemDto
import com.smarttube.web.playlists.PlaylistListResponse
import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.subscriptions.SubscriptionDetailsResponse
import com.smarttube.web.subscriptions.SubscriptionDto
import com.smarttube.web.subscriptions.SubscriptionListResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class YouTubeDataService(
    private val authService: YouTubeAuthService,
    private val httpClient: YouTubeHttpClient,
) {
    fun listSubscriptions(): SubscriptionListResponse {
        val session = authService.getAuthorizedSession()
        val items = mutableListOf<SubscriptionDto>()
        var pageToken: String? = null

        do {
            val query = buildMap {
                put("part", "snippet")
                put("mine", "true")
                put("maxResults", "50")
                pageToken?.let { put("pageToken", it) }
            }
            val json = getJson(
                "https://www.googleapis.com/youtube/v3/subscriptions",
                query,
                session,
            )
            items += json.path("items").map { item ->
                val snippet = item.path("snippet")
                SubscriptionDto(
                    channelId = snippet.path("resourceId").path("channelId").asText(""),
                    channelName = snippet.path("title").asText("Unknown channel"),
                    thumbnailUrl = snippet.bestThumbnailUrl(),
                    subscriberCountLabel = "Subscribed channel",
                    notificationsEnabled = false,
                )
            }
            pageToken = json.path("nextPageToken").asText("").ifBlank { null }
        } while (pageToken != null)

        return SubscriptionListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Subscriptions listing", "youtube-data-api"),
        )
    }

    fun getSubscription(channelId: String): SubscriptionDetailsResponse {
        val session = authService.getAuthorizedSession()
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/channels",
            mapOf(
                "part" to "snippet,statistics",
                "id" to channelId,
                "maxResults" to "1",
            ),
            session,
        )

        val item = json.path("items").firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Channel $channelId was not found")
        val snippet = item.path("snippet")
        val statistics = item.path("statistics")

        return SubscriptionDetailsResponse(
            item = SubscriptionDto(
                channelId = channelId,
                channelName = snippet.path("title").asText("Unknown channel"),
                thumbnailUrl = snippet.bestThumbnailUrl(),
                subscriberCountLabel = statistics.path("subscriberCount").asText("Unknown") + " subscribers",
                notificationsEnabled = false,
            ),
            stub = liveMetadata("Subscription details", "youtube-data-api"),
        )
    }

    fun listSubscriptionUploadVideos(limit: Int = 20): List<YouTubeVideoCard> {
        val session = authService.getAuthorizedSession()
        val subscriptionJson = getJson(
            "https://www.googleapis.com/youtube/v3/subscriptions",
            mapOf(
                "part" to "snippet",
                "mine" to "true",
                "maxResults" to "20",
            ),
            session,
        )

        val uploadsPlaylistIds = subscriptionJson.path("items")
            .mapNotNull { item -> item.path("snippet").path("resourceId").path("channelId").asText("").takeIf { it.isNotBlank() } }
            .mapNotNull { channelId -> getUploadsPlaylistId(channelId, session) }

        return uploadsPlaylistIds
            .flatMap { playlistId -> listPlaylistVideoCards(playlistId, perPlaylistLimit = 3, session = session) }
            .distinctBy { it.videoId }
            .sortedByDescending { it.metadataLabel?.let(::parseInstantOrNull) ?: Instant.EPOCH }
            .take(limit)
    }

    fun listPlaylistVideoCards(playlistId: String, limit: Int = 20): List<YouTubeVideoCard> {
        val session = authService.getAuthorizedSession()
        return listPlaylistVideoCards(playlistId = playlistId, perPlaylistLimit = limit, session = session).take(limit)
    }

    fun listChannelUploadVideos(channelId: String, limit: Int = 20): List<YouTubeVideoCard> {
        val session = authService.getAuthorizedSession()
        val uploadsPlaylistId = getUploadsPlaylistId(channelId, session)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Uploads playlist for channel $channelId was not found")
        return listPlaylistVideoCards(playlistId = uploadsPlaylistId, perPlaylistLimit = limit, session = session).take(limit)
    }

    fun listPlaylists(): PlaylistListResponse {
        val session = authService.getAuthorizedSession()
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/playlists",
            mapOf(
                "part" to "snippet,contentDetails,status",
                "mine" to "true",
                "maxResults" to "50",
            ),
            session,
        )

        val items = json.path("items").map { playlist ->
            val snippet = playlist.path("snippet")
            val contentDetails = playlist.path("contentDetails")
            val status = playlist.path("status")
            PlaylistDto(
                playlistId = playlist.path("id").asText(""),
                title = snippet.path("title").asText("Untitled playlist"),
                description = snippet.path("description").asText(""),
                visibility = status.path("privacyStatus").asText("private"),
                itemCount = contentDetails.path("itemCount").asInt(0),
                items = emptyList(),
            )
        }

        return PlaylistListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Playlists listing", "youtube-data-api"),
        )
    }

    private fun getUploadsPlaylistId(channelId: String, session: StoredYouTubeSession): String? {
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/channels",
            mapOf(
                "part" to "contentDetails",
                "id" to channelId,
                "maxResults" to "1",
            ),
            session,
        )

        return json.path("items").firstOrNull()
            ?.path("contentDetails")
            ?.path("relatedPlaylists")
            ?.path("uploads")
            ?.asText("")
            ?.takeIf { it.isNotBlank() }
    }

    private fun listPlaylistVideoCards(
        playlistId: String,
        perPlaylistLimit: Int,
        session: StoredYouTubeSession,
    ): List<YouTubeVideoCard> {
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/playlistItems",
            mapOf(
                "part" to "snippet,contentDetails",
                "playlistId" to playlistId,
                "maxResults" to perPlaylistLimit.coerceIn(1, 50).toString(),
            ),
            session,
        )

        return json.path("items").mapNotNull { item ->
            val snippet = item.path("snippet")
            val videoId = snippet.path("resourceId").path("videoId").asText(
                item.path("contentDetails").path("videoId").asText(""),
            ).takeIf { it.isNotBlank() } ?: return@mapNotNull null

            YouTubeVideoCard(
                videoId = videoId,
                title = snippet.path("title").asText("Untitled video"),
                channelName = snippet.path("videoOwnerChannelTitle").asText(
                    snippet.path("channelTitle").asText("Unknown channel"),
                ),
                thumbnailUrl = snippet.bestThumbnailUrl(),
                durationLabel = null,
                metadataLabel = item.path("contentDetails").path("videoPublishedAt").asText(
                    snippet.path("publishedAt").asText(""),
                ).ifBlank { null },
            )
        }
    }

    fun getPlaylist(playlistId: String): PlaylistDetailsResponse {
        val session = authService.getAuthorizedSession()
        val playlistJson = getJson(
            "https://www.googleapis.com/youtube/v3/playlists",
            mapOf(
                "part" to "snippet,contentDetails,status",
                "id" to playlistId,
                "maxResults" to "1",
            ),
            session,
        )

        val playlist = playlistJson.path("items").firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist $playlistId was not found")

        val playlistItemsJson = getJson(
            "https://www.googleapis.com/youtube/v3/playlistItems",
            mapOf(
                "part" to "snippet,contentDetails",
                "playlistId" to playlistId,
                "maxResults" to "50",
            ),
            session,
        )

        val snippet = playlist.path("snippet")
        val contentDetails = playlist.path("contentDetails")
        val status = playlist.path("status")
        val items = playlistItemsJson.path("items").map { item ->
            val itemSnippet = item.path("snippet")
            PlaylistItemDto(
                videoId = itemSnippet.path("resourceId").path("videoId").asText(""),
                title = itemSnippet.path("title").asText("Untitled video"),
                durationLabel = "",
                channelName = itemSnippet.path("videoOwnerChannelTitle").asText("Unknown channel"),
                thumbnailUrl = itemSnippet.bestThumbnailUrl(),
                metadataLabel = item.path("contentDetails").path("videoPublishedAt").asText(""),
            )
        }

        return PlaylistDetailsResponse(
            item = PlaylistDto(
                playlistId = playlist.path("id").asText(playlistId),
                title = snippet.path("title").asText("Untitled playlist"),
                description = snippet.path("description").asText(""),
                visibility = status.path("privacyStatus").asText("private"),
                itemCount = contentDetails.path("itemCount").asInt(items.size),
                items = items,
            ),
            stub = liveMetadata("Playlist details", "youtube-data-api"),
        )
    }

    fun createPlaylist(request: CreatePlaylistRequest): PlaylistCreateResponse {
        val session = authService.getAuthorizedSession()
        val json = postJson(
            "https://www.googleapis.com/youtube/v3/playlists",
            mapOf("part" to "snippet,status"),
            mapOf(
                "snippet" to mapOf(
                    "title" to request.title,
                    "description" to request.description,
                ),
                "status" to mapOf(
                    "privacyStatus" to request.visibility,
                ),
            ),
            session,
        )

        val snippet = json.path("snippet")
        val status = json.path("status")

        return PlaylistCreateResponse(
            item = PlaylistDto(
                playlistId = json.path("id").asText(""),
                title = snippet.path("title").asText(request.title),
                description = snippet.path("description").asText(request.description),
                visibility = status.path("privacyStatus").asText(request.visibility),
                itemCount = 0,
                items = emptyList(),
            ),
            created = true,
            stub = liveMetadata("Playlist creation", "youtube-data-api"),
        )
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
}

private fun JsonNode.bestThumbnailUrl(): String = path("thumbnails").path("high").path("url").asText(
    path("thumbnails").path("medium").path("url").asText(
        path("thumbnails").path("default").path("url").asText(""),
    ),
)

private fun JsonNode.firstOrNull(): JsonNode? = if (isArray && size() > 0) get(0) else null

private fun parseInstantOrNull(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
