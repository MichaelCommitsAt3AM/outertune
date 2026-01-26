#include <jni.h>
#include <map>
#include <vector>
#include <algorithm>
#include <cmath>
#include <android/log.h>

// BTrack Header
#include "BTrack.h"

// libKeyFinder Headers
#include "keyfinder.h"
#include "audiodata.h"

#define TAG "OuterTune-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// --- Constants ---
const int HOP_SIZE = 512;
const int FRAME_SIZE = 1024;
const double MIN_BPM = 40.0;
const double MAX_BPM = 200.0;
const double TARGET_MIN_BPM = 75.0;
const double TARGET_MAX_BPM = 160.0;
const double LATENCY_COMPENSATION_MS = 40.0;

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

    // 1. Calculate all Inter-Beat Intervals (IBIs)
    std::vector<double> intervals;
    intervals.reserve(beatTimesMs.size() - 1);
    for (size_t i = 1; i < beatTimesMs.size(); ++i) {
        double diff = beatTimesMs[i] - beatTimesMs[i - 1];
        // Ignore impossible glitches (< 10ms)
        if (diff > 10.0) intervals.push_back(diff);
    }

    if (intervals.empty()) return 0.0;

    // 2. Build a Histogram of BPMs (Resolution: 1 BPM)
    // We map integer BPM -> Count (score)
    std::map<int, int> bpmHistogram;

    for (double intervalMs : intervals) {
        if (intervalMs <= 0) continue;

        double rawBpm = 60000.0 / intervalMs;

        // 3. Octave Normalization (The "Double/Half" Fix)
        // We force the BPM into our target range (75-160) to see where the energy clusters.
        // e.g., if the raw is 70, we treat it as 140 for voting purposes.
        double normalizedBpm = rawBpm;
        while (normalizedBpm < TARGET_MIN_BPM && normalizedBpm > 0) normalizedBpm *= 2.0;
        while (normalizedBpm > TARGET_MAX_BPM) normalizedBpm /= 2.0;

        int bin = std::round(normalizedBpm);
        bpmHistogram[bin]++;

        // Smear vote to neighbors to handle jitter (120.1 vs 119.9)
        bpmHistogram[bin - 1]++;
        bpmHistogram[bin + 1]++;
    }

    // 4. Find the Mode (The bin with the highest score)
    int bestBin = 0;
    int maxCount = -1;

    for (auto const& [bpm, count] : bpmHistogram) {
        if (count > maxCount) {
            maxCount = count;
            bestBin = bpm;
        }
    }

    // 5. Refinement (Weighted Average)
    // Now that we know the "Rough BPM" (e.g., 120), calculate the precise average
    // using ONLY the intervals that contributed to this peak.
    double totalBpm = 0.0;
    int validCount = 0;

    for (double intervalMs : intervals) {
        double rawBpm = 60000.0 / intervalMs;

        // Apply the same normalization logic to match the raw beat against our Winner
        double normalizedBpm = rawBpm;
        while (normalizedBpm < TARGET_MIN_BPM && normalizedBpm > 0) normalizedBpm *= 2.0;
        while (normalizedBpm > TARGET_MAX_BPM) normalizedBpm /= 2.0;

        // If this interval is close to our best bin (within +/- 3 BPM), include it in the average
        if (std::abs(normalizedBpm - bestBin) <= 3.0) {
            totalBpm += normalizedBpm;
            validCount++;
        }
    }

    return (validCount > 0) ? (totalBpm / validCount) : (double)bestBin;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_dd3boh_outertune_utils_analysis_AudioAnalyzer_analyzeBpm(
        JNIEnv* env,
        jobject,
        jfloatArray pcmData,
        jint sampleRate) {

    LOGD("=== [BTrack] Starting BPM analysis ===");
    
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    if (!data) {
        LOGD("[BTrack] FAILED: Could not get PCM array elements");
        return nullptr;
    }

    jsize length = env->GetArrayLength(pcmData);
    LOGD("[BTrack] Input: %d samples @ %d Hz (%.2f seconds)", length, sampleRate, (double)length / sampleRate);

    BTrack beatTracker(HOP_SIZE, FRAME_SIZE);

    // Optimizing for modern electronic/pop structures
    // beatTracker.setFixTempo(false);

    std::vector<double> processingFrame(FRAME_SIZE, 0.0);
    std::vector<double> detectedBeatsMs;

    if (length >= FRAME_SIZE) {
        int framesProcessed = 0;
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
            framesProcessed++;
        }
        LOGD("[BTrack] Processed %d frames, detected %zu raw beats", framesProcessed, detectedBeatsMs.size());
    }

    if (detectedBeatsMs.empty()) {
        LOGD("[BTrack] WARNING: No beats detected");
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return nullptr;
    }

    // Rec #3: Calculate raw BPM, then clean the grid using that BPM as a reference
    double calculatedBpm = calculateBpmFromBeats(detectedBeatsMs);
    LOGD("[BTrack] Calculated BPM: %.2f", calculatedBpm);
    
    std::vector<double> cleanedBeats = cleanBeatGrid(detectedBeatsMs, calculatedBpm);

    env->ReleaseFloatArrayElements(pcmData, data, 0);

    LOGD("[BTrack] Final: BPM=%.2f, Beats (raw=%zu, cleaned=%zu)",
         calculatedBpm, detectedBeatsMs.size(), cleanedBeats.size());

    jclass resultClass = env->FindClass("com/dd3boh/outertune/utils/analysis/AudioAnalysisResult");
    if (!resultClass) {
        LOGD("[BTrack] FAILED: Could not find AudioAnalysisResult class");
        return nullptr;
    }

    // Updated constructor signature to include the optional key parameter
    // Signature: (Float, Long, LongArray, MusicalKey?) -> AudioAnalysisResult
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(FJ[JLcom/dd3boh/outertune/utils/analysis/MusicalKey;)V");
    if (!constructor) {
        LOGD("[BTrack] FAILED: Could not find AudioAnalysisResult constructor with signature (FJ[JLcom/dd3boh/outertune/utils/analysis/MusicalKey;)V");
        return nullptr;
    }

    jlongArray beatGrid = env->NewLongArray((jsize)cleanedBeats.size());
    if (!beatGrid) {
        LOGD("[BTrack] FAILED: Could not allocate beat grid array");
        return nullptr;
    }

    std::vector<jlong> beatsLong;
    beatsLong.reserve(cleanedBeats.size());
    for (double b : cleanedBeats) {
        beatsLong.push_back((jlong)b);
    }

    if (!beatsLong.empty()) {
        env->SetLongArrayRegion(beatGrid, 0, (jsize)beatsLong.size(), beatsLong.data());
    }

    jlong firstBeatMs = beatsLong.empty() ? 0 : beatsLong[0];

    LOGD("[BTrack] === Complete: Returning result ===");
    // Pass null for the key parameter since BTrack doesn't detect musical keys
    return env->NewObject(resultClass, constructor, (jfloat)calculatedBpm, firstBeatMs, beatGrid, nullptr);
}

