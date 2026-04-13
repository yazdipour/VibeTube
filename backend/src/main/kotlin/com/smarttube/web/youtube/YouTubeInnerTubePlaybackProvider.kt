package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service("innerTubePlaybackProvider")
class YouTubeInnerTubePlaybackProvider(
    private val httpClient: YouTubeHttpClient,
    private val properties: YouTubeProperties,
) : YouTubePlaybackProvider {
    private val playerMetadataCache = ConcurrentHashMap<String, CachedPlayerMetadata>()
    private val playerScriptCache = ConcurrentHashMap<String, CachedPlayerScript>()
    private val globalPlayerMetadataCache = ConcurrentHashMap<String, CachedPlayerMetadata>()

    override fun getVideoStreamUrl(videoId: String, formatSelector: String): VideoStreamInfo? {
        val players = fetchPlayers(videoId)
        val player = selectBestPlayer(players) ?: return null
        val adaptiveManifest = buildAdaptiveDashManifest(videoId, player)
        val formats = resolveFormats(player, adaptiveManifest)
        val selectedFormat = resolveRequestedFormat(formats, formatSelector)
        val hasHlsFormats = formats.any { it.mimeType.contains("mpegurl", ignoreCase = true) }
        val selectedDashItag = selectedFormat
            ?.takeIf { it.mimeType == "application/dash+xml" }
            ?.itag
        val useHls = player.hlsManifestUrl != null && hasHlsFormats
        val useDash = adaptiveManifest != null &&
            !useHls &&
            (formatSelector == DEFAULT_FORMAT_SELECTOR || selectedFormat != null)
        val streamUrl = when {
            useHls -> player.hlsManifestUrl
            useDash && selectedDashItag != null -> "/api/videos/$videoId/manifest.mpd?itag=$selectedDashItag"
            useDash -> "/api/videos/$videoId/manifest.mpd"
            selectedFormat != null -> selectedFormat.url
            else -> player.hlsManifestUrl
        } ?: return null

        return VideoStreamInfo(
            videoId = videoId,
            title = player.title ?: "Unknown",
            author = player.author ?: "Unknown",
            channelId = player.channelId,
            lengthSeconds = player.lengthSeconds ?: 0,
            streamUrl = streamUrl,
            hlsManifestUrl = player.hlsManifestUrl,
            dashManifestUrl = adaptiveManifest?.let { "/api/videos/$videoId/manifest.mpd" },
            formats = formats,
            subtitles = player.subtitles,
        )
    }

    override fun getAvailableFormats(videoId: String): VideoFormatsInfo? {
        val player = selectBestPlayer(fetchPlayers(videoId)) ?: return null
        val formats = resolveFormats(player, buildAdaptiveDashManifest(videoId, player))
        if (formats.isEmpty()) {
            return null
        }

        return VideoFormatsInfo(
            videoId = videoId,
            title = player.title ?: "Unknown",
            author = player.author ?: "Unknown",
            lengthSeconds = player.lengthSeconds ?: 0,
            formats = formats,
        )
    }

    override fun getDashManifest(videoId: String, videoItag: Int?): String? {
        val player = selectBestPlayer(fetchPlayers(videoId)) ?: return null
        return buildAdaptiveDashManifest(videoId, player, videoItag)?.manifest
    }

    override fun getAdaptiveStreamUrl(videoId: String, itag: Int): String? {
        return getAdaptiveStreamUrls(videoId, itag).firstOrNull()
    }

    override fun getAdaptiveStreamUrls(videoId: String, itag: Int): List<String> =
        fetchPlayers(videoId)
            .asSequence()
            .flatMap { player ->
                resolveAdaptiveCandidates(player)
                    .asSequence()
                    .filter { it.itag == itag }
                    .map { it.url }
            }
            .distinct()
            .toList()

    override fun getSubtitleContent(captionUrl: String): String =
        httpClient.getText(
            ensureVttCaptionUrl(captionUrl),
            headers = mapOf(
                "User-Agent" to IOS_USER_AGENT,
                "Accept" to "text/vtt,text/plain,*/*",
            ),
        )

    private fun fetchPlayers(videoId: String): List<ParsedInnerTubePlayer> {
        val apiKey = properties.innertubeApiKey?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "YOUTUBE_INNERTUBE_API_KEY is not set.")
        val url = httpClient.buildUrl(
            "https://www.youtube.com/youtubei/v1/player",
            mapOf(
                "key" to apiKey,
                "prettyPrint" to "false",
            ),
        )

        val playerMetadata = loadPlayerMetadata(videoId)
        var failureMessage: String? = null
        val playablePlayers = mutableListOf<ParsedInnerTubePlayer>()
        for (client in clients) {
            val (status, json) = httpClient.postJsonWithStatus(
                url,
                client.buildBody(videoId, playerMetadata),
                headers = client.headers(apiKey),
            )
            if (status !in 200..299) {
                failureMessage = "Player request failed for ${client.clientName} with status $status"
                continue
            }

            val parsed = json.toParsedInnerTubePlayer(playerMetadata)
            if (parsed.isPlayable()) {
                playablePlayers += parsed
                continue
            }

            failureMessage = parsed.failureMessage(client.clientName)
        }

        if (playablePlayers.isNotEmpty()) {
            return playablePlayers
        }

        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            failureMessage ?: "Video extraction failed: No playable stream available",
        )
    }

    private fun resolveFormats(player: ParsedInnerTubePlayer, adaptiveManifest: AdaptiveDashManifest?): List<VideoFormat> {
        val hlsManifestUrl = player.hlsManifestUrl
        if (!hlsManifestUrl.isNullOrBlank()) {
            val manifestText = httpClient.getText(
                hlsManifestUrl,
                headers = mapOf("User-Agent" to IOS_USER_AGENT),
            )
            val hlsFormats = parseHlsFormats(hlsManifestUrl, manifestText)
            if (hlsFormats.isNotEmpty()) {
                return hlsFormats
            }
        }

        if (adaptiveManifest != null) {
            return adaptiveManifest.formats
        }

        val directFormats = player.formats
            .mapNotNull { candidate ->
                val resolved = player.playerMetadata?.playerUrl
                    ?.let(::loadPlayerScript)
                    ?.jsCode
                    ?.let(::YouTubePlayerScriptService)
                    ?.resolveFormat(candidate)
                    ?: candidate.url?.let {
                    ResolvedFormatCandidate(
                        url = it,
                        mimeType = candidate.mimeType,
                        itag = candidate.itag,
                        qualityLabel = candidate.qualityLabel,
                        bitrate = candidate.bitrate,
                        hasAudio = candidate.hasAudio,
                        hasVideo = candidate.hasVideo,
                        width = candidate.width,
                        height = candidate.height,
                        fps = candidate.fps,
                        contentLength = candidate.contentLength,
                        approxDurationMs = candidate.approxDurationMs,
                        initRange = candidate.initRange,
                        indexRange = candidate.indexRange,
                        audioSampleRate = candidate.audioSampleRate,
                        audioTrackDisplayName = candidate.audioTrackDisplayName,
                        audioTrackId = candidate.audioTrackId,
                        audioIsDefault = candidate.audioIsDefault,
                        isAutoDubbed = candidate.isAutoDubbed,
                        isDrc = candidate.isDrc,
                        xtags = candidate.xtags,
                    )
                    }

                resolved?.toVideoFormat()
            }
            .filter { format -> format.mimeType.contains("mp4") }
            .sortedWith(compareByDescending<VideoFormat> { extractHeight(it.qualityLabel) }.thenByDescending { it.bitrate })

        val progressiveFormats = directFormats.filter { format ->
            val matchingCandidate = player.formats.firstOrNull { it.itag == format.itag }
            matchingCandidate?.hasAudio == true && matchingCandidate.hasVideo
        }

        if (progressiveFormats.isNotEmpty()) {
            return progressiveFormats
                .distinctBy { extractHeight(it.qualityLabel).takeIf { height -> height > 0 } ?: it.formatId }
        }

        return emptyList()
    }

    private fun loadPlayerMetadata(videoId: String): PlayerMetadata? {
        val cached = playerMetadataCache[videoId]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.metadata
        }

        val watchHtml = runCatching {
            httpClient.getText(
                "https://www.youtube.com/watch?v=$videoId&hl=en&bpctr=9999999999&has_verified=1",
                headers = mapOf(
                    "User-Agent" to WEB_USER_AGENT,
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            )
        }.getOrNull() ?: return cached?.metadata

        val extracted = extractPlayerMetadata(watchHtml) ?: loadIframePlayerMetadata() ?: return cached?.metadata
        val metadata = extracted.playerUrl?.let { playerUrl ->
            val playerScript = loadPlayerScript(playerUrl)
            extracted.copy(
                signatureTimestamp = extracted.signatureTimestamp ?: playerScript?.signatureTimestamp,
            )
        } ?: extracted
        playerMetadataCache[videoId] = CachedPlayerMetadata(
            metadata = metadata,
            expiresAt = Instant.now().plus(METADATA_TTL),
        )
        return metadata
    }

    private fun loadIframePlayerMetadata(): PlayerMetadata? {
        val cacheKey = "iframe_api"
        val cached = globalPlayerMetadataCache[cacheKey]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.metadata
        }

        val iframeJs = runCatching {
            httpClient.getText(
                "https://www.youtube.com/iframe_api",
                headers = mapOf("User-Agent" to WEB_USER_AGENT),
            )
        }.getOrNull() ?: return cached?.metadata

        val playerUrl = extractPlayerUrlFromIframeApi(iframeJs) ?: return cached?.metadata
        val playerScript = loadPlayerScript(playerUrl)
        val metadata = PlayerMetadata(
            playerUrl = playerUrl,
            signatureTimestamp = playerScript?.signatureTimestamp,
        )
        globalPlayerMetadataCache[cacheKey] = CachedPlayerMetadata(
            metadata = metadata,
            expiresAt = Instant.now().plus(METADATA_TTL),
        )
        return metadata
    }

    private fun loadPlayerScript(playerUrl: String): CachedPlayerScript? {
        val cached = playerScriptCache[playerUrl]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached
        }

        val jsCode = runCatching {
            httpClient.getText(
                playerUrl,
                headers = mapOf("User-Agent" to WEB_USER_AGENT),
            )
        }.getOrNull() ?: return cached

        val script = CachedPlayerScript(
            signatureTimestamp = extractSignatureTimestamp(jsCode),
            jsCode = jsCode,
            expiresAt = Instant.now().plus(METADATA_TTL),
        )
        playerScriptCache[playerUrl] = script
        return script
    }

    private fun resolveRequestedFormat(formats: List<VideoFormat>, formatSelector: String): VideoFormat? {
        if (formatSelector == DEFAULT_FORMAT_SELECTOR) {
            return formats.maxWithOrNull(compareBy<VideoFormat> { extractHeight(it.qualityLabel) }.thenBy { it.bitrate })
        }

        return formats.firstOrNull { it.formatId == formatSelector || it.itag.toString() == formatSelector }
            ?: formats.maxWithOrNull(compareBy<VideoFormat> { extractHeight(it.qualityLabel) }.thenBy { it.bitrate })
    }

    private fun selectBestPlayer(players: List<ParsedInnerTubePlayer>): ParsedInnerTubePlayer? =
        players.maxWithOrNull(
            compareBy<ParsedInnerTubePlayer> { player ->
                when {
                    !player.hlsManifestUrl.isNullOrBlank() -> 4
                    player.formats.any { it.hasAudio && it.hasVideo && it.mimeType.contains("mp4") } -> 3
                    player.formats.any { it.hasAudio && it.hasVideo } -> 2
                    else -> 0
                }
            }.thenBy { player ->
                player.formats.any { it.hasAudio && it.hasVideo && it.mimeType.contains("mp4") }
            },
        )

    private fun buildAdaptiveDashManifest(videoId: String, player: ParsedInnerTubePlayer, selectedVideoItag: Int? = null): AdaptiveDashManifest? {
        val resolvedFormats = resolveAdaptiveCandidates(player)
        val videoFormats = selectAdaptiveVideoFormats(resolvedFormats)
            .let { formats ->
                selectedVideoItag?.let { itag -> formats.filter { it.itag == itag } } ?: formats
            }
        val audioFormat = selectAdaptiveAudioFormat(resolvedFormats)
        if (videoFormats.isEmpty() || audioFormat == null) {
            return null
        }

        val manifest = buildDashManifestXml(videoId, player.lengthSeconds ?: 0, videoFormats, audioFormat)
        val formats = videoFormats.map { format ->
            VideoFormat(
                formatId = "dash-${format.height ?: extractHeight(format.qualityLabel)}",
                itag = format.itag,
                url = "/api/videos/$videoId/manifest.mpd",
                mimeType = "application/dash+xml",
                qualityLabel = format.qualityLabel,
                bitrate = format.bitrate,
            )
        }

        return AdaptiveDashManifest(manifest = manifest, formats = formats)
    }

    private fun resolveAdaptiveCandidates(player: ParsedInnerTubePlayer): List<ResolvedFormatCandidate> =
        player.formats.mapNotNull { candidate ->
            val resolved = player.playerMetadata?.playerUrl
                ?.let(::loadPlayerScript)
                ?.jsCode
                ?.let(::YouTubePlayerScriptService)
                ?.resolveFormat(candidate)
                ?: candidate.url?.let {
                    ResolvedFormatCandidate(
                        url = it,
                        mimeType = candidate.mimeType,
                        itag = candidate.itag,
                        qualityLabel = candidate.qualityLabel,
                        bitrate = candidate.bitrate,
                        hasAudio = candidate.hasAudio,
                        hasVideo = candidate.hasVideo,
                        width = candidate.width,
                        height = candidate.height,
                        fps = candidate.fps,
                        contentLength = candidate.contentLength,
                        approxDurationMs = candidate.approxDurationMs,
                        initRange = candidate.initRange,
                        indexRange = candidate.indexRange,
                        audioSampleRate = candidate.audioSampleRate,
                        audioTrackDisplayName = candidate.audioTrackDisplayName,
                        audioTrackId = candidate.audioTrackId,
                        audioIsDefault = candidate.audioIsDefault,
                        isAutoDubbed = candidate.isAutoDubbed,
                        isDrc = candidate.isDrc,
                        xtags = candidate.xtags,
                    )
                }
            resolved
        }

    private data class ClientProfile(
        val clientName: String,
        val clientNameId: String,
        val clientVersion: String,
        val userAgent: String,
        val fields: Map<String, Any>,
        val referer: String,
        val origin: String = "https://www.youtube.com",
        val extraBody: Map<String, Any> = emptyMap(),
    ) {
        fun buildBody(videoId: String, playerMetadata: PlayerMetadata?): Map<String, Any> = buildMap {
            put(
                "context",
                mapOf(
                    "client" to (fields + mapOf(
                        "clientName" to clientName,
                        "clientVersion" to clientVersion,
                        "userAgent" to userAgent,
                        "hl" to "en",
                        "gl" to "US",
                        "utcOffsetMinutes" to 0,
                    )),
                    "request" to mapOf("useSsl" to true),
                ),
            )
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("cpn", createClientPlaybackNonce())
            playerMetadata?.signatureTimestamp?.let { signatureTimestamp ->
                put(
                    "playbackContext",
                    mapOf(
                        "contentPlaybackContext" to mapOf(
                            "html5Preference" to "HTML5_PREF_WANTS",
                            "signatureTimestamp" to signatureTimestamp,
                        ),
                    ),
                )
            }
            extraBody.forEach { (key, value) -> put(key, value) }
        }

        fun headers(apiKey: String): Map<String, String> = mapOf(
            "User-Agent" to userAgent,
            "X-Goog-Api-Key" to apiKey,
            "X-YouTube-Client-Name" to clientNameId,
            "X-YouTube-Client-Version" to clientVersion,
            "Origin" to origin,
            "Referer" to referer,
            "Accept" to "application/json",
        )
    }

    companion object {
        private const val DEFAULT_FORMAT_SELECTOR = "bestvideo+bestaudio/best"
        private const val IOS_USER_AGENT = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private val METADATA_TTL: Duration = Duration.ofHours(6)

        private val clients = listOf(
            ClientProfile(
                clientName = "IOS",
                clientNameId = "5",
                clientVersion = "20.10.4",
                userAgent = IOS_USER_AGENT,
                referer = "https://www.youtube.com/",
                fields = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            ClientProfile(
                clientName = "ANDROID",
                clientNameId = "3",
                clientVersion = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; Pixel 8 Pro Build/AP1A.240305.019.A1) gzip",
                referer = "https://www.youtube.com/",
                fields = mapOf(
                    "osName" to "Android",
                    "osVersion" to "14",
                    "androidSdkVersion" to "34",
                    "deviceMake" to "Google",
                    "deviceModel" to "Pixel 8 Pro",
                ),
            ),
            ClientProfile(
                clientName = "WEB_EMBEDDED_PLAYER",
                clientNameId = "56",
                clientVersion = "1.20250326.01.00",
                userAgent = WEB_USER_AGENT,
                referer = "https://www.youtube.com/embed/dQw4w9WgXcQ",
                fields = mapOf(
                    "clientScreen" to "EMBED",
                    "platform" to "DESKTOP",
                ),
                extraBody = mapOf(
                    "thirdParty" to mapOf("embedUrl" to "https://www.youtube.com"),
                ),
            ),
            ClientProfile(
                clientName = "TVHTML5",
                clientNameId = "7",
                clientVersion = "7.20260311.12.00",
                userAgent = "Mozilla/5.0 (Linux; Android 12; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                referer = "https://www.youtube.com/tv",
                fields = mapOf(
                    "platform" to "TV",
                    "clientScreen" to "WATCH",
                ),
            ),
        )
    }
}

