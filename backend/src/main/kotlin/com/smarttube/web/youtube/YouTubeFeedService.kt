package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Service

@Service
class YouTubeFeedService(
    private val innerTubeClient: YouTubeInnerTubeApi,
    private val dataService: YouTubeDataService,
) {
    fun listHomeVideos(): List<YouTubeVideoCard> {
        val homeVideos = runCatching { innerTubeClient.browse("FEwhat_to_watch").extractVideoCards() }.getOrDefault(emptyList())
        val subscriptionVideos = homeVideos.ifEmpty {
            runCatching { innerTubeClient.browse("FEsubscriptions").extractVideoCards() }.getOrDefault(emptyList())
        }
        return subscriptionVideos.ifEmpty { dataService.listSubscriptionUploadVideos(FEED_LIMIT) }.take(FEED_LIMIT)
    }

    fun listSubscriptionFeedVideos(): List<YouTubeVideoCard> {
        val videos = runCatching { innerTubeClient.browse("FEsubscriptions").extractVideoCards() }.getOrDefault(emptyList())
        return videos.ifEmpty { dataService.listSubscriptionUploadVideos(FEED_LIMIT) }.take(FEED_LIMIT)
    }

    fun listWatchLaterVideos(): List<YouTubeVideoCard> {
        val libraryWatchLaterVideos = runCatching {
            innerTubeClient.browse(WATCH_LATER_LIBRARY_BROWSE_ID, WATCH_LATER_LIBRARY_PARAMS).extractVideoCards()
        }.getOrDefault(emptyList())
        val playlistWatchLaterVideos = libraryWatchLaterVideos.ifEmpty {
            runCatching { innerTubeClient.browse(WATCH_LATER_PLAYLIST_BROWSE_ID).extractVideoCards() }.getOrDefault(emptyList())
        }
        return playlistWatchLaterVideos.take(FEED_LIMIT)
    }

    fun addToWatchLater(videoId: String) {
        innerTubeClient.editPlaylist(
            playlistId = "WL",
            actions = listOf(
                mapOf(
                    "action" to "ACTION_ADD_VIDEO",
                    "addedVideoId" to videoId,
                ),
            ),
        )
    }

    fun removeFromWatchLater(videoId: String) {
        innerTubeClient.editPlaylist(
            playlistId = "WL",
            actions = listOf(
                mapOf(
                    "action" to "ACTION_REMOVE_VIDEO_BY_VIDEO_ID",
                    "removedVideoId" to videoId,
                ),
            ),
        )
    }

    companion object {
        private const val FEED_LIMIT = 20
        private const val WATCH_LATER_LIBRARY_BROWSE_ID = "FEmy_youtube"
        private const val WATCH_LATER_LIBRARY_PARAMS = "cAc%3D"
        private const val WATCH_LATER_PLAYLIST_BROWSE_ID = "VLWL"
    }
}

data class YouTubeVideoCard(
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val durationLabel: String?,
    val metadataLabel: String?,
)

internal fun JsonNode.extractVideoCards(): List<YouTubeVideoCard> {
    val cards = linkedMapOf<String, YouTubeVideoCard>()
    collectVideoCards(cards)
    return cards.values.toList()
}

