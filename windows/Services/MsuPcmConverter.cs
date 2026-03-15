using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using NAudio.Wave;

namespace LTTPRandomizerGenerator.Services;

public static class MsuPcmConverter
{
    private static readonly HashSet<string> AiffExtensions =
        new(StringComparer.OrdinalIgnoreCase) { ".aiff", ".aif" };

    public static async Task<string?> ConvertAsync(
        string sourcePath,
        string destPcmPath,
        uint loopPoint = 0,
        IProgress<double>? progress = null,
        CancellationToken ct = default)
    {
        return await Task.Run(() =>
        {
            try
            {
                WaveStream reader = AiffExtensions.Contains(Path.GetExtension(sourcePath))
                    ? new AiffFileReader(sourcePath)
                    : (WaveStream)new MediaFoundationReader(sourcePath);

                using (reader)
                {
                    var targetFormat = new WaveFormat(44100, 16, 2);
                    using var resampler = new MediaFoundationResampler(reader, targetFormat)
                    {
                        ResamplerQuality = 60
                    };

                    long expectedBytes = reader.TotalTime.TotalSeconds > 0
                        ? (long)(reader.TotalTime.TotalSeconds * 44100 * 2 * 2)
                        : 0;

                    using var output = new FileStream(destPcmPath, FileMode.Create, FileAccess.Write, FileShare.None);

                    // Write 8-byte MSU-1 header
                    byte[] header = new byte[8];
                    Encoding.ASCII.GetBytes("MSU1").CopyTo(header, 0);
                    BitConverter.GetBytes(loopPoint).CopyTo(header, 4);
                    output.Write(header, 0, 8);

                    // Stream PCM data in 64 KB chunks
                    byte[] buffer = new byte[65536];
                    long bytesWritten = 0;
                    int lastReportedMilestone = 0;
                    int bytesRead;

                    while ((bytesRead = resampler.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        ct.ThrowIfCancellationRequested();
                        output.Write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;

                        if (expectedBytes > 0 && progress != null)
                        {
                            double pct = Math.Min(1.0, (double)bytesWritten / expectedBytes);
                            int milestone = (int)(pct * 4) * 25;
                            if (milestone > lastReportedMilestone && milestone < 100)
                            {
                                progress.Report(pct);
                                lastReportedMilestone = milestone;
                            }
                        }
                    }

                    progress?.Report(1.0);
                }

                return null;
            }
            catch (OperationCanceledException)
            {
                TryDelete(destPcmPath);
                throw;
            }
            catch (Exception ex)
            {
                TryDelete(destPcmPath);
                return $"Conversion failed: {ex.Message}";
            }
        }, ct);
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
    }
}
