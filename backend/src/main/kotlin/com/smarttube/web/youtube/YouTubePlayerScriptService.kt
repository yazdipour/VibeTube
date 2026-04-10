package com.smarttube.web.youtube

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class YouTubePlayerScriptService(
    private val jsCode: String,
) {
    private val globalPrelude: String? = extractGlobalPrelude(jsCode)
    private val signatureFunctionName: String? = extractSignatureFunctionName(jsCode)
    private val nFunctionName: String? = extractNFunctionName(jsCode)
    private val signatureScript: String? = signatureFunctionName?.let { buildInvocationScript(it, "decipherSignature") }
    private val nScript: String? = nFunctionName?.let { buildInvocationScript(it, "transformN") }?.let(::fixupNFunctionCode)

    fun resolveFormat(candidate: StreamingFormatCandidate): ResolvedFormatCandidate? {
        val cipherParts = parseCipherPayload(candidate.signatureCipher ?: candidate.cipher)
        val baseUrl = candidate.url ?: cipherParts?.url ?: return null
        val query = splitUrl(baseUrl).second.toMutableMap()
        val signature = cipherParts?.s?.let { decipherSignature(it) }
        val signatureParam = cipherParts?.sp ?: "sig"
        if (cipherParts?.s != null && signature == null) {
            return null
        }
        if (signature != null) {
            query[signatureParam] = signature
        }

        val nValue = query["n"]
        if (!nValue.isNullOrBlank()) {
            transformN(nValue)?.takeIf { it.isNotBlank() }?.let { query["n"] = it }
        }

        return ResolvedFormatCandidate(
            url = rebuildUrl(baseUrl, query),
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

    fun decipherSignature(signature: String): String? = signatureScript?.let { invoke(it, "decipherSignature", signature) }

    fun transformN(value: String): String? = nScript?.let { invoke(it, "transformN", value) } ?: value

    private fun buildInvocationScript(functionName: String, exportedName: String): String? {
        val functionCode = extractFunctionCode(jsCode, functionName) ?: return null
        val helperNames = extractReferencedHelperNames(functionCode)
        val helperCode = helperNames.mapNotNull { extractObjectCode(jsCode, it) }
        return buildString {
            globalPrelude?.let {
                append(it)
                append(';')
            }
            helperCode.distinct().forEach {
                append(it)
                append(';')
            }
            append(functionCode)
            append(';')
            append("function ")
            append(exportedName)
            append("(a){return ")
            append(functionName)
            append("(a);}")
        }
    }

    private fun invoke(script: String, functionName: String, value: String): String? {
        val context = Context.enter()
        return try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            val scope: Scriptable = context.initStandardObjects()
            context.evaluateString(scope, script, "youtube-player", 1, null)
            val function = scope.get(functionName, scope) as? Function ?: return null
            val result = function.call(context, scope, scope, arrayOf(value))
            Context.toString(result)
        } catch (_: Exception) {
            null
        } finally {
            Context.exit()
        }
    }
}

internal data class StreamingFormatCandidate(
    val url: String?,
    val cipher: String?,
    val signatureCipher: String?,
    val mimeType: String,
    val itag: Int,
    val qualityLabel: String,
    val bitrate: Long,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val contentLength: Long?,
    val approxDurationMs: Long?,
    val initRange: ByteRange?,
    val indexRange: ByteRange?,
    val audioSampleRate: Int?,
    val audioTrackDisplayName: String?,
    val audioTrackId: String?,
    val audioIsDefault: Boolean,
    val isAutoDubbed: Boolean,
    val isDrc: Boolean,
    val xtags: String?,
)

internal data class ResolvedFormatCandidate(
    val url: String,
    val mimeType: String,
    val itag: Int,
    val qualityLabel: String,
    val bitrate: Long,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val contentLength: Long?,
    val approxDurationMs: Long?,
    val initRange: ByteRange?,
    val indexRange: ByteRange?,
    val audioSampleRate: Int?,
    val audioTrackDisplayName: String?,
    val audioTrackId: String?,
    val audioIsDefault: Boolean,
    val isAutoDubbed: Boolean,
    val isDrc: Boolean,
    val xtags: String?,
)

internal data class ByteRange(
    val start: Long,
    val end: Long,
)

internal data class CipherPayload(
    val url: String?,
    val s: String?,
    val sp: String?,
)

internal fun parseCipherPayload(cipherText: String?): CipherPayload? {
    if (cipherText.isNullOrBlank()) {
        return null
    }
    val query = parseQueryString(cipherText)
    return CipherPayload(
        url = query["url"],
        s = query["s"],
        sp = query["sp"],
    )
}

internal fun extractGlobalPrelude(jsCode: String): String? =
    Regex(
        """(?x)
            (["'])use\s+strict\1;\s*
            (
                var\s+([a-zA-Z0-9_$]+)\s*=\s*
                (
                    (["'])(?:(?!\5).|\\.)+\5
                    \.split\((["'])(?:(?!\6).)+\6\)
                    |\[\s*(?:(["'])(?:(?!\7).|\\.)*\7\s*,?\s*)+\]
                )
            )[;,]
        """.trimIndent(),
    ).find(jsCode)?.groupValues?.getOrNull(2)

internal fun extractSignatureFunctionName(jsCode: String): String? {
    val primary = Regex("""\b([a-zA-Z0-9_$]+)&&\(\1=([a-zA-Z0-9_$]{2,})\(decodeURIComponent\(\1\)\)""")
        .find(jsCode)
        ?.groupValues
        ?.getOrNull(2)
    if (!primary.isNullOrBlank()) {
        return primary
    }

    return Regex("""\b[a-zA-Z0-9_$]+\s*=\s*function\(\s*a\s*\)\s*\{\s*a=a\.split\((?:""|'.*?')\)""")
        .find(jsCode)
        ?.value
        ?.substringBefore("=")
        ?.trim()
        ?.removePrefix("var ")
}

internal fun extractNFunctionName(jsCode: String): String? {
    val candidates = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9_$]+)\([a-zA-Z]\)"""),
        Regex("""\bn=([a-zA-Z0-9_$]+)\(n\)"""),
        Regex(""";\s*([a-zA-Z0-9_$]+)\s*=\s*function\([a-zA-Z0-9_$]+\)\s*\{(?:(?!\};).)+?return\s*(["'])[\w-]+_w8_\1"""),
    )
    return candidates.firstNotNullOfOrNull { regex ->
        regex.find(jsCode)?.groupValues?.getOrNull(1)
    }
}

