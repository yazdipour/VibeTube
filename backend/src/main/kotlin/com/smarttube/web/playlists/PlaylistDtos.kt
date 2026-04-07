package com.smarttube.web.playlists

import com.smarttube.web.stub.StubMetadata

data class PlaylistItemDto(
    val videoId: String,
    val title: String,
    val durationLabel: String,
    val channelName: String = "",
    val thumbnailUrl: String = "",
    val metadataLabel: String = "",
)

data class PlaylistDto(
    val playlistId: String,
    val title: String,
    val description: String,
    val visibility: String,
    val itemCount: Int,
    val items: List<PlaylistItemDto>,
)

data class CreatePlaylistRequest(
    val title: String,
    val description: String = "",
    val visibility: String = "private",
)

data class PlaylistListResponse(
    val items: List<PlaylistDto>,
    val total: Int,
    val stub: StubMetadata,
)

data class PlaylistDetailsResponse(
    val item: PlaylistDto,
    val stub: StubMetadata,
)

data class PlaylistCreateResponse(
    val item: PlaylistDto,
    val created: Boolean,
    val stub: StubMetadata,
)
