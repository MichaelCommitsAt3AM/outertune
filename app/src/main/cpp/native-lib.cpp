#include <jni.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include <android/log.h>

// BTrack Header
#include "BTrack.h"

#define TAG "OuterTune-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// --- Constants ---
const int HOP_SIZE = 512;
const int FRAME_SIZE = 1024;
const double MIN_BPM = 40.0;
const double MAX_BPM = 200.0;

/**
 * Helper: Post-process the raw beat grid to improve reliability (Recommendation #3).
 * 1. Removes beats that are physically too close (glitches).
 * 2. Fills in obvious missing beats (gaps).
 */
std::vector<double> cleanBeatGrid(std::vector<double>& rawBeats, double averageBpm) {
    if (rawBeats.empty()) return rawBeats;

    std::vector<double> cleaned;
    cleaned.reserve(rawBeats.size());

    // Expected interval in ms based on the calculated BPM
    double expectedInterval = 60000.0 / averageBpm;
    // Tolerance for "too close" (e.g., 1/3 of a beat)
    double minInterval = expectedInterval * 0.33;
    // Tolerance for "gap" (e.g., 1.8x the interval implies a missed beat)
    double gapThreshold = expectedInterval * 1.8;

    // 1. Filter Glitches
    cleaned.push_back(rawBeats[0]);
    for (size_t i = 1; i < rawBeats.size(); ++i) {
        double prev = cleaned.back();
        double curr = rawBeats[i];

        if ((curr - prev) > minInterval) {
            // 2. Fill Gaps
            double diff = curr - prev;
            if (diff > gapThreshold) {
                // Determine how many beats we missed (roughly)
                int missingCount = std::round(diff / expectedInterval) - 1;
                if (missingCount > 0) {
                    double step = diff / (missingCount + 1);
                    for (int k = 1; k <= missingCount; ++k) {
                        cleaned.push_back(prev + k * step);
                    }
                }
            }
            cleaned.push_back(curr);
        }
    }
    return cleaned;
}

double calculateBpmFromBeats(const std::vector<double>& beatTimesMs) {
    if (beatTimesMs.size() < 2) return 0.0;

    std::vector<double> intervals;
    intervals.reserve(beatTimesMs.size() - 1);
    for (size_t i = 1; i < beatTimesMs.size(); ++i) {
        double diff = beatTimesMs[i] - beatTimesMs[i - 1];
        if (diff > 10.0) intervals.push_back(diff);
    }

    if (intervals.empty()) return 0.0;

    std::sort(intervals.begin(), intervals.end());
    double medianIBI = intervals[intervals.size() / 2];

    if (medianIBI <= 0.0) return 0.0;
    double bpm = 60000.0 / medianIBI;

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

    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    if (!data) return nullptr;

    jsize length = env->GetArrayLength(pcmData);

    BTrack beatTracker(HOP_SIZE, FRAME_SIZE);

    // Optimizing for modern electronic/pop structures
    // beatTracker.setFixTempo(false);

    std::vector<double> processingFrame(FRAME_SIZE, 0.0);
    std::vector<double> detectedBeatsMs;

    if (length >= FRAME_SIZE) {
        for (int i = 0; i <= length - FRAME_SIZE; i += HOP_SIZE) {
            for (int j = 0; j < FRAME_SIZE; ++j) {
                processingFrame[j] = (double)data[i + j];
            }

            beatTracker.processAudioFrame(processingFrame.data());

            if (beatTracker.beatDueInCurrentFrame()) {
                // Rec #2: Latency Compensation
                // BTrack analyzes the *filled* buffer. The beat event occurred roughly
                // in the center of the current frame, not at the start.
                // Shift by +FRAME_SIZE/2 samples converted to ms.
                double timestamp = (((double)i + (FRAME_SIZE / 2.0)) / (double)sampleRate) * 1000.0;
                detectedBeatsMs.push_back(timestamp);
            }
        }
    }

    // Rec #3: Calculate raw BPM, then clean the grid using that BPM as a reference
    double calculatedBpm = calculateBpmFromBeats(detectedBeatsMs);
    std::vector<double> cleanedBeats = cleanBeatGrid(detectedBeatsMs, calculatedBpm);

    env->ReleaseFloatArrayElements(pcmData, data, 0);

    LOGD("Analysis Done. Raw BPM: %.2f. Beats Raw: %zu, Cleaned: %zu",
         calculatedBpm, detectedBeatsMs.size(), cleanedBeats.size());

    jclass resultClass = env->FindClass("com/dd3boh/outertune/utils/analysis/AudioAnalysisResult");
    if (!resultClass) return nullptr;

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(FJ[J)V");
    if (!constructor) return nullptr;

    jlongArray beatGrid = env->NewLongArray((jsize)cleanedBeats.size());
    if (!beatGrid) return nullptr;

    std::vector<jlong> beatsLong;
    beatsLong.reserve(cleanedBeats.size());
    for (double b : cleanedBeats) {
        beatsLong.push_back((jlong)b);
    }

    if (!beatsLong.empty()) {
        env->SetLongArrayRegion(beatGrid, 0, (jsize)beatsLong.size(), beatsLong.data());
    }

    jlong firstBeatMs = beatsLong.empty() ? 0 : beatsLong[0];

    return env->NewObject(resultClass, constructor, (jfloat)calculatedBpm, firstBeatMs, beatGrid);
}