internal fun extractFunctionCode(jsCode: String, functionName: String): String? {
    val patterns = listOf(
        Regex("""function\s+$functionName\s*\([^)]*\)\s*\{"""),
        Regex("""(?:var\s+)?$functionName\s*=\s*function\s*\([^)]*\)\s*\{"""),
    )

    for (pattern in patterns) {
        val match = pattern.find(jsCode) ?: continue
        val openBraceIndex = jsCode.indexOf('{', match.range.first)
        if (openBraceIndex < 0) {
            continue
        }
        val closeBraceIndex = findMatchingBrace(jsCode, openBraceIndex) ?: continue
        var code = jsCode.substring(match.range.first, closeBraceIndex + 1)
        if (!code.trimStart().startsWith("function")) {
            code += ";"
        }
        return code
    }

    return null
}

internal fun extractObjectCode(jsCode: String, objectName: String): String? {
    val patterns = listOf(
        Regex("""(?:var\s+|const\s+|let\s+)?$objectName\s*=\s*\{"""),
        Regex("""$objectName\s*:\s*\{"""),
    )
    for (pattern in patterns) {
        val match = pattern.find(jsCode) ?: continue
        val openBraceIndex = jsCode.indexOf('{', match.range.first)
        if (openBraceIndex < 0) {
            continue
        }
        val closeBraceIndex = findMatchingBrace(jsCode, openBraceIndex) ?: continue
        val prefix = if (match.value.contains(":")) "var $objectName=" else ""
        return prefix + jsCode.substring(openBraceIndex.takeIf { prefix.isNotEmpty() } ?: match.range.first, closeBraceIndex + 1)
    }
    return null
}

internal fun extractReferencedHelperNames(functionCode: String): Set<String> =
    Regex("""([A-Za-z_$][A-Za-z0-9_$]{1,})\.[A-Za-z_$][A-Za-z0-9_$]{1,}\(""")
        .findAll(functionCode)
        .map { it.groupValues[1] }
        .toSet()

internal fun fixupNFunctionCode(script: String): String =
    Regex(""";\s*if\s*\(\s*typeof\s+[A-Za-z0-9_$]+\s*===?\s*(['"])undefined\1\s*\)\s*return\s+[A-Za-z0-9_$]+\s*;""")
        .replace(script, ";")

private fun findMatchingBrace(source: String, openBraceIndex: Int): Int? {
    var depth = 0
    var index = openBraceIndex
    var inSingle = false
    var inDouble = false
    var inTemplate = false
    var escaped = false

    while (index < source.length) {
        val char = source[index]
        when {
            escaped -> escaped = false
            char == '\\' && (inSingle || inDouble || inTemplate) -> escaped = true
            char == '\'' && !inDouble && !inTemplate -> inSingle = !inSingle
            char == '"' && !inSingle && !inTemplate -> inDouble = !inDouble
            char == '`' && !inSingle && !inDouble -> inTemplate = !inTemplate
            inSingle || inDouble || inTemplate -> {}
            char == '{' -> depth += 1
            char == '}' -> {
                depth -= 1
                if (depth == 0) {
                    return index
                }
            }
        }
        index += 1
    }

    return null
}

private fun splitUrl(url: String): Pair<String, Map<String, String>> {
    val index = url.indexOf('?')
    return if (index >= 0) {
        url.substring(0, index) to parseQueryString(url.substring(index + 1))
    } else {
        url to emptyMap()
    }
}

private fun parseQueryString(query: String): Map<String, String> =
    query.split('&')
        .asSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) {
                return@mapNotNull null
            }
            val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            key to value
        }
        .toMap()

private fun rebuildUrl(baseUrl: String, query: Map<String, String>): String {
    val targetBase = baseUrl.substringBefore('?')
    if (query.isEmpty()) {
        return targetBase
    }
    val rendered = query.entries.joinToString("&") { (key, value) ->
        "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
    }
    return "$targetBase?$rendered"
}

private fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
