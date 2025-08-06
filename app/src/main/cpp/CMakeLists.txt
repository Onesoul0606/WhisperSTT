cmake_minimum_required(VERSION 3.22.1)

project("whisperstt")

# whisper.cpp 빌드 옵션 설정 (기존 + 최적화 추가)
set(GGML_USE_ANDROID ON)
set(BUILD_SHARED_LIBS OFF)

# 성능 최적화 플래그 추가 (whisper.cpp 호환성을 위해 -fno-finite-math-only 추가)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fno-finite-math-only -funroll-loops")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fno-finite-math-only -funroll-loops")

# whisper.cpp 서브디렉토리 추가
add_subdirectory(whisper.cpp)

# JNI 래퍼 라이브러리 생성
add_library(whisperstt
        SHARED
        whisper_jni.cpp)

# 컴파일 옵션 (기존 + 최적화 추가, whisper.cpp 호환성 고려)
target_compile_options(whisperstt PRIVATE
        -std=c++17
        -fexceptions
        -frtti
        -O3
        -fno-finite-math-only
        -funroll-loops
        -DNDEBUG
)

# 헤더 파일 경로 설정
target_include_directories(whisperstt PRIVATE
        whisper.cpp
        whisper.cpp/ggml/include
)

# 라이브러리 링크
target_link_libraries(whisperstt
        whisper
        android
        log
)

# Android 로그 라이브러리 추가
find_library(log-lib log)
target_link_libraries(whisperstt ${log-lib})