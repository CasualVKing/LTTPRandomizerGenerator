using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text.Json;
using LTTPRandomizerGenerator.Models;

namespace LTTPRandomizerGenerator.Services;

public record MsuBundleExportResult(int TracksWritten, int TracksSkipped);

public static class MsuPackImporter
{
    private static readonly string LibraryFolder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "LTTPRandomizerGenerator", "MsuLibrary");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public static (MsuPlaylist? playlist, string? error) Import(string zipPath)
    {
        try
        {
            using var zip = ZipFile.OpenRead(zipPath);

            var manifestEntry = zip.GetEntry("manifest.json");
            if (manifestEntry is null)
                return (null, "Invalid pack: manifest.json not found.");

            PackManifest manifest;
            using (var ms = manifestEntry.Open())
            using (var reader = new StreamReader(ms))
            {
                var json = reader.ReadToEnd();
                var parsed = JsonSerializer.Deserialize<PackManifest>(json, JsonOptions);
                if (parsed is null)
                    return (null, "Invalid pack: manifest.json could not be read.");
                manifest = parsed;
            }

            if (manifest.Version != 1)
                return (null, $"Unsupported pack version: {manifest.Version}");

            if (string.IsNullOrWhiteSpace(manifest.Name))
                manifest.Name = Path.GetFileNameWithoutExtension(zipPath);

            string safePackName = string.Concat(manifest.Name.Split(Path.GetInvalidFileNameChars())).Trim();
            if (string.IsNullOrEmpty(safePackName))
                safePackName = "imported-pack";

            string destFolder = Path.Combine(LibraryFolder, "Imported", safePackName);
            Directory.CreateDirectory(destFolder);

            var tracks = new Dictionary<string, string>();

            foreach (var (slotKey, entryName) in manifest.Tracks)
            {
                var zipEntry = zip.GetEntry(entryName);
                if (zipEntry is null) continue;

                string destFileName = Path.GetFileName(entryName);
                string destPath = Path.Combine(destFolder, destFileName);

                // Guard against path traversal
                string fullDest = Path.GetFullPath(destPath);
                string fullFolder = Path.GetFullPath(destFolder) + Path.DirectorySeparatorChar;
                if (!fullDest.StartsWith(fullFolder, StringComparison.OrdinalIgnoreCase))
                    continue;

                if (!File.Exists(destPath))
                {
                    using var src = zipEntry.Open();
                    using var dst = File.Create(destPath);
                    src.CopyTo(dst);
                }

                tracks[slotKey] = destPath;
            }

            var playlist = new MsuPlaylist
            {
                Name = manifest.Name,
                Tracks = tracks
            };

            return (playlist, null);
        }
        catch (Exception ex)
        {
            return (null, $"Import failed: {ex.Message}");
        }
    }

    public static (MsuBundleExportResult? result, string? error) Export(string destZipPath, MsuPlaylist playlist)
    {
        int written = 0;
        int skipped = 0;

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(destZipPath)!);

            using var zip = ZipFile.Open(destZipPath, ZipArchiveMode.Create);
            var manifestTracks = new Dictionary<string, string>();

            foreach (var (slotKey, pcmPath) in playlist.Tracks)
            {
                if (!File.Exists(pcmPath)) { skipped++; continue; }
                if (!int.TryParse(slotKey, out int slot)) { skipped++; continue; }

                string entryName = $"tracks/{slot:D2}.pcm";
                var entry = zip.CreateEntry(entryName, CompressionLevel.NoCompression);
                using (var entryStream = entry.Open())
                using (var fileStream = File.OpenRead(pcmPath))
                    fileStream.CopyTo(entryStream);

                manifestTracks[slotKey] = entryName;
                written++;
            }

            var manifest = new PackManifest
            {
                Version = 1,
                Name = playlist.Name,
                Tracks = manifestTracks
            };

            var manifestJson = JsonSerializer.Serialize(manifest, JsonOptions);
            var manifestEntry = zip.CreateEntry("manifest.json", CompressionLevel.Optimal);
            using (var manifestStream = manifestEntry.Open())
            using (var writer = new StreamWriter(manifestStream))
                writer.Write(manifestJson);

            return (new MsuBundleExportResult(written, skipped), null);
        }
        catch (Exception ex)
        {
            try { if (File.Exists(destZipPath)) File.Delete(destZipPath); } catch { }
            return (null, $"Export failed: {ex.Message}");
        }
    }

    private class PackManifest
    {
        public int Version { get; set; } = 1;
        public string Name { get; set; } = string.Empty;
        public Dictionary<string, string> Tracks { get; set; } = new();
    }
}
