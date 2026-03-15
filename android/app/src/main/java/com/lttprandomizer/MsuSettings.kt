package com.lttprandomizer

import kotlinx.serialization.Serializable

@Serializable
data class MsuSettings(
    val includeMsu: Boolean = false,
    val packName: String = "",
    val tracks: Map<String, String> = emptyMap()
)
