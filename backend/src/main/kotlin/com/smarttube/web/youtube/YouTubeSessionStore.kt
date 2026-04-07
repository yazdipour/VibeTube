package com.smarttube.web.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class YouTubeSessionStore(
    private val properties: YouTubeProperties,
    private val objectMapper: ObjectMapper,
) {
    private val lock = Any()

    fun load(): StoredYouTubeSession? = synchronized(lock) {
        val path = sessionPath()
        if (!Files.exists(path)) {
            return@synchronized null
        }

        Files.newBufferedReader(path).use { reader ->
            objectMapper.readValue(reader, StoredYouTubeSession::class.java)
        }
    }

    fun save(session: StoredYouTubeSession) = synchronized(lock) {
        val path = sessionPath()
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { writer ->
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, session)
        }
    }

    fun clear() = synchronized(lock) {
        Files.deleteIfExists(sessionPath())
    }

    private fun sessionPath(): Path = Path.of(properties.dataDir, "youtube-session.json")
}