internal data class PlayerMetadata(
    val playerUrl: String?,
    val signatureTimestamp: String?,
)

private data class CachedPlayerMetadata(
    val metadata: PlayerMetadata,
    val expiresAt: Instant,
)

private data class CachedPlayerScript(
    val signatureTimestamp: String?,
    val jsCode: String,
    val expiresAt: Instant,
)

private data class ParsedInnerTubePlayer(
    val title: String?,
    val author: String?,
    val channelId: String?,
    val lengthSeconds: Int?,
    val playabilityStatus: String?,
    val playabilityReason: String?,
    val playerMetadata: PlayerMetadata?,
    val hlsManifestUrl: String?,
    val formats: List<StreamingFormatCandidate>,
    val subtitles: List<SubtitleTrack>,
) {
    fun isPlayable(): Boolean = playabilityStatus == "OK" && (hlsManifestUrl != null || formats.isNotEmpty())

    fun failureMessage(clientName: String): String {
        val reason = playabilityReason?.takeIf { it.isNotBlank() } ?: playabilityStatus ?: "unknown"
        return "Video extraction failed for $clientName: $reason"
    }
}

private data class AdaptiveDashManifest(
    val manifest: String,
    val formats: List<VideoFormat>,
)

private fun JsonNode.toParsedInnerTubePlayer(playerMetadata: PlayerMetadata? = null): ParsedInnerTubePlayer {
    val streamingData = path("streamingData")
    val subtitleTracks = path("captions")
        .path("playerCaptionsTracklistRenderer")
        .path("captionTracks")
        .mapNotNull { track ->
            val baseUrl = track.path("baseUrl").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val languageCode = track.path("languageCode").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SubtitleTrack(
                language = languageCode,
                label = track.path("name").displayTextValue() ?: languageCode,
                url = ensureVttCaptionUrl(baseUrl),
                automatic = track.path("kind").asText("") == "asr",
                default = false,
            )
        }
    val muxedFormats = (
        streamingData.path("formats").toStreamingFormatCandidates() +
            streamingData.path("adaptiveFormats").toStreamingFormatCandidates()
        )
        .distinctBy { "${it.itag}:${it.qualityLabel}:${it.mimeType}:${it.audioTrackId ?: ""}:${it.xtags ?: ""}:${it.url ?: it.signatureCipher ?: it.cipher ?: ""}" }

    return ParsedInnerTubePlayer(
        title = path("videoDetails").path("title").asText(null),
        author = path("videoDetails").path("author").asText(null),
        channelId = path("videoDetails").path("channelId").asText(null)?.ifBlank { null },
        lengthSeconds = path("videoDetails").path("lengthSeconds").asText("").toIntOrNull(),
        playabilityStatus = path("playabilityStatus").path("status").asText(null),
        playabilityReason = path("playabilityStatus").path("reason").asText(null),
        playerMetadata = playerMetadata,
        hlsManifestUrl = streamingData.path("hlsManifestUrl").asText(null).takeIf { !it.isNullOrBlank() },
        formats = muxedFormats,
        subtitles = subtitleTracks.mapIndexed { index, track -> track.copy(default = index == 0) },
    )
}

