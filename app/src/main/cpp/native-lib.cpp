#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

extern "C" {
#include "aubio.h"
}

#define TAG "OuterTune-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Analyzes PCM audio data to detect BPM and beat positions.
 * Audio decoding must be done on the Kotlin side using MediaCodec.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_dd3boh_outertune_utils_analysis_AudioAnalyzer_analyzeBpm(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray pcmData,
        jint sampleRate) {

    LOGD("=== analyzeBpm CALLED ===");
    LOGD("Sample rate: %d", sampleRate);

    // Convert Java float array to C++
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    if (!data) {
        LOGE("Failed to get PCM data");
        return nullptr;
    }

    jsize length = env->GetArrayLength(pcmData);
    LOGD("PCM length: %d samples (%.2f seconds)", length, (float)length / sampleRate);

    // Setup Aubio Tempo
    uint_t win_size = 2048;
    uint_t hop_size = 512;
    aubio_tempo_t* tempo = new_aubio_tempo("default", win_size, hop_size, (uint_t)sampleRate);

    if (!tempo) {
        LOGE("Failed to create aubio tempo object");
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return nullptr;
    }
    LOGD("Aubio tempo created successfully");

    // Process Audio
    fvec_t* input = new_fvec(hop_size);
    fvec_t* output = new_fvec(2);

    if (!input || !output) {
        LOGE("Failed to create vectors");
        if (input) del_fvec(input);
        if (output) del_fvec(output);
        del_aubio_tempo(tempo);
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return nullptr;
    }

    std::vector<jlong> beat_timestamps;
    int frames_processed = 0;

    LOGD("Starting audio processing...");

    // Process audio in chunks
    for (int i = 0; i < length - hop_size; i += hop_size) {
        // Fill input buffer
        for (int j = 0; j < hop_size && (i + j) < length; j++) {
            input->data[j] = data[i + j];
        }

        // Run tempo detection
        aubio_tempo_do(tempo, input, output);

        // Check for beat
        if (output->data[0] != 0) {
            float position_secs = aubio_tempo_get_last_s(tempo);
            jlong position_ms = (jlong)(position_secs * 1000.0f);
            beat_timestamps.push_back(position_ms);
            LOGD("Beat detected at %.3f seconds (%lld ms)", position_secs, (long long)position_ms);
        }

        frames_processed++;
    }

    LOGD("Processed %d frames", frames_processed);

    // Get global BPM estimate
    float bpm = aubio_tempo_get_bpm(tempo);

    LOGD("=== Analysis Complete ===");
    LOGD("BPM: %.2f, Beats: %zu", bpm, beat_timestamps.size());

    // Cleanup Native Resources
    del_aubio_tempo(tempo);
    del_fvec(input);
    del_fvec(output);
    env->ReleaseFloatArrayElements(pcmData, data, 0);

    // Create Java AudioAnalysisResult Object
    jclass resultClass = env->FindClass("com/dd3boh/outertune/utils/analysis/AudioAnalysisResult");
    if (!resultClass) {
        LOGE("Failed to find AudioAnalysisResult class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(FJ[J)V");
    if (!constructor) {
        LOGE("Failed to find constructor");
        return nullptr;
    }

    jlongArray beatGrid = env->NewLongArray((jsize)beat_timestamps.size());
    if (!beatGrid) {
        LOGE("Failed to create beat grid array");
        return nullptr;
    }

    if (!beat_timestamps.empty()) {
        env->SetLongArrayRegion(beatGrid, 0, (jsize)beat_timestamps.size(), beat_timestamps.data());
    }

    jlong firstBeatMs = beat_timestamps.empty() ? 0 : beat_timestamps[0];

    jobject resultObj = env->NewObject(resultClass, constructor, (jfloat)bpm, firstBeatMs, beatGrid);

    if (!resultObj) {
        LOGE("Failed to create result object");
        return nullptr;
    }

    LOGD("=== analyzeBpm RETURNING SUCCESS ===");
    return resultObj;
}
