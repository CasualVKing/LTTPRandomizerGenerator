using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace LTTPRandomizerGenerator.Services
{
    public class MsuSettings
    {
        public bool IncludeMsu { get; set; }
        public string PackName { get; set; } = string.Empty;
        public Dictionary<string, string> Tracks { get; set; } = new();
    }

    public static class MsuSettingsManager
    {
        private static readonly string DataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "LTTPRandomizerGenerator");

        private static readonly string SettingsFile = Path.Combine(DataDir, "msu_settings.json");

        private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

        public static MsuSettings Load()
        {
            if (!File.Exists(SettingsFile)) return new();
            try
            {
                string json = File.ReadAllText(SettingsFile);
                return JsonSerializer.Deserialize<MsuSettings>(json) ?? new();
            }
            catch { return new(); }
        }

        public static void Save(MsuSettings s)
        {
            try
            {
                Directory.CreateDirectory(DataDir);
                File.WriteAllText(SettingsFile, JsonSerializer.Serialize(s, JsonOpts));
            }
            catch { /* non-fatal */ }
        }
    }
}