private fun JsonNode.displayTextValue(): String? =
    path("simpleText").asText(null)
        ?: path("runs").takeIf { it.isArray }?.mapNotNull { it.path("text").asText(null) }?.joinToString("")?.ifBlank { null }
        ?: path("content").asText(null)

private fun JsonNode.toStreamingFormatCandidates(): List<StreamingFormatCandidate> {
    if (!isArray) {
        return emptyList()
    }

    return mapNotNull { format ->
        val url = format.path("url").asText("").takeIf { it.isNotBlank() }
        val cipher = format.path("cipher").asText("").takeIf { it.isNotBlank() }
        val signatureCipher = format.path("signatureCipher").asText("").takeIf { it.isNotBlank() }
        if (url == null && cipher == null && signatureCipher == null) {
            return@mapNotNull null
        }
        val mimeType = format.path("mimeType").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val itag = format.path("itag").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
        StreamingFormatCandidate(
            url = url,
            cipher = cipher,
            signatureCipher = signatureCipher,
            mimeType = mimeType,
            itag = itag,
            qualityLabel = format.path("qualityLabel").asText(format.path("quality").asText("Format $itag")),
            bitrate = format.path("bitrate").asLong(0L),
            hasAudio = format.path("audioQuality").asText("").isNotBlank() || mimeType.contains("audio"),
            hasVideo = format.path("width").asInt(0) > 0 || mimeType.contains("video"),
            width = format.path("width").asInt(0).takeIf { it > 0 },
            height = format.path("height").asInt(0).takeIf { it > 0 },
            fps = format.path("fps").asInt(0).takeIf { it > 0 },
            contentLength = format.path("contentLength").asText("").toLongOrNull(),
            approxDurationMs = format.path("approxDurationMs").asText("").toLongOrNull(),
            initRange = format.path("initRange").toByteRange(),
            indexRange = format.path("indexRange").toByteRange(),
            audioSampleRate = format.path("audioSampleRate").asText("").toIntOrNull(),
            audioTrackDisplayName = format.path("audioTrack").path("displayName").asText("").ifBlank { null },
            audioTrackId = format.path("audioTrack").path("id").asText("").ifBlank { null },
            audioIsDefault = format.path("audioTrack").path("audioIsDefault").asBoolean(false),
            isAutoDubbed = format.path("audioTrack").path("isAutoDubbed").asBoolean(false),
            isDrc = format.path("isDrc").asBoolean(false),
            xtags = format.path("xtags").asText("").ifBlank { null },
        )
    }
}

