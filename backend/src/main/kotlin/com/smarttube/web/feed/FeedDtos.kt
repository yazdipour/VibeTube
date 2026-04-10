package com.smarttube.web.feed

import com.smarttube.web.stub.StubMetadata

data class FeedVideoDto(
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val durationLabel: String?,
    val metadataLabel: String?,
    val percentWatched: Int?,
)

data class FeedVideoListResponse(
    val items: List<FeedVideoDto>,
    val total: Int,
    val stub: StubMetadata,
)

data class WatchLaterRequest(
    val videoId: String,
)

data class WatchLaterMutationResponse(
    val saved: Boolean,
)
