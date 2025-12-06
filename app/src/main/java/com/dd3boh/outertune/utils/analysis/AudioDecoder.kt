package com.dd3boh.outertune.utils.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    private const val TAG = "AudioDecoder"

    /**
     * Decodes an audio file to mono PCM float samples using MediaCodec.
     */
    fun decodeToMono(filePath: String): Pair<FloatArray, Int>? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: $filePath")
            return null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.e(TAG, "No audio track found")
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG, "Audio format: sampleRate=$sampleRate, channels=$channelCount")

            // Create decoder
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            val pcmSamples = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOF = false

            while (!isEOF) {
                // Feed input
                val inputIndex = decoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOF = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Get output
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)!!
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                    // Convert to float and mix to mono
                    val shorts = ShortArray(bufferInfo.size / 2)
                    outputBuffer.asShortBuffer().get(shorts)

                    for (i in shorts.indices step channelCount) {
                        // Mix channels to mono
                        var sample = 0f
                        for (ch in 0 until channelCount.coerceAtMost(shorts.size - i)) {
                            sample += shorts[i + ch] / 32768f
                        }
                        pcmSamples.add(sample / channelCount)
                    }

                    decoder.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }

            decoder.stop()
            decoder.release()

            Log.d(TAG, "Decoded ${pcmSamples.size} samples at $sampleRate Hz")
            return Pair(pcmSamples.toFloatArray(), sampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            extractor.release()
        }
    }
}
