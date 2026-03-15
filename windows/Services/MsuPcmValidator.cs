using System;
using System.IO;
using System.Text;

namespace LTTPRandomizerGenerator.Services;

public static class MsuPcmValidator
{
    private const string ExpectedSignature = "MSU1";
    private const int HeaderSize = 8;

    public static string? Validate(string filePath)
    {
        try
        {
            var info = new FileInfo(filePath);
            if (!info.Exists)
                return "File does not exist.";

            if (info.Length < HeaderSize)
                return $"File too small ({info.Length} bytes). MSU-1 header requires at least {HeaderSize} bytes.";

            using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            var header = new byte[HeaderSize];
            int bytesRead = fs.Read(header, 0, HeaderSize);

            if (bytesRead < HeaderSize)
                return "Could not read MSU-1 header.";

            string sig = Encoding.ASCII.GetString(header, 0, 4);
            if (sig != ExpectedSignature)
                return $"Invalid MSU-1 signature. Expected \"{ExpectedSignature}\", found \"{sig}\".";

            if (info.Length <= HeaderSize)
                return "File contains only the MSU-1 header with no audio data.";

            return null;
        }
        catch (Exception ex)
        {
            return $"Cannot read file: {ex.Message}";
        }
    }
}
