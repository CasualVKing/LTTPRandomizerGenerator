package com.lttprandomizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CrystalsSettings(
    @SerialName("tower") val tower: String = "7",
    @SerialName("ganon") val ganon: String = "7",
)

@Serializable
data class ItemSettings(
    @SerialName("pool")          val pool: String          = "normal",
    @SerialName("functionality") val functionality: String = "normal",
)

@Serializable
data class EnemizerSettings(
    @SerialName("boss_shuffle")  val bossShuffle: String  = "none",
    @SerialName("enemy_shuffle") val enemyShuffle: String = "none",
    @SerialName("enemy_damage")  val enemyDamage: String  = "default",
    @SerialName("enemy_health")  val enemyHealth: String  = "default",
    @SerialName("pot_shuffle")   val potShuffle: String   = "off",
)

@Serializable
data class RandomizerSettings(
    @SerialName("glitches")       val glitches: String           = "none",
    @SerialName("item_placement") val itemPlacement: String      = "basic",
    @SerialName("dungeon_items")  val dungeonItems: String       = "standard",
    @SerialName("accessibility")  val accessibility: String      = "items",
    @SerialName("goal")           val goal: String               = "ganon",
    @SerialName("crystals")       val crystals: CrystalsSettings = CrystalsSettings(),
    @SerialName("mode")           val mode: String               = "open",
    @SerialName("entrances")      val entrances: String          = "none",
    @SerialName("hints")          val hints: String              = "on",
    @SerialName("weapons")        val weapons: String            = "randomized",
    @SerialName("item")           val item: ItemSettings         = ItemSettings(),
    @SerialName("spoilers")       val spoilers: String           = "on",
    @SerialName("pseudoboots")    val pseudoboots: Boolean       = false,
    @SerialName("enemizer")       val enemizer: EnemizerSettings = EnemizerSettings(),
    @SerialName("lang")           val lang: String               = "en",
)

// ── API response model ────────────────────────────────────────────────────────

@Serializable
data class SeedApiResponse(
    @SerialName("hash")  val hash: String,
    @SerialName("patch") val patch: List<Map<String, List<Int>>> = emptyList(),
    @SerialName("size")  val size: Int = 2,
)

// ── Preset ────────────────────────────────────────────────────────────────────

@Serializable
data class RandomizerPreset(
    val name: String,
    val settings: RandomizerSettings,
)

// ── Built-in presets ──────────────────────────────────────────────────────────

object BuiltInPresets {
    val all = listOf(
        RandomizerPreset("Vanilla", RandomizerSettings(
            mode = "standard",
            weapons = "vanilla",
        )),
        RandomizerPreset("Quick Run", RandomizerSettings(
            goal = "fast_ganon", itemPlacement = "basic",
        )),
        RandomizerPreset("Casual Boots", RandomizerSettings(
            itemPlacement = "basic", pseudoboots = true,
        )),
        RandomizerPreset("Keysanity", RandomizerSettings(
            itemPlacement = "advanced", dungeonItems = "full",
        )),
        RandomizerPreset("All Mix", RandomizerSettings(
            itemPlacement = "advanced", dungeonItems = "full",
            entrances = "crossed",
            enemizer = EnemizerSettings(bossShuffle = "full", enemyShuffle = "shuffled"),
        )),
        RandomizerPreset("Beginner", RandomizerSettings(
            itemPlacement = "basic", accessibility = "locations",
        )),
        RandomizerPreset("Swordless", RandomizerSettings(
            weapons = "swordless", itemPlacement = "advanced",
        )),
    )
}

val CUSTOM_SENTINEL = RandomizerPreset("Custom", RandomizerSettings())

// ── Dropdown option ────────────────────────────────────────────────────────────

data class DropdownOption(val display: String, val apiValue: String) {
    override fun toString() = display
}

object SettingsOptions {
    val glitches = listOf(
        DropdownOption("None",                "none"),
        DropdownOption("Minor Glitches",      "minor_glitches"),
        DropdownOption("Overworld Glitches",  "overworld_glitches"),
        DropdownOption("Major Glitches",      "major_glitches"),
    )
    val itemPlacement = listOf(
        DropdownOption("Basic",    "basic"),
        DropdownOption("Advanced", "advanced"),
    )
    val dungeonItems = listOf(
        DropdownOption("Standard",                      "standard"),
        DropdownOption("Maps & Compasses",              "mc"),
        DropdownOption("Maps, Compasses & Small Keys",  "mcs"),
        DropdownOption("Keysanity (full)",              "full"),
    )
    val accessibility = listOf(
        DropdownOption("Items",     "items"),
        DropdownOption("Locations", "locations"),
        DropdownOption("None",      "none"),
    )
    val goal = listOf(
        DropdownOption("Defeat Ganon", "ganon"),
        DropdownOption("Fast Ganon",   "fast_ganon"),
        DropdownOption("All Dungeons", "dungeons"),
        DropdownOption("Pedestal",     "pedestal"),
    )
    val crystalCount = (0..7).map { DropdownOption(it.toString(), it.toString()) }
    val worldState = listOf(
        DropdownOption("Open",     "open"),
        DropdownOption("Standard", "standard"),
        DropdownOption("Inverted", "inverted"),
        DropdownOption("Retro",    "retro"),
    )
    val entranceShuffle = listOf(
        DropdownOption("None",       "none"),
        DropdownOption("Simple",     "simple"),
        DropdownOption("Restricted", "restricted"),
        DropdownOption("Full",       "full"),
        DropdownOption("Crossed",    "crossed"),
        DropdownOption("Insanity",   "insanity"),
    )
    val bossShuffle = listOf(
        DropdownOption("None",   "none"),
        DropdownOption("Simple", "simple"),
        DropdownOption("Full",   "full"),
        DropdownOption("Random", "random"),
    )
    val enemyShuffle = listOf(
        DropdownOption("None",     "none"),
        DropdownOption("Shuffled", "shuffled"),
        DropdownOption("Random",   "random"),
    )
    val enemyDamage = listOf(
        DropdownOption("Default", "default"),
        DropdownOption("Half",    "half"),
        DropdownOption("Double",  "double"),
        DropdownOption("Quad",    "quad"),
    )
    val enemyHealth = listOf(
        DropdownOption("Default", "default"),
        DropdownOption("Easy",    "easy"),
        DropdownOption("Hard",    "hard"),
        DropdownOption("Expert",  "expert"),
    )
    val potShuffle = listOf(
        DropdownOption("Off", "off"),
        DropdownOption("On",  "on"),
    )
    val hints = listOf(DropdownOption("On", "on"), DropdownOption("Off", "off"))
    val weapons = listOf(
        DropdownOption("Randomized", "randomized"),
        DropdownOption("Assured",    "assured"),
        DropdownOption("Vanilla",    "vanilla"),
        DropdownOption("Swordless",  "swordless"),
    )
    val itemPool = listOf(
        DropdownOption("Normal",        "normal"),
        DropdownOption("Hard",          "hard"),
        DropdownOption("Expert",        "expert"),
        DropdownOption("Crowd Control", "crowd_control"),
    )
    val itemFunctionality = listOf(
        DropdownOption("Normal", "normal"),
        DropdownOption("Hard",   "hard"),
        DropdownOption("Expert", "expert"),
    )
    val spoilers = listOf(
        DropdownOption("On",         "on"),
        DropdownOption("Off",        "off"),
        DropdownOption("Exclusions", "generate"),
    )
    val pegasusBoots = listOf(
        DropdownOption("Off (normal start)", "off"),
        DropdownOption("Start with Boots",   "on"),
    )
}
