using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using LTTPRandomizerGenerator.Models;

namespace LTTPRandomizerGenerator.Services
{
    /// <summary>
    /// Communicates with the alttpr.com API to generate a randomizer seed
    /// and retrieve the patch data needed to produce the output ROM.
    /// </summary>
    public static class AlttprApiClient
    {
        private static readonly HttpClient Http = new()
        {
            BaseAddress = new Uri("https://alttpr.com"),
            Timeout = TimeSpan.FromSeconds(60),
        };

        static AlttprApiClient()
        {
            Http.DefaultRequestHeaders.Add("User-Agent", "LTTPRandomizerGenerator/0.1");
        }

        /// <summary>
        /// Posts settings to the API and returns the seed result.
        /// Follows pyz3r's two-step flow: POST /api/randomizer for seed data,
        /// then GET /api/h/{hash} to obtain bpsLocation for the base BPS patch.
        /// The BPS patch contains the English translation and randomizer engine;
        /// without it, dict patches apply to the raw Japanese ROM (garbled text).
        /// </summary>
        public static async Task<SeedResult?> GenerateAsync(
            RandomizerSettings settings,
            IProgress<string>? progress = null,
            CancellationToken ct = default)
        {
            // Step 1 — generate the seed
            progress?.Report("Contacting alttpr.com...");
            HttpResponseMessage response;
            try
            {
                response = await Http.PostAsJsonAsync("/api/randomizer", settings, ct);
            }
            catch (HttpRequestException ex)
            {
                throw new InvalidOperationException($"Network error: {ex.Message}", ex);
            }
            catch (TaskCanceledException)
            {
                throw new InvalidOperationException("Request timed out. Check your internet connection.");
            }

            if (!response.IsSuccessStatusCode)
            {
                string body = await response.Content.ReadAsStringAsync(ct);
                throw new InvalidOperationException(
                    $"API returned {(int)response.StatusCode}: {response.ReasonPhrase}\n{body}");
            }

            progress?.Report("Parsing seed data...");
            var apiResponse = await response.Content.ReadFromJsonAsync<ApiResponse>(ct);
            if (apiResponse is null)
                throw new InvalidOperationException("API returned empty response.");

            // Step 2 — fetch bpsLocation from /api/h/{hash} (mirrors pyz3r's get_patch_base())
            progress?.Report("Fetching patch metadata...");
            HashInfo? hashInfo;
            try
            {
                hashInfo = await Http.GetFromJsonAsync<HashInfo>($"/api/h/{apiResponse.Hash}", ct);
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException($"Failed to fetch patch metadata: {ex.Message}", ex);
            }

            // Step 3 — download the BPS base patch (English translation + randomizer engine)
            byte[] bpsBytes = Array.Empty<byte>();
            string? bpsLocation = hashInfo?.BpsLocation;
            if (!string.IsNullOrWhiteSpace(bpsLocation))
            {
                progress?.Report("Downloading base patch...");
                try
                {
                    bpsBytes = await Http.GetByteArrayAsync(bpsLocation, ct);
                }
                catch (Exception ex)
                {
                    throw new InvalidOperationException($"Failed to download base patch: {ex.Message}", ex);
                }
            }

            return new SeedResult
            {
                Hash = apiResponse.Hash,
                Permalink = $"https://alttpr.com/h/{apiResponse.Hash}",
                BpsPatchBytes = bpsBytes,
                DictionaryPatches = apiResponse.Patch,
                RomSizeMb = apiResponse.Size,
            };
        }

        /// <summary>
        /// Extracts a seed hash from user input (full URL or raw hash).
        /// Returns null if the input is empty or clearly invalid.
        /// </summary>
        public static string? ParseSeedHash(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return null;
            input = input.Trim();

            // Match /h/{hash} in a URL
            var match = Regex.Match(input, @"/h/([A-Za-z0-9]+)");
            if (match.Success)
                return match.Groups[1].Value;

            // Treat as raw hash if alphanumeric and reasonable length
            if (Regex.IsMatch(input, @"^[A-Za-z0-9]{5,20}$"))
                return input;

            return null;
        }

