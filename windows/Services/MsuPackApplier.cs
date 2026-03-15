using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace LTTPRandomizerGenerator.Services;

public static class MsuPackApplier
{
    public static string? Apply(string romPath, Dictionary<string, string> tracks)
    {
        try
        {
            var baseName = Path.Combine(
                Path.GetDirectoryName(romPath)!,
                Path.GetFileNameWithoutExtension(romPath));

            // Write empty .msu marker file
            File.WriteAllBytes(baseName + ".msu", Array.Empty<byte>());

            // Copy each assigned PCM alongside the ROM
            foreach (var (slot, pcmPath) in tracks.OrderBy(t => int.Parse(t.Key)))
            {
                if (!File.Exists(pcmPath))
                    return $"PCM file for slot {slot} not found: {pcmPath}";

                File.Copy(pcmPath, $"{baseName}-{slot}.pcm", overwrite: true);
            }

            return null;
        }
        catch (Exception ex)
        {
            return $"MSU apply failed: {ex.Message}";
        }
    }
}
