package com.lttprandomizer

data class MsuTrackSlot(
    val slotNumber: Int,
    val name: String,
    val trackType: String = "music",
    var pcmPath: String? = null,
    var originalPcmPath: String? = null,
    var isPlaying: Boolean = false,
    var isPlayingOriginal: Boolean = false,
) {
    val slotDisplay: String get() = slotNumber.toString().padStart(2, '0')

    val typeLabel: String get() = when (trackType) {
        "jingle" -> "[SFX]"
        "extended" -> "[EXT]"
        else -> ""
    }

    val hasTypeLabel: Boolean get() = trackType != "music"
    val fileName: String? get() = pcmPath?.substringAfterLast('/')
    val hasFile: Boolean get() = pcmPath != null
    val hasOriginal: Boolean get() = originalPcmPath != null
    val playButtonText: String get() = if (isPlaying) "■" else "▶"
    val originalPlayButtonText: String get() = if (isPlayingOriginal) "■" else "♪"
}
