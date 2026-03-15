package com.lttprandomizer

import kotlinx.serialization.Serializable

@Serializable
data class MsuPlaylist(
    val version: Int = 1,
    val name: String = "",
    val tracks: Map<String, String> = emptyMap(),
    val lastModified: String = ""
)
