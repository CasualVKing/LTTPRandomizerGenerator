using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using LTTPRandomizerGenerator.Models;

namespace LTTPRandomizerGenerator.Services;

public static partial class MsuOriginalSoundtrack
{
    private static readonly string CacheDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "LTTPRandomizerGenerator", "OriginalAudio");

    private static readonly HashSet<string> SupportedExtensions =
        new(StringComparer.OrdinalIgnoreCase) { ".mp3", ".wav", ".wma", ".aac", ".m4a", ".aiff", ".aif", ".flac" };

    private static readonly Dictionary<string, int> OstAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["title"]                    = 1,
        ["link to the past"]         = 1,
        ["beginning of the journey"] = 6,
        ["seal of seven maidens"]    = 3,
        ["time of the falling rain"] = 3,
        ["majestic castle"]          = 16,
        ["princess zeldas rescue"]   = 25,
        ["safety in the sanctuary"]  = 20,
        ["hyrule field main theme"]  = 2,
        ["hyrule field"]             = 2,
        ["kakariko village"]         = 7,
        ["guessing game house"]      = 14,
        ["fortune teller"]           = 23,
        ["soldiers of kakariko"]     = 12,
        ["dank dungeons"]            = 17,
        ["lost ancient ruins"]       = 18,
        ["anger of the guardians"]   = 21,
        ["great victory"]            = 19,
        ["silly pink rabbit"]        = 4,
        ["forest of mystery"]        = 5,
        ["unsealing the master sword"] = 10,
        ["priest of the dark order"] = 28,
        ["dark golden land"]         = 9,
        ["black mist"]               = 13,
        ["dungeon of shadows"]       = 22,
        ["meeting the maidens"]      = 26,
        ["goddess appears"]          = 27,
        ["release of ganon"]         = 29,
        ["ganons message"]           = 30,
        ["prince of darkness"]       = 31,
        ["power of the gods"]        = 32,
        ["epilogue"]                 = 33,
        ["beautiful hyrule"]         = 33,
        ["staff roll"]               = 34,
        ["credits"]                  = 34,
        ["overworld"]                = 2,
        ["dark world theme"]         = 9,
        ["file select"]              = 11,
        ["game over"]                = 11,
        ["sanctuary"]                = 20,
        ["lost woods"]               = 5,
        ["kakariko"]                 = 7,
        ["ganon battle"]             = 31,
        ["ganon fight"]              = 31,
        ["triforce"]                 = 32,
        ["fairy fountain"]           = 27,
        ["boss battle"]              = 21,
        ["boss victory"]             = 19,
        ["cave"]                     = 18,
        ["shop"]                     = 23,
        ["minigame"]                 = 14,
        ["skull woods"]              = 15,
        ["dimensional shift"]        = 8,
        ["teleport"]                 = 8,
    };

    public static void LoadCachedOriginals(IReadOnlyList<MsuTrackSlot> tracks)
    {
        if (!Directory.Exists(CacheDir)) return;

        foreach (var track in tracks)
        {
            string cached = Path.Combine(CacheDir, $"{track.SlotNumber:D2}.pcm");
            if (File.Exists(cached))
                track.OriginalPcmPath = cached;
        }
    }

    public static void ClearCache(IReadOnlyList<MsuTrackSlot> tracks)
    {
        foreach (var track in tracks)
            track.OriginalPcmPath = null;

        if (!Directory.Exists(CacheDir)) return;
        try { Directory.Delete(CacheDir, true); } catch { }
    }

    public static async Task<string?> ImportFromFolderAsync(
        string folderPath,
        IReadOnlyList<MsuTrackSlot> tracks,
        IProgress<(int current, int total, string trackName)>? progress = null,
        CancellationToken ct = default)
    {
        if (!Directory.Exists(folderPath))
            return "Folder does not exist.";

        var audioFiles = Directory.GetFiles(folderPath)
            .Where(f => SupportedExtensions.Contains(Path.GetExtension(f)))
            .ToList();

        if (audioFiles.Count == 0)
            return "No supported audio files found. Supported: MP3, WAV, WMA, AAC, M4A, AIFF, FLAC.";

        var matches = MatchFilesToSlots(audioFiles, tracks);
        if (matches.Count == 0)
            return "Could not match any files to track slots. Files should be numbered (e.g., '01 - Opening.mp3') or named to match track names.";

        return await ConvertAndCacheAsync(matches, tracks, progress, ct);
    }

    public static async Task<string?> ImportFromZipAsync(
        string zipPath,
        IReadOnlyList<MsuTrackSlot> tracks,
        IProgress<(int current, int total, string trackName)>? progress = null,
        CancellationToken ct = default)
    {
        if (!File.Exists(zipPath))
            return "ZIP file does not exist.";

        string tempDir = Path.Combine(Path.GetTempPath(), "LTTPOriginalImport_" + Guid.NewGuid().ToString("N")[..8]);

        try
        {
            Directory.CreateDirectory(tempDir);
            await Task.Run(() => ZipFile.ExtractToDirectory(zipPath, tempDir), ct);

            var audioFiles = Directory.GetFiles(tempDir, "*.*", SearchOption.AllDirectories)
                .Where(f => SupportedExtensions.Contains(Path.GetExtension(f)))
                .ToList();

            if (audioFiles.Count == 0)
                return "No supported audio files found in ZIP.";

            var matches = MatchFilesToSlots(audioFiles, tracks);
            if (matches.Count == 0)
                return "Could not match any files to track slots.";

            return await ConvertAndCacheAsync(matches, tracks, progress, ct);
        }
        finally
        {
            try { Directory.Delete(tempDir, true); } catch { }
        }
    }

    public static async Task<string?> ImportFromUrlAsync(
        string url,
        IReadOnlyList<MsuTrackSlot> tracks,
        HttpClient http,
        IProgress<(int current, int total, string trackName)>? progress = null,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(url))
            return "URL cannot be empty.";

        string tempZip = Path.Combine(Path.GetTempPath(), "LTTPOriginal_" + Guid.NewGuid().ToString("N")[..8] + ".zip");

        try
        {
            progress?.Report((0, 1, "Downloading…"));

            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
            if (!response.IsSuccessStatusCode)
                return $"Download failed: HTTP {(int)response.StatusCode} {response.ReasonPhrase}";

            await using (var stream = await response.Content.ReadAsStreamAsync(ct))
            await using (var file = File.Create(tempZip))
            {
                await stream.CopyToAsync(file, ct);
            }

            return await ImportFromZipAsync(tempZip, tracks, progress, ct);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            return $"Download failed: {ex.Message}";
        }
        finally
        {
            try { File.Delete(tempZip); } catch { }
        }
    }

    public static Dictionary<int, string> MatchFilesToSlots(
        IReadOnlyList<string> filePaths,
        IReadOnlyList<MsuTrackSlot> tracks)
    {
        var result = new Dictionary<int, string>();
        var slotLookup = tracks.ToDictionary(t => t.SlotNumber);
        var usedSlots = new HashSet<int>();

        // Pass 1: OST alias table
        foreach (var filePath in filePaths)
        {
            string fileName = Path.GetFileNameWithoutExtension(filePath);
            string cleaned = LeadingNumberRegex().Replace(fileName, "")
                .Replace('_', ' ').Replace('-', ' ').Replace('~', ' ')
                .Replace(".", " ").Replace("'", "").Replace("\u2019", "").Trim();

            foreach (var (alias, slot) in OstAliases)
            {
                if (usedSlots.Contains(slot)) continue;
                if (!slotLookup.ContainsKey(slot)) continue;

                if (cleaned.Contains(alias, StringComparison.OrdinalIgnoreCase))
                {
                    result[slot] = filePath;
                    usedSlots.Add(slot);
                    break;
                }
            }
        }

        // Pass 2: leading number
        foreach (var filePath in filePaths)
        {
            if (result.ContainsValue(filePath)) continue;

            string fileName = Path.GetFileNameWithoutExtension(filePath);
            var match = LeadingNumberRegex().Match(fileName);
            if (match.Success && int.TryParse(match.Value, out int slot) && slot >= 1 && slot <= 61 && slotLookup.ContainsKey(slot))
            {
                if (usedSlots.Add(slot))
                    result[slot] = filePath;
            }
        }

        // Pass 3: any number
        foreach (var filePath in filePaths)
        {
            if (result.ContainsValue(filePath)) continue;

            string fileName = Path.GetFileNameWithoutExtension(filePath);
            var matches = AnyNumberRegex().Matches(fileName);
            foreach (Match m in matches)
            {
                if (int.TryParse(m.Value, out int slot) && slot >= 1 && slot <= 61 && slotLookup.ContainsKey(slot) && usedSlots.Add(slot))
                {
                    result[slot] = filePath;
                    break;
                }
            }
        }

        // Pass 4: fuzzy name match
        foreach (var filePath in filePaths)
        {
            if (result.ContainsValue(filePath)) continue;

            string fileName = Path.GetFileNameWithoutExtension(filePath)
                .Replace('_', ' ').Replace('-', ' ').Trim();

            foreach (var track in tracks)
            {
                if (usedSlots.Contains(track.SlotNumber)) continue;

                if (fileName.Contains(track.Name, StringComparison.OrdinalIgnoreCase) ||
                    track.Name.Contains(fileName, StringComparison.OrdinalIgnoreCase))
                {
                    result[track.SlotNumber] = filePath;
                    usedSlots.Add(track.SlotNumber);
                    break;
                }
            }
        }

        return result;
    }

    private static async Task<string?> ConvertAndCacheAsync(
        Dictionary<int, string> matches,
        IReadOnlyList<MsuTrackSlot> tracks,
        IProgress<(int current, int total, string trackName)>? progress,
        CancellationToken ct)
    {
        Directory.CreateDirectory(CacheDir);

        var slotLookup = tracks.ToDictionary(t => t.SlotNumber);
        var sorted = matches.OrderBy(kv => kv.Key).ToList();
        int completed = 0;
        int failed = 0;

        foreach (var (slot, sourcePath) in sorted)
        {
            ct.ThrowIfCancellationRequested();

            string trackName = slotLookup.TryGetValue(slot, out var ts) ? ts.Name : $"Track {slot}";
            progress?.Report((completed + 1, sorted.Count, trackName));

            string destPath = Path.Combine(CacheDir, $"{slot:D2}.pcm");

            if (!File.Exists(destPath))
            {
                string? error = await MsuPcmConverter.ConvertAsync(sourcePath, destPath, ct: ct);
                if (error is not null)
                {
                    failed++;
                    completed++;
                    continue;
                }
            }

            if (slotLookup.TryGetValue(slot, out var trackSlot))
                trackSlot.OriginalPcmPath = destPath;

            completed++;
        }

        if (failed > 0 && failed == sorted.Count)
            return "All conversions failed. Ensure the files are valid audio files.";

        if (failed > 0)
            return $"{sorted.Count - failed} of {sorted.Count} tracks imported. {failed} file(s) failed to convert.";

        return null;
    }

    [GeneratedRegex(@"^\d+")]
    private static partial Regex LeadingNumberRegex();

    [GeneratedRegex(@"\d+")]
    private static partial Regex AnyNumberRegex();
}
