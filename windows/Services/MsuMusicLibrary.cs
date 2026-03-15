using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using LTTPRandomizerGenerator.Models;

namespace LTTPRandomizerGenerator.Services;

public class MsuMusicLibrary
{
    private static readonly HashSet<string> SupportedExts =
        new(StringComparer.OrdinalIgnoreCase)
        { ".pcm", ".mp3", ".wav", ".wma", ".wmv", ".aac", ".m4a", ".mp4", ".aiff", ".aif" };

    public string? LibraryFolder { get; private set; }
    public IReadOnlyList<MsuLibraryEntry> Entries { get; private set; } = Array.Empty<MsuLibraryEntry>();

    public void SetFolder(string? folder)
    {
        LibraryFolder = string.IsNullOrWhiteSpace(folder) ? null : folder;
        Refresh();
    }

    public void Refresh()
    {
        if (LibraryFolder is null || !Directory.Exists(LibraryFolder))
        {
            Entries = Array.Empty<MsuLibraryEntry>();
            return;
        }

        var entries = new List<MsuLibraryEntry>();

        foreach (var file in Directory.EnumerateFiles(LibraryFolder)
                     .Where(f => SupportedExts.Contains(Path.GetExtension(f)))
                     .OrderBy(f => Path.GetFileNameWithoutExtension(f), StringComparer.OrdinalIgnoreCase))
        {
            string name = Path.GetFileNameWithoutExtension(file);
            string ext  = Path.GetExtension(file).ToLowerInvariant();

            string? cachedPcm = null;
            if (ext == ".pcm")
            {
                cachedPcm = file;
            }
            else
            {
                string cachePath = GetCacheTargetPath(file);
                if (File.Exists(cachePath)
                    && File.GetLastWriteTimeUtc(cachePath) >= File.GetLastWriteTimeUtc(file))
                {
                    cachedPcm = cachePath;
                }
            }

            entries.Add(new MsuLibraryEntry
            {
                Name         = name,
                SourcePath   = file,
                CachedPcmPath = cachedPcm
            });
        }

        Entries = entries;
    }

    public string GetCacheTargetPath(string sourcePath) =>
        Path.Combine(LibraryFolder!, "_cache",
            Path.GetFileNameWithoutExtension(sourcePath) + ".pcm");
}
