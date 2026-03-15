package com.lttprandomizer

import androidx.documentfile.provider.DocumentFile
import android.content.Context
import android.net.Uri
import java.io.File

object MsuPackApplier {
    fun apply(context: Context, outputDir: DocumentFile, romFileName: String, tracks: Map<String, String>): String? {
        return try {
            val baseName = romFileName.substringBeforeLast(".")

            // Write empty .msu marker file
            val msuFile = outputDir.createFile("application/octet-stream", "$baseName.msu")
                ?: return "Failed to create .msu marker file."
            // File is created empty by default via SAF

            // Copy each assigned PCM alongside the ROM
            for ((slot, pcmPath) in tracks.toSortedMap(compareBy { it.toInt() })) {
                val srcFile = File(pcmPath)
                if (!srcFile.exists()) return "PCM file for slot $slot not found: $pcmPath"

                val pcmName = "$baseName-$slot.pcm"
                // Remove existing file if present
                outputDir.findFile(pcmName)?.delete()

                val destFile = outputDir.createFile("application/octet-stream", pcmName)
                    ?: return "Failed to create PCM file: $pcmName"

                context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return "Failed to write PCM file: $pcmName"
            }

            null
        } catch (ex: Exception) {
            "MSU apply failed: ${ex.message}"
        }
    }
}
