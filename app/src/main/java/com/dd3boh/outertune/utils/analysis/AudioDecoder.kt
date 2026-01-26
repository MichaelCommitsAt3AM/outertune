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

    fun decodeToMono(context: android.content.Context, filePath: String): Pair<FloatArray, Int>? {
        // Reuse stereo decoder and downmix to save code duplication
        val (stereoSamples, sampleRate) = decodeToStereo(context, filePath) ?: return null
        
        // Downmix Stereo (Interleaved) to Mono Float
        val monoSamples = FloatArray(stereoSamples.size / 2)
        for (i in monoSamples.indices) {
            val left = stereoSamples[i * 2]
            val right = stereoSamples[i * 2 + 1]
            // Average and normalize to -1.0..1.0
            monoSamples[i] = ((left + right) / 2f) / 32768f
        }
        
        return Pair(monoSamples, sampleRate)
    }

    /**
     * Decodes audio to an interleaved Stereo ShortArray (L, R, L, R...).
     * Returns Pair(ShortArray, SampleRate).
     */
    fun decodeToStereo(context: android.content.Context, filePath: String): Pair<ShortArray, Int>? {
        Log.d(TAG, "=== decodeToStereo started for: $filePath ===")

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            if (filePath.startsWith("content://")) {
                extractor.setDataSource(context, android.net.Uri.parse(filePath), null)
            } else {
                 val file = File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: $filePath")
                    return null
                }
                extractor.setDataSource(filePath)
            }

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

            // Estimate array size (add 20% buffer)
            // Duration is in micros. 
            // Total samples = (duration / 1M) * sampleRate * channels
            val estimatedSamples = ((duration / 1_000_000.0) * sampleRate * channelCount * 1.2).toInt()

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            // Pre-allocate ShortArray
            var pcmSamples = ShortArray(estimatedSamples)
            var currentIndex = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOF = false
            val startTime = System.currentTimeMillis()

            Log.d(TAG, "Entering decoding loop...")
            var loopCount = 0

            while (!isEOF) {
                loopCount++
                if (System.currentTimeMillis() - startTime > DECODE_TIMEOUT_MS) {
                    Log.e(TAG, "Decoding timed out")
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
                    
                    // Resize if needed
                    if (currentIndex + shortCount > pcmSamples.size) {
                         val newSize = (pcmSamples.size * 1.5).toInt().coerceAtLeast(currentIndex + shortCount + 48000)
                         val newArray = ShortArray(newSize)
                         System.arraycopy(pcmSamples, 0, newArray, 0, currentIndex)
                         pcmSamples = newArray
                    }

                    // We need to handle Mono sources by duplicating channels for Stereo output
                    val decodedShorts = ShortArray(shortCount)
                    outputBuffer.asShortBuffer().get(decodedShorts)
                    
                    if (channelCount == 1) {
                         // Convert Mono -> Stereo (L=Input, R=Input)
                         // We need double the space
                         if (currentIndex + (shortCount * 2) > pcmSamples.size) {
                             val newSize = (pcmSamples.size * 1.5).toInt().coerceAtLeast(currentIndex + (shortCount * 2) + 48000)
                             val newArray = ShortArray(newSize)
                             System.arraycopy(pcmSamples, 0, newArray, 0, currentIndex)
                             pcmSamples = newArray
                         }
                         
                         for (s in decodedShorts) {
                             pcmSamples[currentIndex++] = s
                             pcmSamples[currentIndex++] = s
                         }
                    } else {
                        // Already Stereo (or more? we assume max 2 for this simple impl, but typically 2)
                        // If multi-channel > 2, we should probably take first 2, but for now copy all
                        // Adjust logic if > 2 channels is common (rare for music files)
                         System.arraycopy(decodedShorts, 0, pcmSamples, currentIndex, shortCount)
                         currentIndex += shortCount
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
            
            // Trim
             val finalArray = if (currentIndex < pcmSamples.size) {
                pcmSamples.copyOf(currentIndex)
            } else {
                pcmSamples
            }

            Log.d(TAG, "=== decodeToStereo COMPLETED: ${finalArray.size} samples @ ${sampleRate}Hz ===")
            return Pair(finalArray, sampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }
    fun loadBeatGrid(file: File, startMs: Long? = null, endMs: Long? = null): List<Float>? {
        if (!file.exists()) {
            Log.e(TAG, "loadBeatGrid: File does not exist: ${file.absolutePath}")
            return null
        }

        return try {
            val content = file.readText()
            if (content.isBlank()) {
                Log.e(TAG, "loadBeatGrid: File is empty: ${file.absolutePath}")
                return null
            }

            // Split by comma OR newline, then trim whitespace
            val beats = content.split(Regex("[,\\n]"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toFloatOrNull() }
                .filter { beatMs ->
                    val afterStart = startMs == null || beatMs >= startMs
                    val beforeEnd = endMs == null || beatMs <= endMs
                    afterStart && beforeEnd
                }

            if (beats.isEmpty()) {
                // If we filtered everything out, that's technically a valid (but empty) result for the range.
                // But usually implies a logic error if we expected beats.
                // However, for the calling code, an empty list usually aborts the transition.
                Log.w(TAG, "loadBeatGrid: Loaded 0 beats from ${file.absolutePath} (Range: $startMs - $endMs)")
                return null
            }
            
            Log.d(TAG, "loadBeatGrid: Successfully loaded ${beats.size} beats from ${file.absolutePath} (Range: $startMs - $endMs)")
            beats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse beatgrid file: ${file.absolutePath}", e)
            null
        }
    }
}