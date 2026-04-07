package com.smarttube.web.subscriptions

import com.smarttube.web.feed.FeedVideoListResponse
import com.smarttube.web.feed.toDto
import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.youtube.YouTubeFeedService
import com.smarttube.web.youtube.YouTubeSubscriptionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionsController(
    private val youTubeDataService: com.smarttube.web.youtube.YouTubeDataService,
    private val subscriptionService: YouTubeSubscriptionService,
    private val feedService: YouTubeFeedService,
) {
    @GetMapping
    fun listSubscriptions(): SubscriptionListResponse = youTubeDataService.listSubscriptions()

    @GetMapping("/feed")
    fun listSubscriptionFeed(): FeedVideoListResponse {
        val items = feedService.listSubscriptionFeedVideos().map { it.toDto() }
        return FeedVideoListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Subscriptions feed", "youtubei-tv-subscriptions"),
        )
    }

    @GetMapping("/{channelId}")
    fun getSubscription(@PathVariable channelId: String): SubscriptionDetailsResponse =
        youTubeDataService.getSubscription(channelId)

    @PostMapping("/{channelId}/subscribe")
    fun subscribe(@PathVariable channelId: String): SubscriptionActionResponse {
        subscriptionService.subscribe(channelId)
        return SubscriptionActionResponse(subscribed = true)
    }

    @PostMapping("/{channelId}/unsubscribe")
    fun unsubscribe(@PathVariable channelId: String): SubscriptionActionResponse {
        val subscriptionId = subscriptionService.findSubscriptionId(channelId)
            ?: throw IllegalStateException("Not subscribed to channel $channelId")
        subscriptionService.unsubscribe(subscriptionId)
        return SubscriptionActionResponse(subscribed = false)
    }
}
