# WhisperSTT

Android 앱에서 실시간 음성 인식을 위한 Whisper 기반 Speech-to-Text 애플리케이션입니다.

## 주요 기능

- 🎤 실시간 음성 캡처 및 처리
- 🧠 Whisper 모델을 사용한 음성 인식
- 📊 실시간 성능 모니터링
- 🔄 슬라이딩 윈도우 기반 음성 처리
- 📱 Android 네이티브 UI

## 기술 스택

- **언어**: Kotlin
- **음성 인식**: Whisper.cpp
- **UI**: Jetpack Compose
- **오디오 처리**: Android AudioRecord API
- **빌드 도구**: Gradle

## 프로젝트 구조

```
app/src/main/
├── java/com/hiclone/whisperstt/
│   ├── MainActivity.kt          # 메인 액티비티
│   ├── AudioCapture.kt          # 오디오 캡처 관리
│   ├── WhisperSTT.kt           # Whisper STT 핵심 로직
│   ├── SlidingWindowProcessor.kt # 슬라이딩 윈도우 처리
│   └── RealtimePerformanceMonitor.kt # 성능 모니터링
├── cpp/
│   ├── whisper_jni.cpp         # JNI 인터페이스
│   └── whisper.cpp/            # Whisper 라이브러리
└── assets/models/
    └── ggml-tiny.en.bin        # Whisper 모델 파일
```

## 설치 및 실행

1. 프로젝트 클론
```bash
git clone https://github.com/YOUR_USERNAME/WhisperSTT.git
cd WhisperSTT
```

2. Android Studio에서 프로젝트 열기

3. Gradle 동기화 및 빌드

4. Android 디바이스에서 실행

## 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다.

## 기여

버그 리포트나 기능 제안은 GitHub Issues를 통해 해주세요. 