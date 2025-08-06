#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <atomic>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 스레드 안전성을 위한 전역 변수들
static struct whisper_context* g_whisper = nullptr;
static std::mutex g_whisper_mutex;
static std::atomic<bool> g_whisper_initialized{false};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_loadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return JNI_FALSE;
    }

    LOGD("Loading whisper model from: %s", path);

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    
    // 기존 모델이 있다면 해제
    if (g_whisper != nullptr) {
        whisper_free(g_whisper);
        g_whisper = nullptr;
        g_whisper_initialized = false;
    }

    // 새 모델 로드 - 실시간 처리 최적화
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Android에서는 CPU 사용

    g_whisper = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_whisper == nullptr) {
        LOGE("Failed to load whisper model");
        return JNI_FALSE;
    }

    g_whisper_initialized = true;
    LOGD("Whisper model loaded successfully");
    return JNI_TRUE;
}

// 기존 transcribe 메서드 (호환성 유지)
JNIEXPORT jstring JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_transcribe(JNIEnv *env, jobject thiz, jfloatArray audio_data) {
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model not loaded");
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    // Java float 배열을 C++ vector로 변환
    jsize length = env->GetArrayLength(audio_data);
    if (length <= 0) {
        LOGE("Invalid audio data length: %d", length);
        return env->NewStringUTF("");
    }

    jfloat* elements = env->GetFloatArrayElements(audio_data, nullptr);
    if (elements == nullptr) {
        LOGE("Failed to get audio data elements");
        return env->NewStringUTF("");
    }

    // 메모리 안전성을 위한 벡터 복사
    std::vector<float> audio_buffer;
    try {
        audio_buffer.assign(elements, elements + length);
    } catch (const std::exception& e) {
        LOGE("Failed to copy audio data: %s", e.what());
        env->ReleaseFloatArrayElements(audio_data, elements, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // 메모리 해제 - JNI_ABORT 대신 0 사용
    env->ReleaseFloatArrayElements(audio_data, elements, 0);

    LOGD("Transcribing audio with %d samples", length);

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model became invalid during transcription");
        return env->NewStringUTF("ERROR: Model became invalid");
    }

    // Whisper 파라미터 설정 - 안정성 향상
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // 실시간 스트리밍 기본 설정
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;  // 스레드 수 제한
    wparams.offset_ms = 0;
    wparams.duration_ms = 0;

    // 안정성 향상을 위한 설정
    wparams.n_max_text_ctx = 128;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.temperature = 0.0f;
    wparams.temperature_inc = 0.0f;
    wparams.no_speech_thold = 0.4f;
    wparams.logprob_thold = -1.5f;
    wparams.entropy_thold = 3.0f;

    // 빠른 디코딩
    wparams.beam_search.beam_size = 1;
    wparams.greedy.best_of = 1;
    wparams.audio_ctx = 0;
    wparams.token_timestamps = false;
    wparams.split_on_word = true;

    // 음성 인식 실행
    int result = whisper_full(g_whisper, wparams, audio_buffer.data(), audio_buffer.size());

    if (result != 0) {
        LOGE("Failed to process audio, error: %d", result);
        return env->NewStringUTF("");
    }

    // 결과 텍스트 추출
    std::string transcription;
    const int n_segments = whisper_full_n_segments(g_whisper);

    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper, i);
        if (text != nullptr) {
            std::string segment_text(text);
            size_t start = segment_text.find_first_not_of(" \t\n\r");
            if (start != std::string::npos) {
                size_t end = segment_text.find_last_not_of(" \t\n\r");
                segment_text = segment_text.substr(start, end - start + 1);

                if (!segment_text.empty() && 
                    segment_text != "[BLANK_AUDIO]" && 
                    segment_text.find_first_not_of(" ") != std::string::npos) {
                    
                    if (!transcription.empty()) {
                        transcription += " ";
                    }
                    transcription += segment_text;
                }
            }
        }
    }

    if (transcription.empty() || transcription.length() < 2) {
        LOGD("Empty or too short transcription result");
        return env->NewStringUTF("");
    }

    LOGD("Transcription result: '%s' (%d segments)", transcription.c_str(), n_segments);
    return env->NewStringUTF(transcription.c_str());
}

