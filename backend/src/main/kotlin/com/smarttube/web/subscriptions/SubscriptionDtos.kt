package com.smarttube.web.subscriptions

import com.smarttube.web.stub.StubMetadata

data class SubscriptionDto(
    val channelId: String,
    val channelName: String,
    val thumbnailUrl: String,
    val subscriberCountLabel: String,
    val notificationsEnabled: Boolean,
)

data class SubscriptionListResponse(
    val items: List<SubscriptionDto>,
    val total: Int,
    val stub: StubMetadata,
)

data class SubscriptionDetailsResponse(
    val item: SubscriptionDto,
    val stub: StubMetadata,
)

data class SubscriptionActionResponse(
    val subscribed: Boolean,
)
