#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// Include Aubio headers
extern "C" {
#include "aubio.h"
}

#define TAG "OuterTune-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dd3boh_outertune_utils_analysis_AudioAnalyzer_analyzeBpm(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray pcmData,
        jint sampleRate) {

    // 1. Convert Java float array to C++
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);
    jsize length = env->GetArrayLength(pcmData);

    // 2. Setup Aubio Tempo
    uint_t win_size = 1024;
    uint_t hop_size = 512;
    aubio_tempo_t* tempo = new_aubio_tempo("default", win_size, hop_size, (uint_t)sampleRate);

    if (!tempo) {
        LOGD("Failed to create aubio tempo object");
        env->ReleaseFloatArrayElements(pcmData, data, 0);
        return nullptr;
    }

    // 3. Process Audio
    fvec_t* input = new_fvec(hop_size);
    fvec_t* output = new_fvec(2); // [0] = is_beat, [1] = confidence

    std::vector<float> beat_timestamps;

    // Iterate through the audio buffer
    for (int i = 0; i < length - hop_size; i += hop_size) {
        // Fill input buffer
        for (int j = 0; j < hop_size; j++) {
            input->data[j] = data[i + j];
        }

        // Run tempo detection
        aubio_tempo_do(tempo, input, output);

        // Check for beat
        if (output->data[0] != 0) {
            // Get beat position in seconds (samples / sample_rate)
            float position_secs = aubio_tempo_get_last_s(tempo);
            // Convert to ms
            beat_timestamps.push_back(position_secs * 1000.0f);
        }
    }

    // 4. Get global BPM estimate
    float bpm = aubio_tempo_get_bpm(tempo);

    LOGD("Analysis Complete. BPM: %f, Beats found: %lu", bpm, beat_timestamps.size());

    // 5. Cleanup
    del_aubio_tempo(tempo);
    del_fvec(input);
    del_fvec(output);
    env->ReleaseFloatArrayElements(pcmData, data, 0);

    // 6. Return Result (BPM + First Beat Timestamp)
    // We return a float array: [BPM, FirstBeatMs, Confidence...]
    std::vector<float> result;
    result.push_back(bpm);
    if (!beat_timestamps.empty()) {
        result.push_back(beat_timestamps[0]); // First beat phase
    } else {
        result.push_back(0.0f);
    }

    jfloatArray outputArray = env->NewFloatArray(result.size());
    env->SetFloatArrayRegion(outputArray, 0, result.size(), result.data());

    return outputArray;
}