private fun JsonNode.toByteRange(): ByteRange? {
    val start = path("start").asText("").toLongOrNull() ?: return null
    val end = path("end").asText("").toLongOrNull() ?: return null
    return ByteRange(start, end)
}

internal fun extractPlayerMetadata(html: String): PlayerMetadata? {
    val playerUrl = listOf(
        Regex("\"PLAYER_JS_URL\":\"([^\"]+)\""),
        Regex("\"jsUrl\":\"([^\"]+)\""),
        Regex("\"playerUrl\":\"([^\"]+)\""),
        Regex("id=\"base-js\" src=\"([^\"]+)\""),
    ).firstNotNullOfOrNull { regex ->
        regex.find(html)?.groupValues?.getOrNull(1)
    }?.replace("\\/", "/")?.let { candidate ->
        when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("/") -> "https://www.youtube.com$candidate"
            candidate.startsWith("http") -> candidate
            else -> null
        }
    }

    val signatureTimestamp = Regex("""signatureTimestamp["':= ]+(\d+)""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)

    return if (playerUrl != null || signatureTimestamp != null) {
        PlayerMetadata(playerUrl = playerUrl, signatureTimestamp = signatureTimestamp)
    } else {
        null
    }
}

internal fun extractPlayerUrlFromIframeApi(jsCode: String): String? {
    val playerId = Regex("""player\\?/([a-zA-Z0-9_-]+)/""")
        .find(jsCode)
        ?.groupValues
        ?.getOrNull(1)
        ?: Regex("""/s/player/([a-zA-Z0-9_-]+)/""")
            .find(jsCode)
            ?.groupValues
            ?.getOrNull(1)

    return playerId?.let { "https://www.youtube.com/s/player/$it/player_ias.vflset/en_US/base.js" }
}

internal fun extractSignatureTimestamp(jsCode: String): String? =
    Regex("""signatureTimestamp[:=](\d+)""").find(jsCode)?.groupValues?.getOrNull(1)

internal fun parseHlsFormats(manifestUrl: String, manifestText: String): List<VideoFormat> {
    val lines = manifestText.lineSequence().map { it.trim() }.toList()
    val preferredAudioGroups = selectPreferredHlsAudioGroups(lines)
    val variants = mutableListOf<HlsVariantCandidate>()

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!line.startsWith("#EXT-X-STREAM-INF:")) {
            index += 1
            continue
        }

        val attributes = parseM3uAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
        val audioGroup = attributes["AUDIO"]
        var targetIndex = index + 1
        while (targetIndex < lines.size && lines[targetIndex].startsWith("#")) {
            targetIndex += 1
        }
        if (targetIndex >= lines.size) {
            break
        }
        if (preferredAudioGroups.isNotEmpty() && audioGroup != null && audioGroup !in preferredAudioGroups) {
            index = targetIndex + 1
            continue
        }

        val playlistPath = lines[targetIndex]
        val resolvedUrl = URI.create(manifestUrl).resolve(playlistPath).toString()
        val resolution = attributes["RESOLUTION"].orEmpty()
        val height = resolution.substringAfter('x', "").toIntOrNull() ?: 0
        val qualityLabel = if (height > 0) "${height}p" else "HLS"
        val bandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull()
            ?: attributes["BANDWIDTH"]?.toLongOrNull()
            ?: 0L
        variants += HlsVariantCandidate(
            format = VideoFormat(
                formatId = qualityLabel,
                itag = height,
                url = resolvedUrl,
                mimeType = "application/x-mpegURL",
                qualityLabel = qualityLabel,
                bitrate = bandwidth,
            ),
            codecs = attributes["CODECS"].orEmpty(),
            audioGroupId = audioGroup,
        )
        index = targetIndex + 1
    }

    return variants
        .groupBy { it.format.qualityLabel }
        .values
        .mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<HlsVariantCandidate> {
                    hlsAudioGroupRank(it.audioGroupId, preferredAudioGroups)
                }.thenBy {
                    hlsVideoCodecRank(it.codecs)
                }.thenBy {
                    it.format.bitrate
                },
            )
        }
        .map { it.format }
        .sortedWith(compareByDescending<VideoFormat> { extractHeight(it.qualityLabel) }.thenByDescending { it.bitrate })
}

private data class HlsVariantCandidate(
    val format: VideoFormat,
    val codecs: String,
    val audioGroupId: String?,
)

private fun selectPreferredHlsAudioGroups(lines: List<String>): Set<String> {
    val audioTracks = lines
        .filter { it.startsWith("#EXT-X-MEDIA:") }
        .map { parseM3uAttributes(it.removePrefix("#EXT-X-MEDIA:")) }
        .filter { it["TYPE"]?.uppercase() == "AUDIO" }

    val originalGroups = audioTracks
        .filter { hlsAudioTrackDescriptor(it).contains("original", ignoreCase = true) }
        .mapNotNull { it["GROUP-ID"] }
        .toSet()
    if (originalGroups.isNotEmpty()) {
        return originalGroups
    }

    val nonDubbedGroups = audioTracks
        .filterNot { hlsAudioTrackDescriptor(it).contains("dubbed", ignoreCase = true) }
        .mapNotNull { it["GROUP-ID"] }
        .toSet()
    if (nonDubbedGroups.isNotEmpty()) {
        return nonDubbedGroups
    }

    return audioTracks.mapNotNull { it["GROUP-ID"] }.toSet()
}

private fun hlsAudioTrackDescriptor(attributes: Map<String, String>): String =
    listOfNotNull(
        attributes["NAME"],
        attributes["LANGUAGE"],
        attributes["YT-EXT-XTAGS"],
        attributes["YT-EXT-AUDIO-CONTENT-ID"],
    ).joinToString(" ")

private fun hlsAudioGroupRank(groupId: String?, preferredAudioGroups: Set<String>): Int =
    when {
        preferredAudioGroups.isEmpty() -> 1
        groupId != null && groupId in preferredAudioGroups -> 1
        else -> 0
    }

private fun hlsVideoCodecRank(codecs: String): Int =
    when {
        codecs.contains("avc1", ignoreCase = true) -> 4
        codecs.contains("hvc1", ignoreCase = true) || codecs.contains("hev1", ignoreCase = true) -> 3
        codecs.contains("av01", ignoreCase = true) -> 2
        codecs.contains("vp09", ignoreCase = true) -> 1
        else -> 0
    }

private fun parseM3uAttributes(raw: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    val parts = Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)""").split(raw)
    for (part in parts) {
        val separator = part.indexOf('=')
        if (separator <= 0) {
            continue
        }
        val key = part.substring(0, separator).trim()
        val value = part.substring(separator + 1).trim().trim('"')
        values[key] = value
    }
    return values
}

