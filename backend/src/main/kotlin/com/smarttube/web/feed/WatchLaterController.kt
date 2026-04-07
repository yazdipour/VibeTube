package com.smarttube.web.feed

import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.youtube.YouTubeFeedService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/watch-later")
class WatchLaterController(
    private val feedService: YouTubeFeedService,
) {
    @GetMapping
    fun listWatchLater(): FeedVideoListResponse {
        val items = feedService.listWatchLaterVideos().map { it.toDto() }
        return FeedVideoListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Watch Later", "youtubei-tv-playlist"),
        )
    }

    @PostMapping
    fun addToWatchLater(@RequestBody request: WatchLaterRequest): WatchLaterMutationResponse {
        feedService.addToWatchLater(request.videoId)
        return WatchLaterMutationResponse(saved = true)
    }

    @DeleteMapping("/{videoId}")
    fun removeFromWatchLater(@PathVariable videoId: String): WatchLaterMutationResponse {
        feedService.removeFromWatchLater(videoId)
        return WatchLaterMutationResponse(saved = false)
    }
}
