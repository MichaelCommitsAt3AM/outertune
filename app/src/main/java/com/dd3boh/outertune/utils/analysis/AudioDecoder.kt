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
    private const val DECODE_TIMEOUT_MS = 30_000L

    fun decodeToMono(filePath: String): Pair<FloatArray, Int>? {
        Log.d(TAG, "=== decodeToMono started for: $filePath ===")

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: $filePath")
            return null
        }
        Log.d(TAG, "File exists, size: ${file.length()} bytes")

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var loopCount = 0

        try {
            Log.d(TAG, "Setting data source...")
            extractor.setDataSource(filePath)
            Log.d(TAG, "Data source set successfully")

            // Find audio track
            Log.d(TAG, "Searching for audio track (total tracks: ${extractor.trackCount})...")
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                Log.d(TAG, "Track $i: mime=$mime")
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    Log.d(TAG, "Found audio track at index $i")
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.e(TAG, "No audio track found")
                return null
            }

            Log.d(TAG, "Selecting audio track $audioTrackIndex...")
            extractor.selectTrack(audioTrackIndex)
            Log.d(TAG, "Audio track selected")

            // Initialize with Extractor's format (Header info), but keep mutable for Decoder updates
            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = audioFormat.getLong(MediaFormat.KEY_DURATION) // in microseconds

            // Estimate array size (add 10% buffer for safety)
            val estimatedSamples = ((duration / 1_000_000.0) * sampleRate * 1.1).toInt()
            Log.d(TAG, "Initial format: sampleRate=$sampleRate, channels=$channelCount, duration=${duration/1_000_000}s")
            Log.d(TAG, "Estimated samples: $estimatedSamples")

            // Create decoder
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            Log.d(TAG, "Creating decoder for mime: $mime")
            decoder = MediaCodec.createDecoderByType(mime)
            Log.d(TAG, "Decoder created, configuring...")
            decoder.configure(audioFormat, null, null, 0)
            Log.d(TAG, "Decoder configured, starting...")
            decoder.start()
            Log.d(TAG, "Decoder started successfully")

            // Pre-allocate FloatArray instead of using List
            var pcmSamples = FloatArray(estimatedSamples)
            var currentIndex = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOF = false
            val startTime = System.currentTimeMillis()

            Log.d(TAG, "Entering decoding loop...")
            while (!isEOF) {
                loopCount++

                if (loopCount % 1000 == 0) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    Log.d(TAG, "Progress: iteration=$loopCount, samples=$currentIndex, elapsed=${elapsed}s")
                }

                // CHECK FOR TIMEOUT
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
                        Log.d(TAG, "Reached end of input stream, queuing EOS")
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

                    val samplesNeeded = shorts.size / channelCount

                    // Resize array if needed
                    if (currentIndex + samplesNeeded > pcmSamples.size) {
                        Log.d(TAG, "Resizing array from ${pcmSamples.size} to ${(currentIndex + samplesNeeded) * 2}")
                        val newArray = FloatArray((currentIndex + samplesNeeded) * 2)
                        System.arraycopy(pcmSamples, 0, newArray, 0, currentIndex)
                        pcmSamples = newArray
                    }

                    // Write directly to array
                    for (i in shorts.indices step channelCount) {
                        var sample = 0f
                        // Safely sum available channels (in case format lies or changes mid-stream slightly)
                        val actualChannels = min(channelCount, shorts.size - i)
                        for (ch in 0 until actualChannels) {
                            sample += shorts[i + ch] / 32768f
                        }
                        pcmSamples[currentIndex++] = sample / actualChannels
                    }

                    decoder.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Received EOS flag, breaking loop")
                        break
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "Output format changed: $newFormat")

                    // CRITICAL FIX: Update sample rate and channels to what the decoder is actually producing
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newSampleRate != sampleRate) {
                            Log.i(TAG, "Sample rate updated: $sampleRate -> $newSampleRate")
                            sampleRate = newSampleRate
                        }
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val newChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (newChannelCount != channelCount) {
                            Log.i(TAG, "Channel count updated: $channelCount -> $newChannelCount")
                            channelCount = newChannelCount
                        }
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.d(TAG, "Output buffers changed")
                }
            }

            Log.d(TAG, "Decoding loop completed. Total iterations: $loopCount")
            Log.d(TAG, "Decoded $currentIndex samples at $sampleRate Hz")

            // Trim array to actual size (fast operation)
            val finalArray = if (currentIndex < pcmSamples.size) {
                Log.d(TAG, "Trimming array from ${pcmSamples.size} to $currentIndex")
                pcmSamples.copyOf(currentIndex)
            } else {
                pcmSamples
            }

            Log.d(TAG, "Returning result...")
            return Pair(finalArray, sampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio at loop count $loopCount", e)
            return null
        } finally {
            Log.d(TAG, "Cleaning up decoder and extractor...")
            try {
                decoder?.stop()
                Log.d(TAG, "Decoder stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping decoder", e)
            }
            try {
                decoder?.release()
                Log.d(TAG, "Decoder released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing decoder", e)
            }
            try {
                extractor.release()
                Log.d(TAG, "Extractor released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing extractor", e)
            }
            Log.d(TAG, "=== decodeToMono finished ===")
        }
    }
}
