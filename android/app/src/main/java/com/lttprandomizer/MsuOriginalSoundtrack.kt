package com.lttprandomizer

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object MsuOriginalSoundtrack {
    private fun cacheDir(context: Context) = File(context.filesDir, "original_audio")

    private val audioExtensions = setOf("pcm", "mp3", "ogg", "wav", "flac", "m4a", "aac")

    private fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    // Sorted longest-first so specific aliases match before generic substrings.
    // e.g. "soldiers of kakariko" must match before "kakariko village" or "kakariko".
    private val ostAliases: List<Pair<String, Int>> = listOf(
        "soldiers of kakariko" to 12,
        "beginning of the journey" to 6,
        "unsealing the master sword" to 10,
        "hyrule field main theme" to 2,
        "safety in the sanctuary" to 20,
        "priest of the dark order" to 28,
        "time of the falling rain" to 3,
        "seal of seven maidens" to 3,
        "anger of the guardians" to 21,
        "princess zeldas rescue" to 25,
        "meeting the maidens" to 26,
        "prince of darkness" to 31,
        "dungeon of shadows" to 22,
        "guessing game house" to 14,
        "kakariko village" to 7,
        "lost ancient ruins" to 18,
        "dark golden land" to 9,
        "dimensional shift" to 8,
        "release of ganon" to 29,
        "majestic castle" to 16,
        "forest of mystery" to 5,
        "silly pink rabbit" to 4,
        "power of the gods" to 32,
        "dark world theme" to 9,
        "goddess appears" to 27,
        "ganons message" to 30,
        "link to the past" to 1,
        "beautiful hyrule" to 33,
        "fairy fountain" to 27,
        "dank dungeons" to 17,
        "great victory" to 19,
        "hyrule field" to 2,
        "fortune teller" to 23,
        "boss victory" to 19,
        "black mist" to 13,
        "skull woods" to 15,
        "ganon battle" to 31,
        "boss battle" to 21,
        "ganon fight" to 31,
        "dark world" to 9,
        "lost woods" to 5,
        "staff roll" to 34,
        "quit game" to 11,
        "game over" to 11,
        "sanctuary" to 20,
        "overworld" to 2,
        "kakariko" to 7,
        "teleport" to 8,
        "triforce" to 32,
        "epilogue" to 33,
        "minigame" to 14,
        "credits" to 34,
        "title" to 1,
        "cave" to 18,
        "shop" to 23,
    )

    fun loadCachedOriginals(context: Context, tracks: List<MsuTrackSlot>) {
        val dir = cacheDir(context)
        if (!dir.exists()) return

        val cachedFiles = dir.listFiles() ?: return
        for (track in tracks) {
            val match = cachedFiles.firstOrNull {
                it.nameWithoutExtension == track.slotDisplay && isAudioFile(it.name)
            }
            if (match != null) track.originalPcmPath = match.absolutePath
        }
    }

    fun clearCache(context: Context, tracks: List<MsuTrackSlot>) {
        for (track in tracks) track.originalPcmPath = null
        val dir = cacheDir(context)
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Import audio files from a folder. Supports PCM, MP3, OGG, WAV, FLAC, M4A, AAC. */
    fun importFromFolder(context: Context, folderPath: String, tracks: List<MsuTrackSlot>): String? {
        val folder = File(folderPath)
        if (!folder.exists()) return "Folder does not exist."

        val audioFiles = folder.listFiles()?.filter { isAudioFile(it.name) } ?: emptyList()
        if (audioFiles.isEmpty()) return "No audio files found in folder."

        val matches = matchFilesToSlots(audioFiles.map { it.absolutePath }, tracks)
        if (matches.isEmpty()) return "Could not match any files to track slots."

        return copyToCache(context, matches, tracks)
    }

    /** Import audio files from a ZIP. Supports PCM, MP3, OGG, WAV, FLAC, M4A, AAC. */
    fun importFromZip(context: Context, inputStream: java.io.InputStream, tracks: List<MsuTrackSlot>): String? {
        val tempDir = File(context.cacheDir, "ost_import_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val zipStream = ZipInputStream(inputStream)
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isAudioFile(entry.name)) {
                    val destFile = File(tempDir, entry.name.substringAfterLast('/'))
                    if (destFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        destFile.outputStream().use { out -> zipStream.copyTo(out) }
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()

            val audioFiles = tempDir.listFiles()?.filter { isAudioFile(it.name) } ?: emptyList()
            if (audioFiles.isEmpty()) return "No audio files found in ZIP."

            val matches = matchFilesToSlots(audioFiles.map { it.absolutePath }, tracks)
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
        val aliasMatchedFiles = mutableSetOf<String>() // files that matched an alias (even if slot taken)
        val leadingNumberRegex = Regex("^\\d+")
        val anyNumberRegex = Regex("\\d+")

        // Sort: non-variant files first so "Majestic Castle" beats "Majestic Castle (Storm)"
        val variantPattern = Regex("""\(storm\)|\(short\)|\(variant\)""", RegexOption.IGNORE_CASE)
        val sortedPaths = filePaths.sortedBy { if (variantPattern.containsMatchIn(File(it).nameWithoutExtension)) 1 else 0 }

        // Pass 1: alias table
        for (filePath in sortedPaths) {
            val fileName = File(filePath).nameWithoutExtension
            val cleaned = leadingNumberRegex.replace(fileName, "")
                .replace('_', ' ').replace('-', ' ').replace('~', ' ')
                .replace(".", " ").replace("'", "").replace("\u2019", "").trim()

            for ((alias, slot) in ostAliases) {
                if (slot !in slotLookup) continue
                if (cleaned.contains(alias, ignoreCase = true)) {
                    aliasMatchedFiles.add(filePath)
                    if (slot !in usedSlots) {
                        result[slot] = filePath
                        usedSlots.add(slot)
                    }
                    break // first matching alias wins (list is sorted longest-first)
                }
            }
        }

        // Pass 2: leading number (skip files that matched an alias — their number is likely a disc track, not a slot)
        for (filePath in sortedPaths) {
            if (filePath in result.values || filePath in aliasMatchedFiles) continue
            val fileName = File(filePath).nameWithoutExtension
            val match = leadingNumberRegex.find(fileName) ?: continue
            val slot = match.value.toIntOrNull() ?: continue
            if (slot in 1..61 && slot in slotLookup && usedSlots.add(slot))
                result[slot] = filePath
        }

        // Pass 3: any number (skip alias-matched files)
        for (filePath in sortedPaths) {
            if (filePath in result.values || filePath in aliasMatchedFiles) continue
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
        for (filePath in sortedPaths) {
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
            val ext = File(sourcePath).extension.ifEmpty { "pcm" }
            val slotName = slot.toString().padStart(2, '0')
            // Remove any existing file for this slot (might have different extension)
            dir.listFiles()?.filter { it.nameWithoutExtension == slotName }?.forEach { it.delete() }
            val destPath = File(dir, "$slotName.$ext")
            try {
                File(sourcePath).copyTo(destPath, overwrite = true)
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
