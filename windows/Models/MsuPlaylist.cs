using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace LTTPRandomizerGenerator.Models;

public class MsuPlaylist
{
    [JsonPropertyName("version")]
    public int Version { get; set; } = 1;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("tracks")]
    public Dictionary<string, string> Tracks { get; set; } = new();

    [JsonPropertyName("lastModified")]
    public string LastModified { get; set; } = string.Empty;
}
