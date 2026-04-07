package com.smarttube.web.feed

import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.youtube.YouTubeFeedService
import com.smarttube.web.youtube.YouTubeVideoCard
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/home")
class HomeController(
    private val feedService: YouTubeFeedService,
) {
    @GetMapping
    fun getHome(): FeedVideoListResponse {
        val items = feedService.listHomeVideos().map { it.toDto() }
        return FeedVideoListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Home feed", "youtubei-tv-subscriptions"),
        )
    }
}

internal fun YouTubeVideoCard.toDto() = FeedVideoDto(
    videoId = videoId,
    title = title,
    channelName = channelName,
    thumbnailUrl = thumbnailUrl,
    durationLabel = durationLabel,
    metadataLabel = metadataLabel,
)
