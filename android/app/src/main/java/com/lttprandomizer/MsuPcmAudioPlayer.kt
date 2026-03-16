package com.lttprandomizer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.*
import java.io.RandomAccessFile

class MsuPcmAudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    val isPlaying: Boolean get() =
        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
        mediaPlayer?.isPlaying == true

    var onPlaybackStopped: (() -> Unit)? = null

    fun play(path: String, scope: CoroutineScope): String? {
        stop()

        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext == "pcm") {
            playPcm(path, scope)
        } else {
            playMedia(path, scope)
        }
    }

    private fun playPcm(pcmPath: String, scope: CoroutineScope): String? {
        return try {
            val bufferSize = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioTrack = track
            track.play()

            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    val file = RandomAccessFile(pcmPath, "r")
                    file.use {
                        it.seek(8) // skip MSU-1 header
                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        while (it.read(buffer).also { n -> bytesRead = n } > 0 && isActive) {
                            track.write(buffer, 0, bytesRead)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        stop()
                        onPlaybackStopped?.invoke()
                    }
                }
            }

            null
        } catch (ex: Exception) {
            stop()
            "Playback error: ${ex.message}"
        }
    }

    private fun playMedia(path: String, scope: CoroutineScope): String? {
        return try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(path)
                prepare()
            }
            mediaPlayer = player

            player.setOnCompletionListener {
                scope.launch(Dispatchers.Main) {
                    stop()
                    onPlaybackStopped?.invoke()
                }
            }

            player.start()
            null
        } catch (ex: Exception) {
            stop()
            "Playback error: ${ex.message}"
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) { }
        audioTrack?.release()
        audioTrack = null
        try {
            mediaPlayer?.stop()
        } catch (_: IllegalStateException) { }
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