// ✨ 새로 추가: 프롬프트와 함께 전사하는 메서드 (Step 1의 핵심)
JNIEXPORT jstring JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_transcribeWithPrompt(JNIEnv *env, jobject thiz, jfloatArray audio_data, jstring prompt) {
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model not loaded");
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    // 오디오 데이터 처리
    jsize length = env->GetArrayLength(audio_data);
    if (length <= 0) {
        LOGE("Invalid audio data length: %d", length);
        return env->NewStringUTF("");
    }

    jfloat* elements = env->GetFloatArrayElements(audio_data, nullptr);
    if (elements == nullptr) {
        LOGE("Failed to get audio data elements");
        return env->NewStringUTF("");
    }

    std::vector<float> audio_buffer;
    try {
        audio_buffer.assign(elements, elements + length);
    } catch (const std::exception& e) {
        LOGE("Failed to copy audio data: %s", e.what());
        env->ReleaseFloatArrayElements(audio_data, elements, JNI_ABORT);
        return env->NewStringUTF("");
    }

    env->ReleaseFloatArrayElements(audio_data, elements, 0);

    // 프롬프트 처리
    const char* prompt_str = nullptr;
    if (prompt != nullptr) {
        prompt_str = env->GetStringUTFChars(prompt, nullptr);
    }

    LOGD("Transcribing with prompt: '%.50s%s'", 
         prompt_str ? prompt_str : "none", 
         (prompt_str && strlen(prompt_str) > 50) ? "..." : "");

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model became invalid during transcription");
        if (prompt_str) env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("ERROR: Model became invalid");
    }

    // Whisper 파라미터 설정 - 스트리밍 최적화
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // 실시간 스트리밍 설정
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;

    // 스트리밍 최적화 (논문 기반)
    wparams.n_max_text_ctx = 256;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.temperature = 0.0f;
    wparams.temperature_inc = 0.0f;
    wparams.no_speech_thold = 0.4f;
    wparams.logprob_thold = -1.2f;
    wparams.entropy_thold = 3.0f;

    // 빠른 디코딩
    wparams.beam_search.beam_size = 1;
    wparams.greedy.best_of = 1;
    wparams.audio_ctx = 0;
    wparams.token_timestamps = false;
    wparams.split_on_word = true;

    // 프롬프트 설정
    if (prompt_str != nullptr && strlen(prompt_str) > 0) {
        wparams.initial_prompt = prompt_str;
    }

    // 음성 인식 실행
    int result = whisper_full(g_whisper, wparams, audio_buffer.data(), audio_buffer.size());

    if (prompt_str) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
    }

    if (result != 0) {
        LOGE("Failed to process audio with prompt, error: %d", result);
        return env->NewStringUTF("");
    }

    // 결과 추출
    std::string transcription;
    const int n_segments = whisper_full_n_segments(g_whisper);

    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper, i);
        if (text != nullptr) {
            std::string segment_text(text);
            size_t start = segment_text.find_first_not_of(" \t\n\r");
            if (start != std::string::npos) {
                size_t end = segment_text.find_last_not_of(" \t\n\r");
                segment_text = segment_text.substr(start, end - start + 1);

                if (!segment_text.empty() && 
                    segment_text != "[BLANK_AUDIO]" && 
                    segment_text.find_first_not_of(" ") != std::string::npos) {
                    
                    if (!transcription.empty()) {
                        transcription += " ";
                    }
                    transcription += segment_text;
                }
            }
        }
    }

    if (transcription.empty() || transcription.length() < 2) {
        LOGD("Empty or too short transcription result with prompt");
        return env->NewStringUTF("");
    }

    LOGD("Transcription with prompt result: '%s' (%d segments)", transcription.c_str(), n_segments);
    return env->NewStringUTF(transcription.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_transcribeRealtime(JNIEnv *env, jobject thiz, jfloatArray audio_data) {
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model not loaded");
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    // 오디오 데이터 처리
    jsize length = env->GetArrayLength(audio_data);
    if (length <= 0) {
        LOGE("Invalid audio data length: %d", length);
        return env->NewStringUTF("");
    }

    jfloat* elements = env->GetFloatArrayElements(audio_data, nullptr);
    if (elements == nullptr) {
        LOGE("Failed to get audio data elements");
        return env->NewStringUTF("");
    }

    std::vector<float> audio_buffer;
    try {
        audio_buffer.assign(elements, elements + length);
    } catch (const std::exception& e) {
        LOGE("Failed to copy audio data: %s", e.what());
        env->ReleaseFloatArrayElements(audio_data, elements, JNI_ABORT);
        return env->NewStringUTF("");
    }

    env->ReleaseFloatArrayElements(audio_data, elements, 0);

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    
    if (!g_whisper_initialized || g_whisper == nullptr) {
        LOGE("Whisper model became invalid during realtime transcription");
        return env->NewStringUTF("ERROR: Model became invalid");
    }

    // 실시간 최적화 파라미터 (매우 빠른 처리)
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;  // 스레드 수 제한으로 안정성 향상

    // 실시간 처리를 위한 안정적인 설정
    wparams.n_max_text_ctx = 64;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.temperature = 0.0f;
    wparams.no_speech_thold = 0.3f;
    wparams.logprob_thold = -2.0f;
    wparams.entropy_thold = 3.5f;

    // 최대 속도 설정
    wparams.beam_search.beam_size = 1;
    wparams.greedy.best_of = 1;
    wparams.audio_ctx = 256;
    wparams.token_timestamps = false;

    // 음성 인식 실행
    int result = whisper_full(g_whisper, wparams, audio_buffer.data(), audio_buffer.size());

    if (result != 0) {
        LOGE("Failed to process realtime audio, error: %d", result);
        return env->NewStringUTF("");
    }

    // 결과 추출
    std::string transcription;
    const int n_segments = whisper_full_n_segments(g_whisper);

    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper, i);
        if (text != nullptr) {
            std::string segment_text(text);
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

    return env->NewStringUTF(transcription.c_str());
}

JNIEXPORT void JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_releaseModel(JNIEnv *env, jobject thiz) {
    (void)env;  // 사용하지 않는 매개변수 경고 방지
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    
    if (g_whisper != nullptr) {
        whisper_free(g_whisper);
        g_whisper = nullptr;
        g_whisper_initialized = false;
        LOGD("Whisper model released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hiclone_whisperstt_WhisperSTT_isModelLoaded(JNIEnv *env, jobject thiz) {
    (void)env;  // 사용하지 않는 매개변수 경고 방지
    (void)thiz; // 사용하지 않는 매개변수 경고 방지
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    return (g_whisper != nullptr && g_whisper_initialized) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"