using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using LTTPRandomizerGenerator.Models;

namespace LTTPRandomizerGenerator.Services;

public static class MsuPlaylistManager
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public static (MsuPlaylist? playlist, string? error) Load(string filePath)
    {
        try
        {
            string json = File.ReadAllText(filePath);
            var playlist = JsonSerializer.Deserialize<MsuPlaylist>(json, JsonOptions);
            if (playlist is null)
                return (null, "Playlist file is empty or malformed.");
            if (playlist.Version != 1)
                return (null, $"Unsupported playlist version: {playlist.Version}");
            return (playlist, null);
        }
        catch (Exception ex)
        {
            return (null, $"Failed to load playlist: {ex.Message}");
        }
    }

    public static string? Save(string filePath, MsuPlaylist playlist)
    {
        try
        {
            playlist.LastModified = DateTime.UtcNow.ToString("o");
            var normalizedTracks = playlist.Tracks
                .ToDictionary(kv => kv.Key, kv => kv.Value.Replace('\\', '/'));
            playlist.Tracks = normalizedTracks;

            string json = JsonSerializer.Serialize(playlist, JsonOptions);
            Directory.CreateDirectory(Path.GetDirectoryName(filePath)!);
            File.WriteAllText(filePath, json);
            return null;
        }
        catch (Exception ex)
        {
            return $"Failed to save playlist: {ex.Message}";
        }
    }
}
