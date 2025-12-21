package com.dd3boh.outertune.utils.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val DECODE_TIMEOUT_MS = 300_000L // 5 mins

    fun decodeToMono(filePath: String): Pair<FloatArray, Int>? {
        Log.d(TAG, "=== decodeToMono started for: $filePath ===")

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: $filePath")
            return null
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var loopCount = 0

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

            if (audioTrackIndex < 0 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)

            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = audioFormat.getLong(MediaFormat.KEY_DURATION)

            // Estimate array size (add 20% buffer for safety to avoid resizing)
            val estimatedSamples = ((duration / 1_000_000.0) * sampleRate * 1.2).toInt()

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            // Pre-allocate FloatArray
            var pcmSamples = FloatArray(estimatedSamples)
            var currentIndex = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOF = false
            val startTime = System.currentTimeMillis()

            // OPTIMIZATION: Reuse a generic buffer for short conversion to reduce GC
            // 4096 is a standard starting size, we will resize this specific buffer if needed
            var reusableShortBuffer = ShortArray(4096)

            Log.d(TAG, "Entering decoding loop...")
            while (!isEOF) {
                loopCount++

                // Log less frequently (every 2000) to save IO
                if (loopCount % 2000 == 0) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    Log.d(TAG, "Progress: iteration=$loopCount, samples=$currentIndex, elapsed=${elapsed}s")
                }

                if (System.currentTimeMillis() - startTime > DECODE_TIMEOUT_MS) {
                    Log.e(TAG, "Decoding timed out after ${DECODE_TIMEOUT_MS}ms")
                    return null
                }

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

                    val shortCount = bufferInfo.size / 2

                    // OPTIMIZATION: Resize reusable buffer only if strictly necessary
                    if (reusableShortBuffer.size < shortCount) {
                        reusableShortBuffer = ShortArray(shortCount)
                    }

                    // Read into reusable buffer
                    outputBuffer.asShortBuffer().get(reusableShortBuffer, 0, shortCount)

                    val samplesNeeded = shortCount / channelCount

                    // Resize main storage array if needed (This is expensive, so we overestimated initial size)
                    if (currentIndex + samplesNeeded > pcmSamples.size) {
                        Log.w(TAG, "Resizing main array - initial estimation was too small")
                        val newArray = FloatArray((currentIndex + samplesNeeded) * 2)
                        System.arraycopy(pcmSamples, 0, newArray, 0, currentIndex)
                        pcmSamples = newArray
                    }

                    // Write directly to array using reusable buffer
                    for (i in 0 until shortCount step channelCount) {
                        var sample = 0f
                        val actualChannels = min(channelCount, shortCount - i)
                        for (ch in 0 until actualChannels) {
                            sample += reusableShortBuffer[i + ch] / 32768f
                        }
                        pcmSamples[currentIndex++] = sample / actualChannels
                    }

                    decoder.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            // Trim array to actual size
            val finalArray = if (currentIndex < pcmSamples.size) {
                pcmSamples.copyOf(currentIndex)
            } else {
                pcmSamples
            }

            return Pair(finalArray, sampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }
}