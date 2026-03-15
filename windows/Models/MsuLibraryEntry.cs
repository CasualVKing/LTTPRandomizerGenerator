using System.IO;

namespace LTTPRandomizerGenerator.Models;

public class MsuLibraryEntry
{
    private string _sourcePath = string.Empty;
    private string _ext = string.Empty;

    public string Name { get; set; } = string.Empty;

    public string SourcePath
    {
        get => _sourcePath;
        set { _sourcePath = value; _ext = Path.GetExtension(value).ToLowerInvariant(); }
    }

    public string? CachedPcmPath { get; set; }

    public bool   IsPcm          => _ext == ".pcm";
    public string FormatTag       => _ext.TrimStart('.').ToUpperInvariant();
    public string AssignablePath  => CachedPcmPath ?? _sourcePath;
    public bool   NeedsConversion => !IsPcm && CachedPcmPath is null;
    public bool   IsCached        => CachedPcmPath is not null;
    public bool   IsPlayable      => IsPcm || IsCached;
}
