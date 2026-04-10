package com.smarttube.web.youtube

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class YouTubeInnerTubePlaybackProviderTest {
    @Test
    fun `parse hls formats keeps highest bitrate per resolution`() {
        val formats = parseHlsFormats(
            manifestUrl = "https://example.com/master.m3u8",
            manifestText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                low/720.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                high/720.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
                1080.m3u8
            """.trimIndent(),
        )

        assertThat(formats.map { it.qualityLabel }).containsExactly("1080p", "720p")
        assertThat(formats.first().url).isEqualTo("https://example.com/1080.m3u8")
        assertThat(formats.last().url).isEqualTo("https://example.com/high/720.m3u8")
    }

    @Test
    fun `extract player metadata resolves player url and signature timestamp`() {
        val metadata = extractPlayerMetadata(
            """
                <html>
                <script>
                var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"}};
                </script>
                <script>
                "PLAYER_JS_URL":"\/s\/player\/abcd1234\/player_ias.vflset\/en_US\/base.js",
                "signatureTimestamp":20522
                </script>
                </html>
            """.trimIndent(),
        )

        assertThat(metadata?.playerUrl)
            .isEqualTo("https://www.youtube.com/s/player/abcd1234/player_ias.vflset/en_US/base.js")
        assertThat(metadata?.signatureTimestamp).isEqualTo("20522")
    }

    @Test
    fun `extract signature timestamp from player js`() {
        assertThat(extractSignatureTimestamp("""var x={signatureTimestamp:20123};"""))
            .isEqualTo("20123")
    }

    @Test
    fun `extract player url from iframe api`() {
        val url = extractPlayerUrlFromIframeApi(
            """var scriptUrl='https://www.youtube.com/s/player/abcd1234/player_ias.vflset/en_US/base.js';""",
        )

        assertThat(url).isEqualTo("https://www.youtube.com/s/player/abcd1234/player_ias.vflset/en_US/base.js")
    }

    @Test
    fun `parse cipher payload extracts url and s`() {
        val payload = parseCipherPayload("url=https%3A%2F%2Fexample.com%2Fvideoplayback%3Fn%3Dabc&s=secret")

        assertThat(payload?.url).isEqualTo("https://example.com/videoplayback?n=abc")
        assertThat(payload?.s).isEqualTo("secret")
    }

    @Test
    fun `player script service deciphers signature and transforms n`() {
        val service = YouTubePlayerScriptService(
            """
                "use strict";
                var AA={"rv":function(a){return a.reverse()}};
                x&&(x=Sig(decodeURIComponent(x)));
                function Sig(a){a=a.split("");AA.rv(a);return a.join("")}
                n=NF(n);
                function NF(a){return a.split("").reverse().join("")}
            """.trimIndent(),
        )

        val resolved = service.resolveFormat(
            StreamingFormatCandidate(
                url = null,
                cipher = null,
                signatureCipher = "url=https%3A%2F%2Fexample.com%2Fvideoplayback%3Fn%3Dabc&sp=sig&s=secret",
                mimeType = "video/mp4",
                itag = 22,
                qualityLabel = "720p",
                bitrate = 1_000L,
                hasAudio = true,
                hasVideo = true,
                width = null,
                height = null,
                fps = null,
                contentLength = null,
                approxDurationMs = null,
                initRange = null,
                indexRange = null,
                audioSampleRate = null,
                audioTrackDisplayName = null,
                audioTrackId = null,
                audioIsDefault = false,
                isAutoDubbed = false,
                isDrc = false,
                xtags = null,
            ),
        )

        assertThat(resolved?.url).isEqualTo("https://example.com/videoplayback?n=cba&sig=terces")
    }
}
