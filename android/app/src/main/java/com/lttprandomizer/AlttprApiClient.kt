package com.lttprandomizer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object AlttprApiClient {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    internal val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "LTTPRandomizerGenerator-Android/0.1")
                    .build()
            )
        }
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val BASE = "https://alttpr.com"

    data class SeedResult(
        val hash: String,
        val permalink: String,
        val bpsBytes: ByteArray,
        val dictPatches: List<Map<String, List<Int>>>,
        val sizeMb: Int,
    )

    data class FetchedSeed(
        val seed: SeedResult,
        val settings: RandomizerSettings?,
    )

    @kotlinx.serialization.Serializable
    private data class HashInfo(
        @kotlinx.serialization.SerialName("bpsLocation") val bpsLocation: String = "",
        @kotlinx.serialization.SerialName("md5")         val md5: String = "",
    )

    @kotlinx.serialization.Serializable
    private data class SeedHashResponse(
        @kotlinx.serialization.SerialName("hash")    val hash: String = "",
        @kotlinx.serialization.SerialName("patch")   val patch: List<Map<String, List<Int>>> = emptyList(),
        @kotlinx.serialization.SerialName("size")    val size: Int = 2,
        @kotlinx.serialization.SerialName("spoiler") val spoiler: JsonObject? = null,
    )

    /** Extracts a seed hash from user input (full URL or raw hash). */
    fun parseSeedHash(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val match = Regex("""/h/([A-Za-z0-9]+)""").find(trimmed)
        if (match != null) return match.groupValues[1]
        if (trimmed.matches(Regex("""^[A-Za-z0-9]{5,20}$"""))) return trimmed
        return null
    }

    /**
     * Fetches an existing seed by hash from alttpr.com.
     * Uses GET /hash/{hash} for patch data, then GET /api/h/{hash} for BPS location.
     */
    fun fetchSeed(hash: String, onProgress: (String) -> Unit): FetchedSeed {
        onProgress("Fetching seed data…")
        val seedData: SeedHashResponse = http.newCall(
            Request.Builder().url("$BASE/hash/$hash").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("Seed not found (${resp.code})")
            val raw = resp.body?.string() ?: throw IOException("Empty response")
            json.decodeFromString(raw)
        }

        onProgress("Fetching patch metadata…")
        val hashInfo: HashInfo = http.newCall(
            Request.Builder().url("$BASE/api/h/$hash").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("Failed to fetch patch metadata: ${resp.code}")
            val raw = resp.body?.string() ?: throw IOException("Empty metadata response")
            json.decodeFromString(raw)
        }

        onProgress("Downloading base patch…")
        val bpsBytes = if (hashInfo.bpsLocation.isNotBlank()) {
            val bpsUrl = if (hashInfo.bpsLocation.startsWith("http")) hashInfo.bpsLocation
                         else "$BASE${hashInfo.bpsLocation}"
            http.newCall(Request.Builder().url(bpsUrl).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful)
                        throw IOException("Failed to download BPS patch: ${resp.code}")
                    resp.body?.bytes() ?: throw IOException("Empty BPS response")
                }
        } else ByteArray(0)

        val settings = seedData.spoiler?.get("meta")?.jsonObject?.let { parseSettingsFromMeta(it) }

        return FetchedSeed(
            seed = SeedResult(
                hash        = hash,
                permalink   = "$BASE/h/$hash",
                bpsBytes    = bpsBytes,
                dictPatches = seedData.patch,
                sizeMb      = seedData.size,
            ),
            settings = settings,
        )
    }

    private fun parseSettingsFromMeta(meta: JsonObject): RandomizerSettings {
        fun s(key: String, def: String): String =
            meta[key]?.jsonPrimitive?.content ?: def
        fun b(key: String, def: Boolean): Boolean =
            try { meta[key]?.jsonPrimitive?.boolean ?: def } catch (_: Exception) { def }

        val logic = s("logic", "NoGlitches")
        val glitches = when (logic) {
            "NoGlitches"        -> "none"
            "MinorGlitches"     -> "minor_glitches"
            "OverworldGlitches" -> "overworld_glitches"
            "MajorGlitches"     -> "major_glitches"
            else                -> "none"
        }

        return RandomizerSettings(
            glitches      = glitches,
            itemPlacement = s("item_placement", "basic"),
            dungeonItems  = s("dungeon_items", "standard"),
            accessibility = s("accessibility", "items"),
            goal          = s("goal", "ganon"),
            crystals      = CrystalsSettings(
                tower = s("entry_crystals_tower", "7"),
                ganon = s("entry_crystals_ganon", "7"),
            ),
            mode          = s("mode", "open"),
            entrances     = s("entrances", "none"),
            hints         = s("hints", "on"),
            weapons       = s("weapons", "randomized"),
            item          = ItemSettings(
                pool          = s("item_pool", "normal"),
                functionality = s("item_functionality", "normal"),
            ),
            spoilers      = s("spoilers", "on"),
            pseudoboots   = b("pseudoboots", false),
            enemizer      = EnemizerSettings(
                bossShuffle  = s("enemizer.boss_shuffle", "none"),
                enemyShuffle = s("enemizer.enemy_shuffle", "none"),
                enemyDamage  = s("enemizer.enemy_damage", "default"),
                enemyHealth  = s("enemizer.enemy_health", "default"),
                potShuffle   = s("enemizer.pot_shuffle", "off"),
            ),
        )
    }

    /**
     * Generates a seed and returns [SeedResult].
     * Throws [IOException] or [IllegalStateException] on failure.
     * Call from a coroutine (not the main thread).
     *
     * Two-step flow (mirrors pyz3r):
     *   1. POST /api/randomizer  → seed hash + dict patches
     *   2. GET  /api/h/{hash}    → bpsLocation for the base BPS patch
     *   3. GET  bpsLocation      → download BPS (English translation + engine)
     */
    fun generate(settings: RandomizerSettings, onProgress: (String) -> Unit): SeedResult {
        onProgress("Contacting alttpr.com…")

        val body = json.encodeToString(settings).toRequestBody(JSON_MEDIA)
        val apiResponse: SeedApiResponse = http.newCall(
            Request.Builder().url("$BASE/api/randomizer").post(body).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("API error ${resp.code}: ${resp.message}")
            val raw = resp.body?.string() ?: throw IOException("Empty API response")
            json.decodeFromString(raw)
        }

        onProgress("Fetching patch metadata…")
        val hashInfo: HashInfo = http.newCall(
            Request.Builder().url("$BASE/api/h/${apiResponse.hash}").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful)
                throw IOException("Failed to fetch patch metadata: ${resp.code}")
            val raw = resp.body?.string() ?: throw IOException("Empty metadata response")
            json.decodeFromString(raw)
        }

        onProgress("Downloading base patch…")
        val bpsBytes = if (hashInfo.bpsLocation.isNotBlank()) {
            val bpsUrl = if (hashInfo.bpsLocation.startsWith("http")) hashInfo.bpsLocation
                         else "$BASE${hashInfo.bpsLocation}"
            http.newCall(Request.Builder().url(bpsUrl).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful)
                        throw IOException("Failed to download BPS patch: ${resp.code}")
                    resp.body?.bytes() ?: throw IOException("Empty BPS response")
                }
        } else ByteArray(0)

        return SeedResult(
            hash        = apiResponse.hash,
            permalink   = "$BASE/h/${apiResponse.hash}",
            bpsBytes    = bpsBytes,
            dictPatches = apiResponse.patch,
            sizeMb      = apiResponse.size,
        )
    }
}
