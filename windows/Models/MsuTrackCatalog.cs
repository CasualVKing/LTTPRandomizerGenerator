using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;

namespace LTTPRandomizerGenerator.Models;

public static class MsuTrackCatalog
{
    private record CatalogEntry(int Slot, string Name, string Type);

    public static List<MsuTrackSlot> Load()
    {
        var assembly = Assembly.GetExecutingAssembly();
        var resourceName = assembly.GetManifestResourceNames()
            .First(n => n.EndsWith("trackCatalog.json", StringComparison.OrdinalIgnoreCase));

        using var stream = assembly.GetManifestResourceStream(resourceName)!;
        using var reader = new StreamReader(stream);
        var json = reader.ReadToEnd();

        var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
        var entries = JsonSerializer.Deserialize<List<CatalogEntry>>(json, options)!;

        return entries.Select(e => new MsuTrackSlot
        {
            SlotNumber = e.Slot,
            Name = e.Name,
            TrackType = e.Type
        }).ToList();
    }
}
