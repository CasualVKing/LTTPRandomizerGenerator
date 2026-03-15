package com.lttprandomizer

import java.io.File

class MsuMusicLibrary {
    var libraryFolder: String? = null
        private set

    var entries: List<MsuLibraryEntry> = emptyList()
        private set

    fun setFolder(folder: String?) {
        libraryFolder = folder?.takeIf { it.isNotBlank() }
        refresh()
    }

    fun refresh() {
        val dir = libraryFolder?.let { File(it) }
        if (dir == null || !dir.exists()) {
            entries = emptyList()
            return
        }

        entries = dir.listFiles()
            ?.filter { it.extension.equals("pcm", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension.lowercase() }
            ?.map { MsuLibraryEntry(name = it.nameWithoutExtension, sourcePath = it.absolutePath) }
            ?: emptyList()
    }
}

data class MsuLibraryEntry(
    val name: String,
    val sourcePath: String
)