/**
 * Helper function to convert libKeyFinder key_t to Camelot index (1-24).
 * 
 * libKeyFinder keys:
 * A_MAJOR=0, A_MINOR=1, B_FLAT_MAJOR=2, B_FLAT_MINOR=3, B_MAJOR=4, B_MINOR=5,
 * C_MAJOR=6, C_MINOR=7, D_FLAT_MAJOR=8, D_FLAT_MINOR=9, D_MAJOR=10, D_MINOR=11,
 * E_FLAT_MAJOR=12, E_FLAT_MINOR=13, E_MAJOR=14, E_MINOR=15, F_MAJOR=16, F_MINOR=17,
 * G_FLAT_MAJOR=18, G_FLAT_MINOR=19, G_MAJOR=20, G_MINOR=21, A_FLAT_MAJOR=22, A_FLAT_MINOR=23
 * 
 * Camelot wheel mapping (1-24):
 * 1A-12A = Minor keys, 1B-12B = Major keys
 */
int keyFinderToCamelot(KeyFinder::key_t key) {
    // Map libKeyFinder enum to Camelot index
    switch(key) {
        // Minors (A suffix)
        case KeyFinder::A_MINOR:        return 1;  // 8A
        case KeyFinder::E_MINOR:        return 2;  // 9A
        case KeyFinder::B_MINOR:        return 3;  // 10A
        case KeyFinder::G_FLAT_MINOR:   return 4;  // 11A (F# minor enharmonic)
        case KeyFinder::D_FLAT_MINOR:   return 5;  // 12A
        case KeyFinder::A_FLAT_MINOR:   return 6;  // 1A
        case KeyFinder::E_FLAT_MINOR:   return 7;  // 2A
        case KeyFinder::B_FLAT_MINOR:   return 8;  // 3A
        case KeyFinder::F_MINOR:        return 9;  // 4A
        case KeyFinder::C_MINOR:        return 10; // 5A
        case KeyFinder::G_MINOR:        return 11; // 6A
        case KeyFinder::D_MINOR:        return 12; // 7A
        
        // Majors (B suffix)
        case KeyFinder::B_MAJOR:        return 13; // 8B
        case KeyFinder::G_FLAT_MAJOR:   return 14; // 9B (F# major enharmonic)
        case KeyFinder::D_FLAT_MAJOR:   return 15; // 10B
        case KeyFinder::A_FLAT_MAJOR:   return 16; // 11B
        case KeyFinder::E_FLAT_MAJOR:   return 17; // 12B
        case KeyFinder::B_FLAT_MAJOR:   return 18; // 1B
        case KeyFinder::F_MAJOR:        return 19; // 2B
        case KeyFinder::C_MAJOR:        return 20; // 3B
        case KeyFinder::G_MAJOR:        return 21; // 4B
        case KeyFinder::D_MAJOR:        return 22; // 5B
        case KeyFinder::A_MAJOR:        return 23; // 6B
        case KeyFinder::E_MAJOR:        return 24; // 7B
        
        case KeyFinder::SILENCE:
        default:
            return -1; // Unknown/silence
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dd3boh_outertune_utils_analysis_KeyDetector_detectKeyNative(
        JNIEnv* env,
        jobject,
        jfloatArray pcmData,
        jint sampleRate) {
    
    LOGD("=== [KeyFinder] Starting key detection ===");
    
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    if (!data) {
        LOGD("[KeyFinder] FAILED: Could not get PCM array elements");
        return -1;
    }
    
    jsize length = env->GetArrayLength(pcmData);
    LOGD("[KeyFinder] Input: %d samples @ %d Hz (%.2f seconds)", length, sampleRate, (double)length / sampleRate);
    
    try {
        // Create libKeyFinder instance
        KeyFinder::KeyFinder kf;
        
        // Prepare AudioData
        KeyFinder::AudioData audioData;
        audioData.setFrameRate(sampleRate);
        audioData.setChannels(1); // Mono
        audioData.addToSampleCount(length);
        
        LOGD("[KeyFinder] Copying PCM data (%d samples)...", length);
        
        // Copy PCM data
        for (int i = 0; i < length; i++) {
            audioData.setSample(i, (double)data[i]);
        }
        
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        
        LOGD("[KeyFinder] Running key detection algorithm...");
        
        // Run key detection
        KeyFinder::key_t detectedKey = kf.keyOfAudio(audioData);
        
        LOGD("[KeyFinder] Raw result: key_t=%d", (int)detectedKey);
        
        // Convert to Camelot index
        int camelotIndex = keyFinderToCamelot(detectedKey);
        
        if (camelotIndex > 0) {
            LOGD("[KeyFinder] === Complete: Camelot=%d ===", camelotIndex);
        } else {
            LOGD("[KeyFinder] === Result: Silence/Unknown (returning -1) ===");
        }
        
        return camelotIndex;
        
    } catch (const std::exception& e) {
        LOGD("[KeyFinder] EXCEPTION: %s", e.what());
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return -1;
    } catch (...) {
        LOGD("[KeyFinder] UNKNOWN EXCEPTION");
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return -1;
    }
}