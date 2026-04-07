package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class YouTubeFeedServiceTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `extracts playlist video renderer cards`() {
        val json = objectMapper.readTree(
            """
            {
              "contents": [
                {
                  "playlistVideoRenderer": {
                    "videoId": "playlist-video-id",
                    "title": { "runs": [{ "text": "Playlist video" }] },
                    "shortBylineText": { "runs": [{ "text": "Playlist channel" }] },
                    "thumbnail": { "thumbnails": [{ "url": "https://img/low.jpg" }, { "url": "https://img/high.jpg" }] },
                    "lengthText": { "simpleText": "4:20" },
                    "publishedTimeText": { "simpleText": "Yesterday" }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertThat(json.extractVideoCards()).containsExactly(
            YouTubeVideoCard(
                videoId = "playlist-video-id",
                title = "Playlist video",
                channelName = "Playlist channel",
                thumbnailUrl = "https://img/high.jpg",
                durationLabel = "4:20",
                metadataLabel = "Yesterday",
            ),
        )
    }

    @Test
    fun `extracts tile renderer cards`() {
        val json = objectMapper.readTree(
            """
            {
              "tileRenderer": {
                "contentId": "tile-video-id",
                "metadata": {
                  "tileMetadataRenderer": {
                    "title": { "simpleText": "Tile video" },
                    "lines": [
                      {
                        "lineRenderer": {
                          "items": [
                            { "lineItemRenderer": { "text": { "simpleText": "Tile channel" } } }
                          ]
                        }
                      },
                      {
                        "lineRenderer": {
                          "items": [
                            { "lineItemRenderer": { "text": { "simpleText": "1K views" } } },
                            { "lineItemRenderer": { "text": { "simpleText": "2 days ago" } } }
                          ]
                        }
                      }
                    ]
                  }
                },
                "header": {
                  "tileHeaderRenderer": {
                    "thumbnail": { "thumbnails": [{ "url": "https://img/tile.jpg" }] },
                    "thumbnailOverlays": [
                      {
                        "thumbnailOverlayTimeStatusRenderer": {
                          "text": { "simpleText": "8:10" }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertThat(json.extractVideoCards()).containsExactly(
            YouTubeVideoCard(
                videoId = "tile-video-id",
                title = "Tile video",
                channelName = "Tile channel",
                thumbnailUrl = "https://img/tile.jpg",
                durationLabel = "8:10",
                metadataLabel = "1K views 2 days ago",
            ),
        )
    }

    @Test
    fun `extracts lockup view model cards`() {
        val json = objectMapper.readTree(
            """
            {
              "lockupViewModel": {
                "contentImage": {
                  "thumbnailViewModel": {
                    "image": {
                      "sources": [
                        { "url": "//img.youtube.com/low.jpg" },
                        { "url": "//img.youtube.com/high.jpg" }
                      ]
                    },
                    "overlays": [
                      {
                        "thumbnailOverlayBadgeViewModel": {
                          "thumbnailBadges": [
                            { "thumbnailBadgeViewModel": { "text": "12:34" } }
                          ]
                        }
                      }
                    ]
                  }
                },
                "metadata": {
                  "lockupMetadataViewModel": {
                    "title": { "content": "Lockup video" },
                    "metadata": {
                      "contentMetadataViewModel": {
                        "metadataRows": [
                          {
                            "metadataParts": [
                              { "text": { "content": "Lockup channel" } }
                            ]
                          },
                          {
                            "metadataParts": [
                              { "text": { "content": "42K views" } },
                              { "text": { "content": "3 weeks ago" } }
                            ]
                          }
                        ]
                      }
                    }
                  }
                },
                "rendererContext": {
                  "commandContext": {
                    "onTap": {
                      "innertubeCommand": {
                        "watchEndpoint": { "videoId": "lockup-video-id" }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertThat(json.extractVideoCards()).containsExactly(
            YouTubeVideoCard(
                videoId = "lockup-video-id",
                title = "Lockup video",
                channelName = "Lockup channel",
                thumbnailUrl = "https://img.youtube.com/high.jpg",
                durationLabel = "12:34",
                metadataLabel = "42K views • 3 weeks ago",
            ),
        )
    }

    @Test
    fun `ignores non-video playlist nodes`() {
        val json = objectMapper.readTree(
            """
            {
              "gridPlaylistRenderer": {
                "playlistId": "not-a-video",
                "title": { "simpleText": "A playlist" }
              }
            }
            """.trimIndent(),
        )

        assertThat(json.extractVideoCards()).isEmpty()
    }

    @Test
    fun `watch later tries library query before playlist browse fallback`() {
        val innerTubeApi = FakeInnerTubeApi(
            objectMapper.readTree("""{"contents": []}"""),
            objectMapper.readTree(
                """
                {
                  "contents": [
                    {
                      "playlistVideoRenderer": {
                        "videoId": "fallback-video-id",
                        "title": { "simpleText": "Fallback video" },
                        "shortBylineText": { "simpleText": "Fallback channel" },
                        "thumbnail": { "thumbnails": [{ "url": "https://img/fallback.jpg" }] }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val service = YouTubeFeedService(
            innerTubeClient = innerTubeApi,
            dataService = mock(YouTubeDataService::class.java),
        )

        val videos = service.listWatchLaterVideos()

        assertThat(innerTubeApi.calls).containsExactly(
            BrowseCall("FEmy_youtube", "cAc%3D"),
            BrowseCall("VLWL", null),
        )
        assertThat(videos.map { it.videoId }).containsExactly("fallback-video-id")
    }

    private data class BrowseCall(val browseId: String, val params: String?)

    private class FakeInnerTubeApi(
        private vararg val browseResponses: JsonNode,
    ) : YouTubeInnerTubeApi {
        val calls = mutableListOf<BrowseCall>()

        override fun browse(browseId: String, params: String?): JsonNode {
            calls += BrowseCall(browseId, params)
            return browseResponses.getOrElse(calls.lastIndex) {
                error("No fake response configured for browse call ${calls.size}")
            }
        }

        override fun editPlaylist(playlistId: String, actions: List<Map<String, String>>) = Unit
    }
}
