package com.lttprandomizer

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PresetManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private const val TAG = "PresetManager"
    private const val PREFS_NAME = "lttp_randomizer_prefs"
    private const val KEY_PRESETS = "user_presets"
    private const val KEY_LAST_SETTINGS = "last_settings"
    private const val KEY_LAST_CUSTOMIZATION = "last_customization"
    private const val KEY_SPRITE_FAVORITES = "sprite_favorites"
    private const val KEY_ROM_URI    = "rom_uri"
    private const val KEY_OUTPUT_URI = "output_uri"

    /** Set to true if any load operation failed to deserialize saved data. */
    var lastLoadHadError = false
        private set

    private inline fun <reified T> decodeOrDefault(raw: String?, default: T): T {
        if (raw == null) return default
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode ${T::class.simpleName}, using defaults", e)
            lastLoadHadError = true
            default
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadUserPresets(context: Context): List<RandomizerPreset> =
        decodeOrDefault(prefs(context).getString(KEY_PRESETS, null), emptyList())

    fun loadLastSettings(context: Context): RandomizerSettings =
        decodeOrDefault(prefs(context).getString(KEY_LAST_SETTINGS, null), RandomizerSettings())

    fun loadCustomization(context: Context): CustomizationSettings =
        decodeOrDefault(prefs(context).getString(KEY_LAST_CUSTOMIZATION, null), CustomizationSettings())

    fun loadFavorites(context: Context): MutableSet<String> {
        return prefs(context).getStringSet(KEY_SPRITE_FAVORITES, null)?.toMutableSet()
            ?: mutableSetOf()
    }

    fun loadPaths(context: Context): Pair<String?, String?> {
        val p = prefs(context)
        return Pair(p.getString(KEY_ROM_URI, null), p.getString(KEY_OUTPUT_URI, null))
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /** Returns an error message or null on success. */
    fun savePreset(context: Context, name: String, settings: RandomizerSettings): String? {
        if (name.isBlank()) return "Preset name cannot be empty."
        if (BuiltInPresets.all.any { it.name.equals(name, ignoreCase = true) })
            return "\"$name\" is a built-in preset and cannot be overwritten."

        val presets = loadUserPresets(context).toMutableList()
        val existing = presets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (existing >= 0) presets[existing] = RandomizerPreset(name, settings)
        else presets.add(RandomizerPreset(name, settings))

        return writePresets(context, presets)
    }

    /** Returns an error message or null on success. */
    fun deletePreset(context: Context, name: String): String? {
        if (BuiltInPresets.all.any { it.name.equals(name, ignoreCase = true) })
            return "\"$name\" is a built-in preset and cannot be deleted."

        val presets = loadUserPresets(context).toMutableList()
        val removed = presets.removeAll { it.name.equals(name, ignoreCase = true) }
        if (!removed) return "Preset \"$name\" not found."
        return writePresets(context, presets)
    }

    fun saveLastSettings(context: Context, settings: RandomizerSettings) {
        prefs(context).edit().putString(KEY_LAST_SETTINGS, json.encodeToString(settings)).apply()
    }

    fun saveCustomization(context: Context, c: CustomizationSettings) {
        prefs(context).edit().putString(KEY_LAST_CUSTOMIZATION, json.encodeToString(c)).apply()
    }

    private const val KEY_MSU_SETTINGS = "msu_settings"

    fun loadMsuSettings(context: Context): MsuSettings =
        decodeOrDefault(prefs(context).getString(KEY_MSU_SETTINGS, null), MsuSettings())

    fun saveMsuSettings(context: Context, s: MsuSettings) {
        prefs(context).edit().putString(KEY_MSU_SETTINGS, json.encodeToString(s)).apply()
    }

    fun saveFavorites(context: Context, favorites: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SPRITE_FAVORITES, favorites).apply()
    }

    fun savePaths(context: Context, romUri: String?, outputUri: String?) {
        prefs(context).edit()
            .putString(KEY_ROM_URI, romUri)
            .putString(KEY_OUTPUT_URI, outputUri)
            .apply()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun writePresets(context: Context, presets: List<RandomizerPreset>): String? {
        return try {
            prefs(context).edit().putString(KEY_PRESETS, json.encodeToString(presets)).apply()
            null
        } catch (e: Exception) { "Failed to save presets: ${e.message}" }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
