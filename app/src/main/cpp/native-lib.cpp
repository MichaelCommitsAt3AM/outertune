#include <jni.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include <android/log.h>

// BTrack Header
#include "BTrack.h"

#define TAG "OuterTune-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// --- Constants ---
const int HOP_SIZE = 512;
const int FRAME_SIZE = 1024; // BTrack typically uses 2x Hop Size
const double MIN_BPM = 80.0;
const double MAX_BPM = 160.0;

/**
 * Helper: Calculate BPM from the Median Inter-Beat Interval (IBI).
 * This is more robust than using the instantaneous tempo from the end of the song.
 */
double calculateBpmFromBeats(const std::vector<double>& beatTimesMs) {
    if (beatTimesMs.size() < 2) return 0.0;

    // 1. Calculate intervals
    std::vector<double> intervals;
    intervals.reserve(beatTimesMs.size() - 1);
    for (size_t i = 1; i < beatTimesMs.size(); ++i) {
        double diff = beatTimesMs[i] - beatTimesMs[i - 1];
        if (diff > 10.0) { // Filter insanely short glitches (<10ms)
            intervals.push_back(diff);
        }
    }

    if (intervals.empty()) return 0.0;

    // 2. Sort to find median
    std::sort(intervals.begin(), intervals.end());
    double medianIBI = intervals[intervals.size() / 2];

    // 3. Convert IBI (ms) to BPM
    if (medianIBI <= 0.0) return 0.0;
    double bpm = 60000.0 / medianIBI;

    // 4. Clamp/Octave correction (80 - 160 BPM)
    // If we detected 70 BPM, it's likely 140. If 170, likely 85.
    while (bpm < MIN_BPM && bpm > 0) bpm *= 2.0;
    while (bpm > MAX_BPM) bpm /= 2.0;

    return bpm;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_dd3boh_outertune_utils_analysis_AudioAnalyzer_analyzeBpm(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray pcmData,
        jint sampleRate) {

    // --- 1. JNI Data Acquisition ---
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    if (!data) return nullptr;

    jsize length = env->GetArrayLength(pcmData);

    // --- 2. Initialize BTrack ---
    // BTrack requires hops and frame sizes.
    // It maintains its own circular buffer and FFT state internally.
    BTrack beatTracker(HOP_SIZE, FRAME_SIZE);

    // We do not set a fixed tempo; we let the Kalman filter track it.
    // However, if your app implies Electronic music, you might want beatTracker.setFixTempo(false) explicitly.

    // Buffer for BTrack (it expects double, Android provides float)
    std::vector<double> processingFrame(FRAME_SIZE, 0.0);
    std::vector<double> detectedBeatsMs;

    // --- 3. Processing Loop (Simulated Real-time) ---
    // We iterate through the raw audio in hops.
    // Note: BTrack handles the overlapping/windowing internally via `processAudioFrame`.
    // We just feed it `HOP_SIZE` new samples every time, but we must pass a pointer
    // to a buffer of `FRAME_SIZE`. BTrack shifts data internally?
    // Actually, Adam Stark's BTrack `processAudioFrame` takes a pointer to a double array
    // representing the *current frame*.

    int processedSamples = 0;

    // Safety check for very short files
    if (length >= FRAME_SIZE) {

        // Loop through audio with fixed hop
        for (int i = 0; i <= length - FRAME_SIZE; i += HOP_SIZE) {

            // Convert float[] chunk to double[] frame
            // In a real-time context, you'd use a ring buffer here.
            // For offline analysis, direct copy is fastest safe method.
            for (int j = 0; j < FRAME_SIZE; ++j) {
                processingFrame[j] = (double)data[i + j];
            }

            // Core BTrack Processing
            beatTracker.processAudioFrame(processingFrame.data());

            // Check if a beat happened in this frame
            if (beatTracker.beatDueInCurrentFrame()) {
                // Calculate timestamp based on current head position
                // Note: beatDueInCurrentFrame usually implies the beat is at the center
                // or end of the current hop. We approximate to the current hop start for simplicity
                // or use BTrack's internal getBeatTimeInSamples() if exposed.
                // Standard approximation:
                double timestamp = ((double)i / (double)sampleRate) * 1000.0;
                detectedBeatsMs.push_back(timestamp);
            }

            processedSamples += HOP_SIZE;
        }
    }

    // --- 4. Post-Processing Calculation ---
    double calculatedBpm = calculateBpmFromBeats(detectedBeatsMs);

    // Clean up Raw Data early to free memory
    env->ReleaseFloatArrayElements(pcmData, data, 0);

    LOGD("Analysis Done. Beats: %zu, Raw BPM: %.2f", detectedBeatsMs.size(), calculatedBpm);

    // --- 5. JNI Object Construction ---
    // Class lookup
    jclass resultClass = env->FindClass("com/dd3boh/outertune/utils/analysis/AudioAnalysisResult");
    if (!resultClass) return nullptr;

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(FJ[J)V");
    if (!constructor) return nullptr;

    // Create long[] for beat grid
    jlongArray beatGrid = env->NewLongArray((jsize)detectedBeatsMs.size());
    if (!beatGrid) return nullptr;

    // Convert std::vector<double> to jlong buffer
    std::vector<jlong> beatsLong;
    beatsLong.reserve(detectedBeatsMs.size());
    for (double b : detectedBeatsMs) {
        beatsLong.push_back((jlong)b);
    }

    if (!beatsLong.empty()) {
        env->SetLongArrayRegion(beatGrid, 0, (jsize)beatsLong.size(), beatsLong.data());
    }

    jlong firstBeatMs = beatsLong.empty() ? 0 : beatsLong[0];

    // Return the Java object
    return env->NewObject(resultClass, constructor, (jfloat)calculatedBpm, firstBeatMs, beatGrid);
}