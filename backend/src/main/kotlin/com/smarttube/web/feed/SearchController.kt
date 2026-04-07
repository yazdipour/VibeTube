package com.smarttube.web.feed

import com.smarttube.web.stub.liveMetadata
import com.smarttube.web.youtube.YouTubeFeedService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/search")
class SearchController(
    private val feedService: YouTubeFeedService,
) {
    @GetMapping
    fun search(@RequestParam q: String): FeedVideoListResponse {
        val items = feedService.searchVideos(q).map { it.toDto() }
        return FeedVideoListResponse(
            items = items,
            total = items.size,
            stub = liveMetadata("Search results", "youtubei-tv-search"),
        )
    }
}