private fun ensureVttCaptionUrl(url: String): String =
    when {
        "fmt=" in url -> url
        "?" in url -> "$url&fmt=vtt"
        else -> "$url?fmt=vtt"
    }

private fun extractHeight(label: String): Int =
    Regex("(\\d{3,4})p").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

private fun ResolvedFormatCandidate.toVideoFormat(): VideoFormat =
    VideoFormat(
        formatId = itag.toString(),
        itag = itag,
        url = url,
        mimeType = mimeType,
        qualityLabel = qualityLabel,
        bitrate = bitrate,
    )

private fun selectAdaptiveVideoFormats(formats: List<ResolvedFormatCandidate>): List<ResolvedFormatCandidate> =
    formats
        .filter { it.hasVideo && !it.hasAudio && it.mimeType.startsWith("video/mp4") && it.initRange != null && it.indexRange != null }
        .groupBy { it.height ?: extractHeight(it.qualityLabel) }
        .toSortedMap(compareByDescending { it })
        .values
        .mapNotNull { variants ->
            variants
                .sortedWith(
                    compareByDescending<ResolvedFormatCandidate> { candidate ->
                        when {
                            candidate.mimeType.contains("avc1", ignoreCase = true) -> 3
                            candidate.mimeType.contains("av01", ignoreCase = true) -> 2
                            else -> 1
                        }
                    }.thenByDescending { it.bitrate },
                )
                .firstOrNull()
        }

