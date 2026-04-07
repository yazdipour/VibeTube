package com.smarttube.web.youtube

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/videos")
class VideoSegmentsController(
    private val sponsorBlockService: YouTubeSponsorBlockService,
) {
    @GetMapping("/{videoId}/segments")
    fun getSegments(@PathVariable videoId: String): ResponseEntity<SegmentsResponse> {
        val segments = sponsorBlockService.getSkipSegments(videoId)
        return ResponseEntity.ok(SegmentsResponse(segments = segments))
    }
}

data class SegmentsResponse(
    val segments: List<SkipSegment>,
)
