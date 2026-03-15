package com.lttprandomizer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

object MsuPlaylistManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(inputStream: InputStream): Pair<MsuPlaylist?, String?> {
        return try {
            val text = inputStream.bufferedReader().use { it.readText() }
            val playlist = json.decodeFromString<MsuPlaylist>(text)
            if (playlist.version != 1)
                Pair(null, "Unsupported playlist version: ${playlist.version}")
            else
                Pair(playlist, null)
        } catch (ex: Exception) {
            Pair(null, "Failed to load playlist: ${ex.message}")
        }
    }

    fun save(outputStream: OutputStream, playlist: MsuPlaylist): String? {
        return try {
            val updated = playlist.copy(
                lastModified = Instant.now().toString(),
                tracks = playlist.tracks.mapValues { it.value.replace('\\', '/') }
            )
            val text = json.encodeToString(updated)
            outputStream.bufferedWriter().use { it.write(text) }
            null
        } catch (ex: Exception) {
            "Failed to save playlist: ${ex.message}"
        }
    }
}
