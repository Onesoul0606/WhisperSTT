package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * 효율적인 침묵 감지 및 결과 출력 관리
 * - 500ms 간격으로 침묵 체크 (기존 40ms → 12.5배 효율화)
 * - VAD 기반 정확한 음성 활동 감지
 * - 적절한 타이밍에 결과 출력 트리거
 */
class SilenceManager(
    private val onTempResultTrigger: () -> Unit,   // 임시 결과 출력 요청
    private val onFinalResultTrigger: () -> Unit   // 최종 결과 출력 요청
) {
    companion object {
        private const val TAG = "SilenceManager"
        private const val CHECK_INTERVAL = 500L           // 500ms마다 체크
        private const val SILENCE_THRESHOLD = 0.015f      // VAD 임계값
        private const val TEMP_RESULT_SILENCE = 2000L     // 2초 침묵 → 임시 결과 출력
        private const val FINAL_RESULT_SILENCE = 3000L    // 3초 침묵 → 최종 결과 출력
        private const val SAMPLE_RATE = 16000
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    
    // 음성 활동 추적
    private var lastVoiceActivityTime = 0L
    private var lastTempResultTime = 0L
    private var lastFinalResultTime = 0L
    
    // 오디오 버퍼 (VAD용)
    private val recentAudioBuffer = mutableListOf<Float>()
    private val maxBufferSize = SAMPLE_RATE / 2 // 0.5초 분량
    
    fun start() {
        isRunning = true
        lastVoiceActivityTime = System.currentTimeMillis()
        lastTempResultTime = System.currentTimeMillis()
        lastFinalResultTime = System.currentTimeMillis()
        
        // 🚀 효율적인 주기적 체크
        scope.launch {
            while (isRunning) {
                try {
                    checkSilenceAndTrigger()
                    delay(CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in silence checking", e)
                }
            }
        }
        
        Log.d(TAG, "SilenceManager started with ${CHECK_INTERVAL}ms interval")
    }
    
    fun addAudioData(audioData: FloatArray) {
        if (!isRunning) return
        
        // 최근 오디오 버퍼 업데이트
        synchronized(recentAudioBuffer) {
            audioData.forEach { sample ->
                if (sample.isFinite()) {
                    recentAudioBuffer.add(sample)
                }
            }
            
            // 버퍼 크기 제한
            while (recentAudioBuffer.size > maxBufferSize) {
                recentAudioBuffer.removeAt(0)
            }
        }
        
        // 음성 활동 감지
        if (hasVoiceActivity(audioData)) {
            lastVoiceActivityTime = System.currentTimeMillis()
        }
    }
    
    private fun checkSilenceAndTrigger() {
        val currentTime = System.currentTimeMillis()
        val silenceDuration = currentTime - lastVoiceActivityTime
        
        // 2초 침묵 → 임시 결과 출력 트리거
        if (silenceDuration >= TEMP_RESULT_SILENCE && 
            currentTime - lastTempResultTime >= TEMP_RESULT_SILENCE) {
            
            Log.d(TAG, "Silence detected (${silenceDuration}ms) - triggering temp result")
            onTempResultTrigger()
            lastTempResultTime = currentTime
        }
        
        // 3초 침묵 → 최종 결과 출력 트리거  
        if (silenceDuration >= FINAL_RESULT_SILENCE && 
            currentTime - lastFinalResultTime >= FINAL_RESULT_SILENCE) {
            
            Log.d(TAG, "Long silence detected (${silenceDuration}ms) - triggering final result")
            onFinalResultTrigger()
            lastFinalResultTime = currentTime
        }
    }
    
    private fun hasVoiceActivity(audioData: FloatArray): Boolean {
        if (audioData.isEmpty()) return false
        
        // RMS 계산으로 음성 활동 감지
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        
        val rms = sqrt(sum / audioData.size).toFloat()
        return rms > SILENCE_THRESHOLD
    }
    
    fun getCurrentSilenceDuration(): Long {
        return System.currentTimeMillis() - lastVoiceActivityTime
    }
    
    fun isCurrentlySilent(): Boolean {
        return getCurrentSilenceDuration() > TEMP_RESULT_SILENCE
    }
    
    fun resetSilenceTimer() {
        lastVoiceActivityTime = System.currentTimeMillis()
        Log.d(TAG, "Silence timer reset")
    }
    
    fun stop() {
        isRunning = false
        scope.cancel()
        Log.d(TAG, "SilenceManager stopped")
    }
}