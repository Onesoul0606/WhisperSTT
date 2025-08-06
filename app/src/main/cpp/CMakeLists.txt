cmake_minimum_required(VERSION 3.22.1)

project("whisperstt")

# whisper.cpp 빌드 옵션 설정 (기존 + 최적화 추가)
set(GGML_USE_ANDROID ON)
set(BUILD_SHARED_LIBS OFF)

# CMake 경고 억제 설정
set(CMAKE_WARN_DEPRECATED OFF CACHE BOOL "" FORCE)
set(CMAKE_SUPPRESS_DEVELOPER_WARNINGS ON CACHE BOOL "" FORCE)

# 안정성 향상을 위한 컴파일러 플래그 (경고 억제 포함)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -fno-finite-math-only -funroll-loops -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wno-unused-parameter -Wno-format -Wno-format-security")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fno-finite-math-only -funroll-loops -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wno-unused-parameter -Wno-format -Wno-format-security")

# whisper.cpp 서브디렉토리 추가
add_subdirectory(whisper.cpp)

# whisper 라이브러리에 대한 경고 억제 (외부 라이브러리)
if(TARGET whisper)
    target_compile_options(whisper PRIVATE
        -Wno-all
        -Wno-extra
        -Wno-format
        -Wno-format-security
        -Wno-unused-parameter
        -Wno-unused-variable
        -w
    )
endif()

# JNI 래퍼 라이브러리 생성
add_library(whisperstt
        SHARED
        whisper_jni.cpp)

# 컴파일 옵션 (안정성 향상)
target_compile_options(whisperstt PRIVATE
        -std=c++17
        -fexceptions
        -frtti
        -O2
        -fno-finite-math-only
        -funroll-loops
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
        -DNDEBUG
        -Wall
        -Wextra
        -Wno-unused-parameter
        -Wno-format
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

# 메모리 안전성을 위한 링커 플래그
set_target_properties(whisperstt PROPERTIES
    LINK_FLAGS "-Wl,-z,relro -Wl,-z,now"
)