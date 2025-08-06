package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class SlidingWindowProcessor(
    private val whisperSTT: WhisperSTT,
    private val onResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "SlidingWindowProcessor"
        private const val SAMPLE_RATE = 16000
        private const val LENGTH_SECONDS = 3 // 3초 윈도우로 증가 (더 나은 정확도)
        private const val WINDOW_SIZE_SAMPLES = SAMPLE_RATE * LENGTH_SECONDS
        private const val SLIDE_SIZE_SAMPLES = SAMPLE_RATE * 1 // 1초씩 슬라이드
        private const val MIN_PROCESSING_INTERVAL = 800L // 800ms 최소 간격 (더 빠른 응답)
        private const val SILENCE_THRESHOLD = 0.015 // VAD 임계값 조정
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private val isCurrentlyProcessing = AtomicBoolean(false)
    private val audioBuffer = ConcurrentLinkedQueue<Short>()
    private var lastProcessedTime = 0L
    private val performanceMonitor = RealtimePerformanceMonitor()

    fun addAudioData(audioData: ShortArray) {
        if (!isRunning.get()) return

        // 버퍼에 오디오 데이터 추가
        audioData.forEach { sample ->
            audioBuffer.offer(sample)
        }

        // 버퍼 크기 제한 (메모리 누수 방지)
        while (audioBuffer.size > WINDOW_SIZE_SAMPLES * 2) {
            audioBuffer.poll()
        }

        // 처리 조건 확인 (슬라이딩 윈도우 방식)
        if (audioBuffer.size >= WINDOW_SIZE_SAMPLES && 
            !isCurrentlyProcessing.get() &&
            System.currentTimeMillis() - lastProcessedTime > MIN_PROCESSING_INTERVAL) {
            
            scope.launch {
                processWindow()
            }
        }
    }

    private suspend fun processWindow() {
        // 중복 처리 방지
        if (!isCurrentlyProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Already processing, skipping...")
            return
        }

        // 시간 기반 중복 처리 방지
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) {
            Log.d(TAG, "Too soon since last processing, skipping...")
            isCurrentlyProcessing.set(false)
            return
        }

        try {
            // 슬라이딩 윈도우 방식으로 데이터 추출
            val windowData = ShortArray(WINDOW_SIZE_SAMPLES)
            val tempList = mutableListOf<Short>()

            // 윈도우 크기만큼 데이터 가져오기 (슬라이딩을 위해 일부 보존)
            val bufferSnapshot = audioBuffer.toList()
            
            if (bufferSnapshot.size < WINDOW_SIZE_SAMPLES) {
                isCurrentlyProcessing.set(false)
                return
            }
            
            // 윈도우 데이터 복사
            bufferSnapshot.take(WINDOW_SIZE_SAMPLES).toShortArray().copyInto(windowData)
            
            // 슬라이딩: SLIDE_SIZE만큼 제거 (나머지는 다음 윈도우를 위해 보존)
            repeat(minOf(SLIDE_SIZE_SAMPLES, audioBuffer.size)) {
                audioBuffer.poll()
            }

            // 중지 상태 확인
            if (!isRunning.get()) return
            
            // 개선된 VAD 체크
            if (hasVoiceActivity(windowData)) {
                Log.d(TAG, "Voice detected, processing ${LENGTH_SECONDS}s window...")
                
                // 중지 상태 다시 확인
                if (!isRunning.get()) return
                
                lastProcessedTime = currentTime
                
                try {
                    // 성능 모니터와 함께 Whisper 처리
                    val result = performanceMonitor.recordProcessing(LENGTH_SECONDS.toFloat()) {
                        withContext(Dispatchers.IO) {
                            // 처리 전에도 중지 상태 확인
                            if (!isRunning.get()) return@withContext ""
                            whisperSTT.transcribeAudioSync(windowData)
                        }
                    }

                    // 결과 처리 전에도 중지 상태 확인
                    if (result.isNotEmpty() && !result.startsWith("ERROR") && isRunning.get()) {
                        onResult(result)
                        Log.d(TAG, "Transcription result: $result")
                    } else {
                        Log.d(TAG, "Empty or error result: $result")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during transcription", e)
                }
            } else {
                Log.d(TAG, "No voice activity detected, skipping...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processWindow", e)
        } finally {
            isCurrentlyProcessing.set(false)
        }
    }

    private fun hasVoiceActivity(audioData: ShortArray): Boolean {
        if (audioData.isEmpty()) return false

        try {
            // 간단한 VAD: RMS 기반 음성 활동 감지
            var sum = 0.0
            var count = 0
            
            for (sample in audioData) {
                val amplitude = sample.toDouble() / Short.MAX_VALUE
                sum += amplitude * amplitude
                count++
            }
            
            if (count == 0) return false
            
            val rms = Math.sqrt(sum / count)
            
            return rms > SILENCE_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "Error in VAD calculation", e)
            return false
        }
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Started")
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            audioBuffer.clear()
            
            // 현재 처리 중인 작업 완료 대기 (짧은 시간)
            var waitCount = 0
            while (isCurrentlyProcessing.get() && waitCount < 10) {
                Thread.sleep(50) // 50ms씩 최대 500ms 대기
                waitCount++
            }
            
            // 즉시 모든 코루틴 취소
            scope.cancel()
            
            Log.d(TAG, "Stopped")
        }
    }

    fun release() {
        stop()
    }
}