private fun selectAdaptiveAudioFormat(formats: List<ResolvedFormatCandidate>): ResolvedFormatCandidate? =
    formats
        .filter { it.hasAudio && !it.hasVideo && it.mimeType.startsWith("audio/mp4") && it.initRange != null && it.indexRange != null }
        .let { audioFormats ->
            val originalNonDubbed = audioFormats.filter {
                !it.isAutoDubbed && (
                    it.url.contains("acont%3Doriginal") ||
                        it.url.contains("acont=original") ||
                        it.xtags?.contains("acont=original") == true ||
                        it.audioTrackDisplayName?.contains("original", ignoreCase = true) == true
                    )
            }
            val preferredOriginal = originalNonDubbed.ifEmpty {
                audioFormats.filter {
                    !it.url.contains("dubbed-auto") &&
                        !it.url.contains("lang%3Dde-DE") &&
                        !it.isAutoDubbed
                }
            }
            val nonDubbed = preferredOriginal.ifEmpty {
                audioFormats.filter { !it.isAutoDubbed && !it.url.contains("dubbed-auto") }
            }
            val pool = nonDubbed.ifEmpty { audioFormats }
            pool.sortedWith(
                compareBy<ResolvedFormatCandidate> { it.isDrc }
                    .thenByDescending {
                        it.audioTrackDisplayName?.contains("original", ignoreCase = true) == true ||
                            it.xtags?.contains("acont=original") == true ||
                            it.url.contains("acont%3Doriginal") ||
                            it.url.contains("acont=original")
                    }
                    .thenByDescending { it.audioSampleRate ?: 0 }
                    .thenByDescending { it.bitrate },
            ).firstOrNull()
        }

