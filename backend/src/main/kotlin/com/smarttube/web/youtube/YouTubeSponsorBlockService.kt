package com.smarttube.web.youtube

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class YouTubeSponsorBlockService(
    private val restClient: RestClient,
    @Value("\${sponsorblock.api.url:https://sponsor.ajay.app}") private val apiBaseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(YouTubeSponsorBlockService::class.java)
    
    fun getSkipSegments(videoId: String): List<SkipSegment> {
        return try {
            logger.info("Fetching sponsor segments for video: $videoId")
            val url = "$apiBaseUrl/api/skipSegments?videoID=$videoId"
            logger.info("Requesting: $url")
            
            val response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Array<SponsorSegmentResponse>::class.java) ?: emptyArray()
            
            logger.info("Received ${response.size} sponsor segments from API")
            
            val segments = response.map { segment ->
                logger.debug("Processing segment: ${segment.segment[0]}-${segment.segment[1]} (${segment.category})")
                SkipSegment(
                    startTime = segment.segment[0],
                    endTime = segment.segment[1],
                    category = mapCategory(segment.category),
                )
            }
            
            logger.info("Returning ${segments.size} processed segments")
            segments
        } catch (e: Exception) {
            logger.error("Error fetching sponsor segments for $videoId: ${e.message}", e)
            emptyList()
        }
    }

    private fun mapCategory(segmentCategory: String): SegmentCategory {
        return when (segmentCategory) {
            "sponsor" -> SegmentCategory.SPONSOR
            "selfpromo" -> SegmentCategory.SELFPROMO
            "intro" -> SegmentCategory.INTRO
            "outro" -> SegmentCategory.OUTRO
            "music_offtopic" -> SegmentCategory.MUSIC_OFFTOPIC
            else -> SegmentCategory.SPONSOR
        }
    }
}

data class SkipSegment(
    val startTime: Double,
    val endTime: Double,
    val category: SegmentCategory,
)

enum class SegmentCategory {
    SPONSOR,
    SELFPROMO,
    INTRO,
    OUTRO,
    MUSIC_OFFTOPIC,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SponsorSegmentResponse(
    @JsonProperty("segment")
    val segment: List<Double>,
    @JsonProperty("category")
    val category: String,
)
