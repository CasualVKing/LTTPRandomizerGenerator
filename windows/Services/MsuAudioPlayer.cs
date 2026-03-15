using System;
using System.IO;
using NAudio.Wave;

namespace LTTPRandomizerGenerator.Services;

public class MsuAudioPlayer : IDisposable
{
    private WaveOutEvent? _output;
    private RawSourceWaveStream? _waveStream;
    private FileStream? _stream;
    private bool _disposed;

    public event EventHandler? PlaybackStopped;

    public bool IsPlaying => _output?.PlaybackState == PlaybackState.Playing;

    public string? Play(string pcmPath)
    {
        Stop();

        try
        {
            var stream = new FileStream(pcmPath, FileMode.Open, FileAccess.Read, FileShare.Read);
            try
            {
                stream.Seek(8, SeekOrigin.Begin); // skip MSU-1 header
                var waveFormat = new WaveFormat(44100, 16, 2);
                var waveStream = new RawSourceWaveStream(stream, waveFormat);

                _stream = stream;
                _waveStream = waveStream;
                _output = new WaveOutEvent();
                _output.Init(waveStream);
                _output.PlaybackStopped += OnOutputPlaybackStopped;
                _output.Play();
                return null;
            }
            catch
            {
                stream.Dispose();
                throw;
            }
        }
        catch (Exception ex)
        {
            DisposePlayback();
            return $"Playback error: {ex.Message}";
        }
    }

    public void Stop()
    {
        if (_output?.PlaybackState == PlaybackState.Playing)
            _output.Stop();

        DisposePlayback();
    }

    private void OnOutputPlaybackStopped(object? sender, StoppedEventArgs args)
        => PlaybackStopped?.Invoke(this, EventArgs.Empty);

    private void DisposePlayback()
    {
        if (_output is not null)
        {
            _output.PlaybackStopped -= OnOutputPlaybackStopped;
            _output.Dispose();
            _output = null;
        }
        _waveStream?.Dispose();
        _waveStream = null;
        _stream?.Dispose();
        _stream = null;
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            DisposePlayback();
            _disposed = true;
        }
        GC.SuppressFinalize(this);
    }
}
