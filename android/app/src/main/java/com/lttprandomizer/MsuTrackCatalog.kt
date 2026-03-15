package com.lttprandomizer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object MsuTrackCatalog {
    @Serializable
    private data class CatalogEntry(val slot: Int, val name: String, val type: String)

    fun load(context: Context): List<MsuTrackSlot> {
        val json = context.resources.openRawResource(R.raw.track_catalog)
            .bufferedReader().use { it.readText() }
        val entries = Json.decodeFromString<List<CatalogEntry>>(json)
        return entries.map { MsuTrackSlot(slotNumber = it.slot, name = it.name, trackType = it.type) }
    }
}