private fun JsonNode.collectVideoCards(cards: MutableMap<String, YouTubeVideoCard>) {
    if (isObject) {
        path("tileRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toTileVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("playlistVideoRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toStandardVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("videoRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toStandardVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("compactVideoRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toStandardVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("gridVideoRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toStandardVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("reelItemRenderer").takeIf { !it.isMissingNode && !it.isNull }?.toStandardVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }
        path("lockupViewModel").takeIf { !it.isMissingNode && !it.isNull }?.toLockupVideoCard()?.let { cards.putIfAbsent(it.videoId, it) }

        fields().forEachRemaining { (_, child) -> child.collectVideoCards(cards) }
    }

    if (isArray) {
        for (child in this) {
            child.collectVideoCards(cards)
        }
    }
}

private fun JsonNode.toTileVideoCard(): YouTubeVideoCard? {
    val videoId = path("onSelectCommand").path("watchEndpoint").path("videoId").asText(
        path("contentId").asText(""),
    ).takeIf { it.isNotBlank() } ?: return null

    val metadata = path("metadata").path("tileMetadataRenderer")
    val title = metadata.path("title").displayText() ?: return null
    val channelName = metadata.path("lines").getOrNull(0)
        ?.path("lineRenderer")
        ?.path("items")
        ?.getOrNull(0)
        ?.path("lineItemRenderer")
        ?.path("text")
        ?.displayText()
        ?: "Unknown channel"

    val metadataLabel = metadata.path("lines").getOrNull(1)
        ?.path("lineRenderer")
        ?.path("items")
        ?.joinTexts()

    val header = path("header").path("tileHeaderRenderer")
    return YouTubeVideoCard(
        videoId = videoId,
        title = title,
        channelName = channelName,
        thumbnailUrl = header.path("thumbnail").bestThumbnailUrl(),
        durationLabel = header.path("thumbnailOverlays").getOrNull(0)
            ?.path("thumbnailOverlayTimeStatusRenderer")
            ?.path("text")
            ?.displayText(),
        metadataLabel = metadataLabel,
    )
}

private fun JsonNode.toStandardVideoCard(): YouTubeVideoCard? {
    val videoId = path("videoId").asText("")
        .ifBlank { path("navigationEndpoint").path("watchEndpoint").path("videoId").asText("") }
        .ifBlank { path("onTap").path("innertubeCommand").path("watchEndpoint").path("videoId").asText("") }
        .takeIf { it.isNotBlank() }
        ?: return null

    val title = path("title").displayText()
        ?: path("headline").displayText()
        ?: path("accessibility").path("accessibilityData").path("label").asText(null)
        ?: return null

    return YouTubeVideoCard(
        videoId = videoId,
        title = title,
        channelName = path("shortBylineText").displayText()
            ?: path("longBylineText").displayText()
            ?: path("ownerText").displayText()
            ?: path("shortByline").path("runs").joinTexts()
            ?: "Unknown channel",
        thumbnailUrl = path("thumbnail").bestThumbnailUrl(),
        durationLabel = path("lengthText").path("simpleText").asText(null)
            ?: path("lengthText").path("accessibility").path("accessibilityData").path("label").asText(null),
        metadataLabel = path("metadataLine").path("metadataLineRenderer").path("text").displayText()
            ?: path("publishedTimeText").displayText()
            ?: path("viewCountText").displayText()
            ?: path("title").path("accessibility").path("accessibilityData").path("label").asText(null),
    )
}

private fun JsonNode.toLockupVideoCard(): YouTubeVideoCard? {
    val watchEndpoint = path("rendererContext")
        .path("commandContext")
        .path("onTap")
        .path("innertubeCommand")
        .path("watchEndpoint")
    val videoId = watchEndpoint.path("videoId").asText("").takeIf { it.isNotBlank() } ?: return null
    val metadata = path("metadata").path("lockupMetadataViewModel")
    val title = metadata.path("title").displayText() ?: return null
    val metadataParts = metadata.path("metadata")
        .path("contentMetadataViewModel")
        .path("metadataRows")
        .metadataPartTexts()

    return YouTubeVideoCard(
        videoId = videoId,
        title = title,
        channelName = metadataParts.firstOrNull() ?: "Unknown channel",
        thumbnailUrl = path("contentImage").path("thumbnailViewModel").path("image").bestThumbnailUrl()
            .ifBlank {
                path("contentImage")
                    .path("collectionThumbnailViewModel")
                    .path("primaryThumbnail")
                    .path("thumbnailViewModel")
                    .path("image")
                    .bestThumbnailUrl()
            },
        durationLabel = path("contentImage")
            .path("thumbnailViewModel")
            .path("overlays")
            .firstThumbnailBadgeText(),
        metadataLabel = metadataParts.drop(1).joinToString(" • ").ifBlank { null },
    )
}

private fun JsonNode.bestThumbnailUrl(): String =
    (path("thumbnails").lastOrNull() ?: path("sources").lastOrNull())
        ?.path("url")
        ?.asText("")
        ?.let { if (it.startsWith("//")) "https:$it" else it }
        ?: ""

private fun JsonNode.displayText(): String? =
    path("simpleText").asText(null)
        ?: path("runs").joinTexts()?.takeIf { it.isNotBlank() }
        ?: path("content").asText(null)
        ?: asText(null)

private fun JsonNode.joinTexts(): String? {
    if (!isArray) {
        return null
    }

    val text = mapNotNull { item ->
        item.path("text").asText(null)
            ?: item.path("lineItemRenderer").path("text").displayText()
    }.joinToString(" ").trim()

    return text.ifBlank { null }
}

private fun JsonNode.metadataPartTexts(): List<String> {
    if (!isArray) {
        return emptyList()
    }

    return flatMap { row ->
        row.path("metadataParts").mapNotNull { part -> part.path("text").displayText() }
    }
}

private fun JsonNode.firstThumbnailBadgeText(): String? {
    if (!isArray) {
        return null
    }

    return firstNotNullOfOrNull { overlay ->
        overlay.path("thumbnailOverlayBadgeViewModel")
            .path("thumbnailBadges")
            .firstOrNull()
            ?.path("thumbnailBadgeViewModel")
            ?.path("text")
            ?.asText(null)
    }
}

private fun JsonNode.getOrNull(index: Int): JsonNode? = if (isArray && size() > index) get(index) else null

private fun JsonNode.lastOrNull(): JsonNode? = if (isArray && size() > 0) get(size() - 1) else null