        /// <summary>
        /// Fetches an existing seed by hash from alttpr.com.
        /// Uses GET /hash/{hash} which returns patch data, size, and spoiler settings.
        /// Then fetches the BPS base patch via GET /api/h/{hash}.
        /// </summary>
        public static async Task<FetchedSeed?> FetchSeedAsync(
            string hash,
            IProgress<string>? progress = null,
            CancellationToken ct = default)
        {
            // Step 1 — retrieve seed data from /hash/{hash}
            // Note: this endpoint returns content-type text/html despite being JSON,
            // so we read as string and deserialize manually.
            progress?.Report("Fetching seed data...");
            SeedHashResponse? seedData;
            try
            {
                var response = await Http.GetAsync($"/hash/{hash}", ct);
                if (!response.IsSuccessStatusCode)
                    throw new InvalidOperationException($"Seed not found ({(int)response.StatusCode}).");
                string json = await response.Content.ReadAsStringAsync(ct);
                seedData = JsonSerializer.Deserialize<SeedHashResponse>(json, JsonOptions);
            }
            catch (InvalidOperationException) { throw; }
            catch (HttpRequestException ex)
            {
                throw new InvalidOperationException($"Network error: {ex.Message}", ex);
            }
            catch (TaskCanceledException)
            {
                throw new InvalidOperationException("Request timed out. Check your internet connection.");
            }

            if (seedData is null)
                throw new InvalidOperationException("Seed not found or API returned empty response.");

            // Step 2 — fetch bpsLocation from /api/h/{hash}
            progress?.Report("Fetching patch metadata...");
            HashInfo? hashInfo;
            try
            {
                hashInfo = await Http.GetFromJsonAsync<HashInfo>($"/api/h/{hash}", ct);
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException($"Failed to fetch patch metadata: {ex.Message}", ex);
            }

            // Step 3 — download the BPS base patch
            byte[] bpsBytes = Array.Empty<byte>();
            string? bpsLocation = hashInfo?.BpsLocation;
            if (!string.IsNullOrWhiteSpace(bpsLocation))
            {
                progress?.Report("Downloading base patch...");
                try
                {
                    bpsBytes = await Http.GetByteArrayAsync(bpsLocation, ct);
                }
                catch (Exception ex)
                {
                    throw new InvalidOperationException($"Failed to download base patch: {ex.Message}", ex);
                }
            }

            // Parse settings from spoiler.meta if available
            RandomizerSettings? settings = null;
            if (seedData.Spoiler?.TryGetProperty("meta", out JsonElement meta) == true)
            {
                settings = ParseSettingsFromMeta(meta);
            }

            return new FetchedSeed
            {
                Seed = new SeedResult
                {
                    Hash = hash,
                    Permalink = $"https://alttpr.com/h/{hash}",
                    BpsPatchBytes = bpsBytes,
                    DictionaryPatches = seedData.Patch,
                    RomSizeMb = seedData.Size,
                },
                Settings = settings,
            };
        }

        private static RandomizerSettings ParseSettingsFromMeta(JsonElement meta)
        {
            string S(string key, string def) =>
                meta.TryGetProperty(key, out JsonElement v) ? v.GetString() ?? def : def;
            bool B(string key, bool def) =>
                meta.TryGetProperty(key, out JsonElement v) ? v.GetBoolean() : def;

            // The API meta uses "logic" (e.g., "NoGlitches") instead of "glitches" (e.g., "none").
            // Map logic values back to glitches API values.
            string logic = S("logic", "NoGlitches");
            string glitches = logic switch
            {
                "NoGlitches" => "none",
                "MinorGlitches" => "minor_glitches",
                "OverworldGlitches" => "overworld_glitches",
                "MajorGlitches" => "major_glitches",
                _ => "none",
            };

            return new RandomizerSettings
            {
                Glitches = glitches,
                ItemPlacement = S("item_placement", "basic"),
                DungeonItems = S("dungeon_items", "standard"),
                Accessibility = S("accessibility", "items"),
                Goal = S("goal", "ganon"),
                Crystals = new CrystalsSettings
                {
                    Tower = S("entry_crystals_tower", "7"),
                    Ganon = S("entry_crystals_ganon", "7"),
                },
                Mode = S("mode", "open"),
                Entrances = S("entrances", "none"),
                Hints = S("hints", "on"),
                Weapons = S("weapons", "randomized"),
                Item = new ItemSettings
                {
                    Pool = S("item_pool", "normal"),
                    Functionality = S("item_functionality", "normal"),
                },
                Spoilers = S("spoilers", "on"),
                Pseudoboots = B("pseudoboots", false),
                Enemizer = new EnemizerSettings
                {
                    BossShuffle = S("enemizer.boss_shuffle", "none"),
                    EnemyShuffle = S("enemizer.enemy_shuffle", "none"),
                    EnemyDamage = S("enemizer.enemy_damage", "default"),
                    EnemyHealth = S("enemizer.enemy_health", "default"),
                    PotShuffle = S("enemizer.pot_shuffle", "off"),
                },
            };
        }

        // ── JSON options for /hash/ endpoint (content-type may be text/html) ──

        private static readonly JsonSerializerOptions JsonOptions = new()
        {
            PropertyNameCaseInsensitive = true,
        };

        // ── Internal deserialization types ────────────────────────────────────

        private class SeedHashResponse
        {
            [JsonPropertyName("hash")]
            public string Hash { get; set; } = string.Empty;

            [JsonPropertyName("patch")]
            public List<Dictionary<string, List<int>>> Patch { get; set; } = new();

            [JsonPropertyName("size")]
            public int Size { get; set; } = 2;

            [JsonPropertyName("spoiler")]
            public JsonElement? Spoiler { get; set; }
        }

        private class ApiResponse
        {
            [JsonPropertyName("hash")]
            public string Hash { get; set; } = string.Empty;

            [JsonPropertyName("patch")]
            public List<Dictionary<string, List<int>>> Patch { get; set; } = new();

            [JsonPropertyName("size")]
            public int Size { get; set; } = 2;
        }

        private class HashInfo
        {
            [JsonPropertyName("bpsLocation")]
            public string BpsLocation { get; set; } = string.Empty;

            [JsonPropertyName("md5")]
            public string Md5 { get; set; } = string.Empty;
        }
    }

    public class SeedResult
    {
        public string Hash { get; set; } = string.Empty;
        public string Permalink { get; set; } = string.Empty;
        public byte[] BpsPatchBytes { get; set; } = Array.Empty<byte>();
        public List<Dictionary<string, List<int>>> DictionaryPatches { get; set; } = new();
        public int RomSizeMb { get; set; } = 2;
    }

    /// <summary>
    /// Result from fetching an existing seed by hash.
    /// Contains the seed data plus the original settings (if available from spoiler).
    /// </summary>
    public class FetchedSeed
    {
        public SeedResult Seed { get; set; } = new();
        public RandomizerSettings? Settings { get; set; }
    }
}
