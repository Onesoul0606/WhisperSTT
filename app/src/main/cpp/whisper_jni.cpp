#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static struct whisper_context* g_whisper = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_loadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);

    LOGD("Loading whisper model from: %s", path);

    // 기존 모델이 있다면 해제
    if (g_whisper != nullptr) {
        whisper_free(g_whisper);
        g_whisper = nullptr;
    }

    // 새 모델 로드
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Android에서는 CPU 사용

    g_whisper = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_whisper == nullptr) {
        LOGE("Failed to load whisper model");
        return JNI_FALSE;
    }

    LOGD("Whisper model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_transcribe(JNIEnv *env, jobject thiz, jfloatArray audio_data) {
    if (g_whisper == nullptr) {
        LOGE("Whisper model not loaded");
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    // Java float 배열을 C++ vector로 변환
    jsize length = env->GetArrayLength(audio_data);
    jfloat* elements = env->GetFloatArrayElements(audio_data, nullptr);

    if (elements == nullptr || length == 0) {
        LOGE("Invalid audio data");
        return env->NewStringUTF("");
    }

    std::vector<float> audio_buffer(elements, elements + length);

    env->ReleaseFloatArrayElements(audio_data, elements, JNI_ABORT);

    LOGD("Transcribing audio with %d samples", length);

    // Whisper 파라미터 설정 (실시간 처리 최적화)
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // 기본 설정 (실시간 STT 최적화)
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    wparams.language = "en"; // 영어로 설정 (나중에 동적으로 변경 가능)
    wparams.n_threads = 8;                  // 6 → 8으로 증가 (더 많은 CPU 코어 활용)
    wparams.offset_ms = 0;
    wparams.duration_ms = 0;

    // 단어 단위 실시간 인식을 위한 설정
    wparams.n_max_text_ctx = 256;            // 512 → 256으로 감소 (빠른 처리)
    wparams.suppress_blank = true;           // 빈 결과 억제
    wparams.suppress_nst = false;            // 음성이 아닌 토큰 억제 해제

    // 단어 단위 인식 최적화
    wparams.audio_ctx = 0;                   // 오디오 컨텍스트 (0 = 자동)
    wparams.temperature = 0.0f;              // 온도 0으로 설정 (추론 억제)
    wparams.temperature_inc = 0.0f;          // 온도 증가 없음
    wparams.no_speech_thold = 0.5f;          // 0.6 → 0.5로 더 민감 (음성 없음 임계값)
    wparams.logprob_thold = -1.2f;           // -1.0 → -1.2로 더 관대 (로그 확률 임계값)
    wparams.entropy_thold = 2.8f;            // 2.4 → 2.8로 더 관대 (압축 비율 임계값)
    
    // 추가 추론 억제 설정
    wparams.initial_prompt = nullptr;        // 초기 프롬프트 없음
    wparams.prompt_tokens = nullptr;         // 프롬프트 토큰 없음
    wparams.prompt_n_tokens = 0;             // 프롬프트 토큰 수 0

    // 음성 인식 실행
    int result = whisper_full(g_whisper, wparams, audio_buffer.data(), audio_buffer.size());

    if (result != 0) {
        LOGE("Failed to process audio, error: %d", result);
        return env->NewStringUTF("");
    }

    // 결과 텍스트 추출 (공백 처리 개선)
    std::string transcription;
    const int n_segments = whisper_full_n_segments(g_whisper);

    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper, i);
        if (text != nullptr) {
            std::string segment_text(text);

            // 앞뒤 공백 제거
            size_t start = segment_text.find_first_not_of(" \t\n\r");
            if (start != std::string::npos) {
                size_t end = segment_text.find_last_not_of(" \t\n\r");
                segment_text = segment_text.substr(start, end - start + 1);

                if (!segment_text.empty()) {
                    if (!transcription.empty()) {
                        transcription += " ";
                    }
                    transcription += segment_text;
                }
            }
        }
    }

    LOGD("Transcription result: '%s' (%d segments)", transcription.c_str(), n_segments);

    return env->NewStringUTF(transcription.c_str());
}

JNIEXPORT void JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_releaseModel(JNIEnv *env, jobject thiz) {
    if (g_whisper != nullptr) {
        whisper_free(g_whisper);
        g_whisper = nullptr;
        LOGD("Whisper model released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_isModelLoaded(JNIEnv *env, jobject thiz) {
    return g_whisper != nullptr ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"