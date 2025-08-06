package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*

/**
 * 실시간 임시 결과 생성기
 * - 1.5초마다 빠른 임시 결과 생성
 * - 사용자에게 즉각적인 피드백 제공
 * - 성능 우선, 정확도는 LocalAgreement에서 보완
 */
class RealtimeProcessor(
    private val whisperSTT: WhisperSTT,
    private val onTemporaryResult: (String, Float) -> Unit
) {
    companion object {
        private const val TAG = "RealtimeProcessor"
        private const val CHUNK_SIZE_SEC = 1.5f
        private const val MIN_AUDIO_LENGTH = 0.8f  // 최소 0.8초는 있어야 처리
        private const val SAMPLE_RATE = 16000
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false
    private var lastProcessTime = 0L
    
    fun processForTemporaryResult(audioData: FloatArray) {
        val currentTime = System.currentTimeMillis()
        val audioLengthSec = audioData.size / SAMPLE_RATE.toFloat()
        
        // 처리 조건: 충분한 길이 + 최소 간격 + 현재 처리 중이 아님
        val shouldProcess = audioLengthSec >= MIN_AUDIO_LENGTH &&
                           currentTime - lastProcessTime > (CHUNK_SIZE_SEC * 800) && // 20% 여유
                           !isProcessing
        
        if (!shouldProcess) return
        
        lastProcessTime = currentTime
        isProcessing = true
        
        scope.launch {
            try {
                Log.d(TAG, "Processing ${audioLengthSec}s for temporary result")
                
                // 🚀 빠른 처리: prompt 없음, 최소 설정
                val result = whisperSTT.transcribeRealtimeOptimized(
                    audioData.map { (it * Short.MAX_VALUE).toInt().toShort() }.toShortArray()
                )
                
                if (result.isNotEmpty() && !result.startsWith("ERROR")) {
                    // 간단한 품질 체크
                    val confidence = calculateConfidence(result, audioLengthSec)
                    
                    if (confidence > 0.3f) { // 최소 신뢰도
                        onTemporaryResult(result, confidence)
                        Log.d(TAG, "Temporary result: '$result' (confidence: $confidence)")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in temporary processing", e)
            } finally {
                isProcessing = false
            }
        }
    }
    
    private fun calculateConfidence(text: String, audioLength: Float): Float {
        // 간단한 신뢰도 계산
        val wordsPerSecond = text.split(" ").size / audioLength
        
        return when {
            wordsPerSecond < 0.5f -> 0.2f  // 너무 적은 단어
            wordsPerSecond > 8f -> 0.3f    // 너무 많은 단어 (의심)
            text.length < 3 -> 0.4f        // 너무 짧음
            text.contains(Regex("[a-zA-Z]{10,}")) -> 0.5f // 긴 단어 있음 (좋음)
            else -> 0.7f
        }
    }
    
    fun stop() {
        scope.cancel()
    }
}