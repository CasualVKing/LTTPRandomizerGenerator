using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace LTTPRandomizerGenerator.Models
{
    public class RandomizerPreset
    {
        [JsonPropertyName("name")]
        public string Name { get; set; } = string.Empty;

        [JsonPropertyName("settings")]
        public RandomizerSettings Settings { get; set; } = new();

        /// <summary>
        /// True for built-in presets that cannot be deleted by the user.
        /// Not persisted to JSON — hardcoded presets are never saved to file.
        /// </summary>
        [JsonIgnore]
        public bool IsBuiltIn { get; set; }

        /// <summary>
        /// True only for the "Custom" sentinel entry at the end of the preset list.
        /// </summary>
        [JsonIgnore]
        public bool IsCustomSentinel { get; set; }

        public static readonly RandomizerPreset CustomSentinel = new()
        {
            Name = "Custom",
            IsCustomSentinel = true,
        };

        public override string ToString() => Name;
    }

    public static class BuiltInPresets
    {
        public static IReadOnlyList<RandomizerPreset> All { get; } = new List<RandomizerPreset>
        {
            new()
            {
                Name = "Vanilla",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "basic",
                    DungeonItems = "standard",
                    Accessibility = "items",
                    Mode = "standard",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "vanilla",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
            new()
            {
                Name = "Quick Run",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "fast_ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "basic",
                    DungeonItems = "standard",
                    Accessibility = "items",
                    Mode = "open",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "randomized",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
            new()
            {
                Name = "Casual Boots",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "basic",
                    DungeonItems = "standard",
                    Accessibility = "items",
                    Mode = "open",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "randomized",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                    Pseudoboots = true,
                }
            },
            new()
            {
                Name = "Keysanity",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "advanced",
                    DungeonItems = "full",
                    Accessibility = "items",
                    Mode = "open",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "randomized",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
            new()
            {
                Name = "All Mix",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "advanced",
                    DungeonItems = "full",
                    Accessibility = "items",
                    Mode = "open",
                    Entrances = "crossed",
                    Enemizer = new() { BossShuffle = "full", EnemyShuffle = "shuffled" },
                    Hints = "on",
                    Weapons = "randomized",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
            new()
            {
                Name = "Beginner",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "basic",
                    DungeonItems = "standard",
                    Accessibility = "locations",
                    Mode = "open",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "randomized",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
            new()
            {
                Name = "Swordless",
                IsBuiltIn = true,
                Settings = new()
                {
                    Goal = "ganon",
                    Crystals = new() { Tower = "7", Ganon = "7" },
                    ItemPlacement = "advanced",
                    DungeonItems = "standard",
                    Accessibility = "items",
                    Mode = "open",
                    Entrances = "none",
                    Enemizer = new() { BossShuffle = "none", EnemyShuffle = "none" },
                    Hints = "on",
                    Weapons = "swordless",
                    Item = new() { Pool = "normal", Functionality = "normal" },
                    Spoilers = "on",
                }
            },
        };
    }
}
