package com.lttprandomizer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object MsuPackImporter {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    private data class PackManifest(
        val version: Int = 1,
        val name: String = "",
        val tracks: Map<String, String> = emptyMap()
    )

    fun import(context: Context, inputStream: InputStream): Pair<MsuPlaylist?, String?> {
        return try {
            val zipStream = ZipInputStream(inputStream)
            var manifest: PackManifest? = null
            val extractedFiles = mutableMapOf<String, ByteArray>()

            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "manifest.json") {
                    val text = zipStream.bufferedReader().use { it.readText() }
                    manifest = json.decodeFromString<PackManifest>(text)
                } else if (!entry.isDirectory) {
                    extractedFiles[name] = zipStream.readBytes()
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()

            if (manifest == null)
                return Pair(null, "Invalid pack: manifest.json not found.")

            if (manifest.version != 1)
                return Pair(null, "Unsupported pack version: ${manifest.version}")

            val packName = manifest.name.ifBlank { "imported-pack" }
            val safeName = packName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "").trim().ifEmpty { "imported-pack" }

            val destDir = File(context.filesDir, "msu_library/Imported/$safeName")
            destDir.mkdirs()

            val tracks = mutableMapOf<String, String>()

            for ((slotKey, entryName) in manifest.tracks) {
                val data = extractedFiles[entryName] ?: continue
                val destFileName = entryName.substringAfterLast('/')
                val destFile = File(destDir, destFileName)

                // Guard against path traversal
                if (!destFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator))
                    continue

                if (!destFile.exists()) {
                    destFile.writeBytes(data)
                }

                tracks[slotKey] = destFile.absolutePath
            }

            Pair(MsuPlaylist(name = packName, tracks = tracks), null)
        } catch (ex: Exception) {
            Pair(null, "Import failed: ${ex.message}")
        }
    }

    fun export(outputStream: OutputStream, playlist: MsuPlaylist): String? {
        return try {
            var written = 0
            val zipOut = ZipOutputStream(outputStream)
            val manifestTracks = mutableMapOf<String, String>()

            for ((slotKey, pcmPath) in playlist.tracks) {
                val file = File(pcmPath)
                if (!file.exists()) continue
                val slot = slotKey.toIntOrNull() ?: continue

                val entryName = "tracks/${slot.toString().padStart(2, '0')}.pcm"
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()

                manifestTracks[slotKey] = entryName
                written++
            }

            val manifest = PackManifest(version = 1, name = playlist.name, tracks = manifestTracks)
            val manifestJson = json.encodeToString(manifest)
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toByteArray())
            zipOut.closeEntry()

            zipOut.close()
            null
        } catch (ex: Exception) {
            "Export failed: ${ex.message}"
        }
    }
}