private fun buildDashManifestXml(
    videoId: String,
    lengthSeconds: Int,
    videoFormats: List<ResolvedFormatCandidate>,
    audioFormat: ResolvedFormatCandidate,
): String {
    val durationMs = sequenceOf(audioFormat.approxDurationMs, videoFormats.maxOfOrNull { it.approxDurationMs ?: 0L })
        .filterNotNull()
        .maxOrNull()
        ?: (lengthSeconds * 1000L)
    val durationSeconds = durationMs / 1000.0
    val videoRepresentations = videoFormats.joinToString("\n") { format ->
        """
        <Representation id="${format.itag}" bandwidth="${format.bitrate}" codecs="${xmlEscape(extractCodecs(format.mimeType))}" mimeType="video/mp4" width="${format.width ?: 0}" height="${format.height ?: extractHeight(format.qualityLabel)}"${format.fps?.let { " frameRate=\"$it\"" } ?: ""}>
          <BaseURL>${xmlEscape("/api/videos/$videoId/adaptive/${format.itag}")}</BaseURL>
          <SegmentBase indexRange="${format.indexRange!!.start}-${format.indexRange!!.end}">
            <Initialization range="${format.initRange!!.start}-${format.initRange!!.end}"/>
          </SegmentBase>
        </Representation>
        """.trimIndent()
    }
    val audioRepresentation = """
        <Representation id="${audioFormat.itag}" bandwidth="${audioFormat.bitrate}" codecs="${xmlEscape(extractCodecs(audioFormat.mimeType))}" mimeType="audio/mp4"${audioFormat.audioSampleRate?.let { " audioSamplingRate=\"$it\"" } ?: ""}>
          <BaseURL>${xmlEscape("/api/videos/$videoId/adaptive/${audioFormat.itag}")}</BaseURL>
          <SegmentBase indexRange="${audioFormat.indexRange!!.start}-${audioFormat.indexRange!!.end}">
            <Initialization range="${audioFormat.initRange!!.start}-${audioFormat.initRange!!.end}"/>
          </SegmentBase>
        </Representation>
    """.trimIndent()
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" minBufferTime="PT1.5S" mediaPresentationDuration="PT${"%.3f".format(durationSeconds)}S">
          <Period>
            <AdaptationSet id="video" contentType="video" segmentAlignment="true" startWithSAP="1">
              $videoRepresentations
            </AdaptationSet>
            <AdaptationSet id="audio" contentType="audio" segmentAlignment="true" startWithSAP="1">
              $audioRepresentation
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()
}

private fun extractCodecs(mimeType: String): String =
    Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.getOrNull(1) ?: ""

private fun encodeXmlUrl(url: String): String = java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8)

private fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun createClientPlaybackNonce(length: Int = 16): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    return buildString(length) {
        repeat(length) {
            append(alphabet.random())
        }
    }
}
