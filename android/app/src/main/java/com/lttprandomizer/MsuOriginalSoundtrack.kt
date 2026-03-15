package com.lttprandomizer

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object MsuOriginalSoundtrack {
    private fun cacheDir(context: Context) = File(context.filesDir, "original_audio")

    private val ostAliases = mapOf(
        "title" to 1,
        "link to the past" to 1,
        "beginning of the journey" to 6,
        "seal of seven maidens" to 3,
        "time of the falling rain" to 3,
        "majestic castle" to 16,
        "princess zeldas rescue" to 25,
        "safety in the sanctuary" to 20,
        "hyrule field main theme" to 2,
        "hyrule field" to 2,
        "kakariko village" to 7,
        "guessing game house" to 14,
        "fortune teller" to 23,
        "soldiers of kakariko" to 12,
        "dank dungeons" to 17,
        "lost ancient ruins" to 18,
        "anger of the guardians" to 21,
        "great victory" to 19,
        "silly pink rabbit" to 4,
        "forest of mystery" to 5,
        "unsealing the master sword" to 10,
        "priest of the dark order" to 28,
        "dark golden land" to 9,
        "black mist" to 13,
        "dungeon of shadows" to 22,
        "meeting the maidens" to 26,
        "goddess appears" to 27,
        "release of ganon" to 29,
        "ganons message" to 30,
        "prince of darkness" to 31,
        "power of the gods" to 32,
        "epilogue" to 33,
        "beautiful hyrule" to 33,
        "staff roll" to 34,
        "credits" to 34,
        "overworld" to 2,
        "dark world theme" to 9,
        "file select" to 11,
        "game over" to 11,
        "sanctuary" to 20,
        "lost woods" to 5,
        "kakariko" to 7,
        "ganon battle" to 31,
        "ganon fight" to 31,
        "triforce" to 32,
        "fairy fountain" to 27,
        "boss battle" to 21,
        "boss victory" to 19,
        "cave" to 18,
        "shop" to 23,
        "minigame" to 14,
        "skull woods" to 15,
        "dimensional shift" to 8,
        "teleport" to 8,
    )

    fun loadCachedOriginals(context: Context, tracks: List<MsuTrackSlot>) {
        val dir = cacheDir(context)
        if (!dir.exists()) return

        for (track in tracks) {
            val cached = File(dir, "${track.slotDisplay}.pcm")
            if (cached.exists()) track.originalPcmPath = cached.absolutePath
        }
    }

    fun clearCache(context: Context, tracks: List<MsuTrackSlot>) {
        for (track in tracks) track.originalPcmPath = null
        val dir = cacheDir(context)
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Import pre-converted PCM files from a folder. Android only supports .pcm (no conversion). */
    fun importFromFolder(context: Context, folderPath: String, tracks: List<MsuTrackSlot>): String? {
        val folder = File(folderPath)
        if (!folder.exists()) return "Folder does not exist."

        val pcmFiles = folder.listFiles()?.filter { it.extension.equals("pcm", ignoreCase = true) } ?: emptyList()
        if (pcmFiles.isEmpty()) return "No PCM files found in folder."

        val matches = matchFilesToSlots(pcmFiles.map { it.absolutePath }, tracks)
        if (matches.isEmpty()) return "Could not match any files to track slots."

        return copyToCache(context, matches, tracks)
    }

    /** Import pre-converted PCM files from a ZIP. */
    fun importFromZip(context: Context, inputStream: java.io.InputStream, tracks: List<MsuTrackSlot>): String? {
        val tempDir = File(context.cacheDir, "ost_import_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val zipStream = ZipInputStream(inputStream)
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".pcm", ignoreCase = true)) {
                    val destFile = File(tempDir, entry.name.substringAfterLast('/'))
                    if (destFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        destFile.outputStream().use { out -> zipStream.copyTo(out) }
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()

            val pcmFiles = tempDir.listFiles()?.filter { it.extension.equals("pcm", ignoreCase = true) } ?: emptyList()
            if (pcmFiles.isEmpty()) return "No PCM files found in ZIP."

            val matches = matchFilesToSlots(pcmFiles.map { it.absolutePath }, tracks)
            if (matches.isEmpty()) return "Could not match any files to track slots."

            return copyToCache(context, matches, tracks)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun matchFilesToSlots(filePaths: List<String>, tracks: List<MsuTrackSlot>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val slotLookup = tracks.associateBy { it.slotNumber }
        val usedSlots = mutableSetOf<Int>()
        val leadingNumberRegex = Regex("^\\d+")
        val anyNumberRegex = Regex("\\d+")

        // Pass 1: alias table
        for (filePath in filePaths) {
            val fileName = File(filePath).nameWithoutExtension
            val cleaned = leadingNumberRegex.replace(fileName, "")
                .replace('_', ' ').replace('-', ' ').replace('~', ' ')
                .replace(".", " ").replace("'", "").replace("\u2019", "").trim()

            for ((alias, slot) in ostAliases) {
                if (slot in usedSlots || slot !in slotLookup) continue
                if (cleaned.contains(alias, ignoreCase = true)) {
                    result[slot] = filePath
                    usedSlots.add(slot)
                    break
                }
            }
        }

        // Pass 2: leading number
        for (filePath in filePaths) {
            if (filePath in result.values) continue
            val fileName = File(filePath).nameWithoutExtension
            val match = leadingNumberRegex.find(fileName) ?: continue
            val slot = match.value.toIntOrNull() ?: continue
            if (slot in 1..61 && slot in slotLookup && usedSlots.add(slot))
                result[slot] = filePath
        }

        // Pass 3: any number
        for (filePath in filePaths) {
            if (filePath in result.values) continue
            val fileName = File(filePath).nameWithoutExtension
            for (match in anyNumberRegex.findAll(fileName)) {
                val slot = match.value.toIntOrNull() ?: continue
                if (slot in 1..61 && slot in slotLookup && usedSlots.add(slot)) {
                    result[slot] = filePath
                    break
                }
            }
        }

        // Pass 4: fuzzy name match
        for (filePath in filePaths) {
            if (filePath in result.values) continue
            val fileName = File(filePath).nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
            for (track in tracks) {
                if (track.slotNumber in usedSlots) continue
                if (fileName.contains(track.name, ignoreCase = true) ||
                    track.name.contains(fileName, ignoreCase = true)) {
                    result[track.slotNumber] = filePath
                    usedSlots.add(track.slotNumber)
                    break
                }
            }
        }

        return result
    }

    private fun copyToCache(context: Context, matches: Map<Int, String>, tracks: List<MsuTrackSlot>): String? {
        val dir = cacheDir(context)
        dir.mkdirs()

        val slotLookup = tracks.associateBy { it.slotNumber }
        var failed = 0

        for ((slot, sourcePath) in matches.toSortedMap()) {
            val destPath = File(dir, "${slot.toString().padStart(2, '0')}.pcm")
            try {
                if (!destPath.exists()) {
                    File(sourcePath).copyTo(destPath, overwrite = true)
                }
                slotLookup[slot]?.originalPcmPath = destPath.absolutePath
            } catch (e: Exception) {
                failed++
            }
        }

        if (failed > 0 && failed == matches.size)
            return "All file copies failed."
        if (failed > 0)
            return "${matches.size - failed} of ${matches.size} tracks imported. $failed file(s) failed."
        return null
    }
}
