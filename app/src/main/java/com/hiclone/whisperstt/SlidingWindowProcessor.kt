package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt

class SlidingWindowProcessor(
    private val whisperSTT: WhisperSTT,
    private val onTranscriptionResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "SlidingWindowProcessor"
        private const val SAMPLE_RATE = 16000
        
        // 단어 단위 실시간 인식을 위한 슬라이딩 윈도우 설정
        private const val STEP_MS = 500               // --step-ms 500 (0.5초 간격)
        private const val LENGTH_MS = 1500            // --length-ms 1500 (1.5초 길이)
        private const val KEEP_MS = 300               // --keep-ms 300 (0.3초 유지)
        
        private const val STEP_SECONDS = STEP_MS / 1000f
        private const val LENGTH_SECONDS = LENGTH_MS / 1000f
        private const val KEEP_SECONDS = KEEP_MS / 1000f
        
        private const val WINDOW_SIZE_SAMPLES = (SAMPLE_RATE * LENGTH_SECONDS).toInt()
        private const val STEP_SAMPLES = (SAMPLE_RATE * STEP_SECONDS).toInt()
        private const val KEEP_SAMPLES = (SAMPLE_RATE * KEEP_SECONDS).toInt()

        // 개선된 VAD 임계값 - 더 엄격한 조건
        private const val VOICE_THRESHOLD = 0.025f   // 0.015 → 0.025로 더 엄격하게
        private const val MIN_VOICE_SAMPLES = SAMPLE_RATE / 4 // 0.125초 → 0.25초로 더 길게
        private const val MIN_RMS_THRESHOLD = 0.03f  // 최소 RMS 임계값 추가
        private const val MIN_VOICE_RATIO = 0.15f    // 최소 음성 비율 임계값 추가
    }

    private val audioBuffer = ConcurrentLinkedQueue<Short>()
    private var totalSamples = 0
    private var processingJob: Job? = null
    private var isProcessing = false
    private val performanceMonitor = RealtimePerformanceMonitor() // 성능 모니터 추가
    private var isCurrentlyProcessing = false // 중복 처리 방지
    
    // 중복 처리 방지를 위한 추가 변수들
    private var lastProcessedTime = 0L
    private var lastTranscriptionResult = ""
    private val MIN_PROCESSING_INTERVAL = 2000L // 2초 최소 간격

    fun start() {
        if (isProcessing) return

        isProcessing = true
        processingJob = CoroutineScope(Dispatchers.IO).launch {
            processAudioLoop()
        }
        Log.i(TAG, "Sliding window processor started")
    }

    fun stop() {
        isProcessing = false
        processingJob?.cancel()
        audioBuffer.clear()
        totalSamples = 0
        isCurrentlyProcessing = false
        lastProcessedTime = 0L
        lastTranscriptionResult = ""
        Log.i(TAG, "Sliding window processor stopped")
    }

    fun addAudioData(audioData: ShortArray) {
        // 새로운 오디오 데이터를 버퍼에 추가
        audioData.forEach { sample ->
            audioBuffer.offer(sample)
        }
        totalSamples += audioData.size
    }

    private suspend fun processAudioLoop() {
        while (isProcessing) {
            try {
                if (totalSamples >= WINDOW_SIZE_SAMPLES) {
                    processWindow()
                } else {
                    delay(50) // 100ms → 50ms로 단축 (더 빠른 반응)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in processing loop", e)
                delay(500) // 1000ms → 500ms로 단축
            }
        }
    }

    private suspend fun processWindow() {
        // 중복 처리 방지
        if (isCurrentlyProcessing) {
            Log.d(TAG, "Already processing, skipping...")
            return
        }

        // 시간 기반 중복 처리 방지
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) {
            Log.d(TAG, "Too soon since last processing, skipping...")
            return
        }

        // 윈도우 크기만큼 데이터 추출
        val windowData = ShortArray(WINDOW_SIZE_SAMPLES)
        val tempList = mutableListOf<Short>()

        // ConcurrentLinkedQueue에서 윈도우 크기만큼 데이터 가져오기
        for (i in 0 until WINDOW_SIZE_SAMPLES) {
            val sample = audioBuffer.poll()
            if (sample != null) {
                tempList.add(sample)
            } else {
                break
            }
        }

        if (tempList.size < WINDOW_SIZE_SAMPLES) {
            // 데이터가 부족하면 다시 큐에 넣고 대기
            tempList.forEach { audioBuffer.offer(it) }
            return
        }

        tempList.toShortArray().copyInto(windowData)

        // 개선된 VAD 체크
        if (hasVoiceActivity(windowData)) {
            Log.d(TAG, "Voice detected, processing ${LENGTH_SECONDS}s window...")
            
            isCurrentlyProcessing = true
            lastProcessedTime = currentTime
            
            try {
                // 성능 모니터와 함께 Whisper 처리
                val result = performanceMonitor.recordProcessing(LENGTH_SECONDS.toFloat()) {
                    withContext(Dispatchers.IO) {
                        whisperSTT.transcribeAudioSync(windowData)
                    }
                }

                // 결과 필터링 및 중복 방지
                if (result.isNotEmpty() && result != "[BLANK_AUDIO]" && result != lastTranscriptionResult) {
                    Log.i(TAG, "Transcription result: $result")
                    lastTranscriptionResult = result
                    withContext(Dispatchers.Main) {
                        onTranscriptionResult(result)
                    }
                } else if (result == "[BLANK_AUDIO]") {
                    Log.d(TAG, "Blank audio detected, skipping result")
                } else if (result == lastTranscriptionResult) {
                    Log.d(TAG, "Duplicate result detected, skipping")
                }
            } finally {
                isCurrentlyProcessing = false
            }
        } else {
            Log.d(TAG, "No voice activity detected, skipping...")
        }

        // keep 부분을 다시 큐에 넣기 (문서 기준)
        val keepData = windowData.takeLast(KEEP_SAMPLES)
        keepData.forEach { audioBuffer.offer(it) }

        // 전체 샘플 수 업데이트 (step 크기만큼 감소)
        totalSamples -= STEP_SAMPLES
    }

    private fun hasVoiceActivity(audioData: ShortArray): Boolean {
        if (audioData.isEmpty()) return false

        // 개선된 VAD 알고리즘 (문서 기준 --vad-thold 0.5 적용)
        val sampleStep = 2 // 2개 중 1개만 계산 (더 정확한 분석)
        var sum = 0.0
        var voiceSamples = 0
        var totalSampled = 0
        var maxAmplitude = 0.0

        for (i in audioData.indices step sampleStep) {
            val normalized = audioData[i].toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
            
            val absValue = kotlin.math.abs(normalized)
            if (absValue > maxAmplitude) {
                maxAmplitude = absValue
            }

            if (absValue > VOICE_THRESHOLD) {
                voiceSamples++
            }
            totalSampled++
        }

        val rms = sqrt(sum / totalSampled)
        val voiceRatio = voiceSamples.toFloat() / totalSampled

        // 개선된 VAD 조건 - 더 엄격한 조건들
        val hasVoice = rms > MIN_RMS_THRESHOLD && 
                      voiceRatio > MIN_VOICE_RATIO && 
                      maxAmplitude > VOICE_THRESHOLD * 1.2f &&
                      voiceSamples > MIN_VOICE_SAMPLES

        if (hasVoice) {
            Log.d(TAG, "VAD: RMS=${String.format("%.4f", rms)}, VoiceRatio=${String.format("%.3f", voiceRatio)}, MaxAmp=${String.format("%.4f", maxAmplitude)}, HasVoice=true")
        } else {
            Log.d(TAG, "VAD: RMS=${String.format("%.4f", rms)}, VoiceRatio=${String.format("%.3f", voiceRatio)}, MaxAmp=${String.format("%.4f", maxAmplitude)}, HasVoice=false")
        }

        return hasVoice
    }

    fun release() {
        stop()
    }
}