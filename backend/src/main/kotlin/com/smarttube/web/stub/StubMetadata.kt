package com.smarttube.web.stub

data class StubMetadata(
    val notImplemented: Boolean,
    val implementationStatus: String,
    val source: String,
    val message: String,
)

fun stubMetadata(feature: String): StubMetadata = StubMetadata(
    notImplemented = true,
    implementationStatus = "stub",
    source = "in-memory mock data",
    message = "$feature is not implemented yet. This endpoint returns placeholder data only.",
)

fun liveMetadata(feature: String, source: String): StubMetadata = StubMetadata(
    notImplemented = false,
    implementationStatus = "live",
    source = source,
    message = "$feature is backed by live YouTube data.",
)
