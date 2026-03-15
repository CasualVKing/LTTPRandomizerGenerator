package com.lttprandomizer

import java.io.File
import java.io.RandomAccessFile

object MsuPcmValidator {
    private const val EXPECTED_SIGNATURE = "MSU1"
    private const val HEADER_SIZE = 8

    fun validate(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return "File does not exist."
            if (file.length() < HEADER_SIZE) return "File too small (${file.length()} bytes). MSU-1 header requires at least $HEADER_SIZE bytes."

            val header = ByteArray(HEADER_SIZE)
            RandomAccessFile(file, "r").use { raf ->
                val bytesRead = raf.read(header)
                if (bytesRead < HEADER_SIZE) return "Could not read MSU-1 header."
            }

            val sig = String(header, 0, 4, Charsets.US_ASCII)
            if (sig != EXPECTED_SIGNATURE)
                return "Invalid MSU-1 signature. Expected \"$EXPECTED_SIGNATURE\", found \"$sig\"."

            if (file.length() <= HEADER_SIZE)
                return "File contains only the MSU-1 header with no audio data."

            null
        } catch (ex: Exception) {
            "Cannot read file: ${ex.message}"
        }
    }
}
