using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;

namespace LTTPRandomizerGenerator.Models;

public class MsuTrackSlot : INotifyPropertyChanged
{
    private string? _pcmPath;
    private string? _validationError;
    private bool _isPlaying;
    private string? _originalPcmPath;
    private bool _isPlayingOriginal;

    public int SlotNumber { get; init; }
    public string SlotDisplay => SlotNumber.ToString("D2");
    public string Name { get; init; } = string.Empty;
    public string TrackType { get; init; } = "music";
    public string TypeLabel => TrackType switch
    {
        "jingle" => "[SFX]",
        "extended" => "[EXT]",
        _ => ""
    };
    public bool HasTypeLabel => TrackType != "music";

    public string? PcmPath
    {
        get => _pcmPath;
        set
        {
            if (_pcmPath != value)
            {
                _pcmPath = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(FileName));
                OnPropertyChanged(nameof(HasFile));
            }
        }
    }

    public string? ValidationError
    {
        get => _validationError;
        set
        {
            if (_validationError != value)
            {
                _validationError = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(HasError));
            }
        }
    }

    public bool IsPlaying
    {
        get => _isPlaying;
        set
        {
            if (_isPlaying != value)
            {
                _isPlaying = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(PlayButtonText));
            }
        }
    }

    public string? FileName => PcmPath is not null ? Path.GetFileName(PcmPath) : null;
    public bool HasFile => PcmPath is not null;
    public bool HasError => ValidationError is not null;
    public string PlayButtonText => IsPlaying ? "■" : "▶";

    public string? OriginalPcmPath
    {
        get => _originalPcmPath;
        set
        {
            if (_originalPcmPath != value)
            {
                _originalPcmPath = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(HasOriginal));
            }
        }
    }

    public bool IsPlayingOriginal
    {
        get => _isPlayingOriginal;
        set
        {
            if (_isPlayingOriginal != value)
            {
                _isPlayingOriginal = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(OriginalPlayButtonText));
            }
        }
    }

    public bool HasOriginal => OriginalPcmPath is not null;
    public string OriginalPlayButtonText => IsPlayingOriginal ? "■" : "♪";

    public event PropertyChangedEventHandler? PropertyChanged;

    protected void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
