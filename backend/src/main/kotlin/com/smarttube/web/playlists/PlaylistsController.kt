package com.smarttube.web.playlists

import com.smarttube.web.youtube.YouTubeDataService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/playlists")
class PlaylistsController(
    private val youTubeDataService: YouTubeDataService,
) {
    @GetMapping
    fun listPlaylists(): PlaylistListResponse = youTubeDataService.listPlaylists()

    @GetMapping("/{playlistId}")
    fun getPlaylist(@PathVariable playlistId: String): PlaylistDetailsResponse =
        youTubeDataService.getPlaylist(playlistId)

    @PostMapping
    fun createPlaylist(@RequestBody request: CreatePlaylistRequest): PlaylistCreateResponse =
        youTubeDataService.createPlaylist(request)
}
