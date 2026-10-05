package com.dd3boh.outertune.utils.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.dd3boh.outertune.utils.DebugLog as Log

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val DECODE_TIMEOUT_MS = 300_000L // 5 mins
    private const val CODEC_TIMEOUT_US = 10_000L

    /**
     * Decodes a file (path or content:// URI) to mono float samples in -1..1, averaging all
     * channels, in a single pass. Handles 16-bit and float decoder output.
     *
     * @return the samples and their sample rate, or null on failure
     */
    fun decodeToMono(context: Context, filePath: String): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            if (filePath.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(filePath), null)
            } else {
                if (!File(filePath).exists()) {
                    Log.e(TAG, "File does not exist: $filePath")
                    return null
                }
                extractor.setDataSource(filePath)
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            // Sized from the container's duration (+5%) and grown if that was short.
            var samples = FloatArray((durationUs / 1_000_000.0 * sampleRate * 1.05).toInt().coerceAtLeast(sampleRate))
            var count = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val startTime = System.currentTimeMillis()

            while (true) {
                if (System.currentTimeMillis() - startTime > DECODE_TIMEOUT_MS) {
                    Log.e(TAG, "Decoding timed out")
                    return null
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inputIndex)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        Log.e(TAG, "Unsupported decoder output encoding $encoding")
                        return null
                    }
                    continue
                }
                if (outputIndex < 0) continue

                val buffer = decoder.getOutputBuffer(outputIndex)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.order(ByteOrder.LITTLE_ENDIAN)

                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                val frames = info.size / (bytesPerSample * channels.coerceAtLeast(1))
                if (count + frames > samples.size) {
                    samples = samples.copyOf(maxOf(count + frames, (samples.size * 1.5).toInt()))
                }
                count += downmix(buffer, encoding, channels.coerceAtLeast(1), frames, samples, count)

                decoder.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            return samples.copyOf(count) to sampleRate
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Averages [channels] interleaved channels of [frames] frames into [out] at [offset]. */
    private fun downmix(buffer: ByteBuffer, encoding: Int, channels: Int, frames: Int, out: FloatArray, offset: Int): Int {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val input = buffer.asFloatBuffer()
            for (f in 0 until frames) {
                var sum = 0f
                repeat(channels) { sum += input.get() }
                out[offset + f] = sum / channels
            }
        } else {
            val input = buffer.asShortBuffer()
            val scale = 1f / (32768f * channels)
            for (f in 0 until frames) {
                var sum = 0
                repeat(channels) { sum += input.get() }
                out[offset + f] = sum * scale
            }
        }
        return frames
    }
}
