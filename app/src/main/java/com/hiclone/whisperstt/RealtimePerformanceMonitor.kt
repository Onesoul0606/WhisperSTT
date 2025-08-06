package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RealtimePerformanceMonitor {
    companion object {
        private const val TAG = "PerformanceMonitor"
    }

    private var totalProcessingTime = 0L
    private var totalAudioLength = 0f
    private var processingCount = 0

    suspend fun <T> recordProcessing(audioLengthSeconds: Float, block: suspend () -> T): T {
        val startTime = System.currentTimeMillis()
        
        val result = withContext(Dispatchers.IO) {
            block()
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        val realTimeFactor = processingTime / (audioLengthSeconds * 1000)
        
        // 통계 업데이트
        totalProcessingTime += processingTime
        totalAudioLength += audioLengthSeconds
        processingCount++
        
        Log.d(TAG, "Processing: ${String.format("%.2f", audioLengthSeconds)}s audio in ${processingTime}ms (${String.format("%.2f", realTimeFactor)}x RT)")
        
        return result
    }

    fun getAverageRealTimeFactor(): Float {
        return if (totalAudioLength > 0) {
            (totalProcessingTime / 1000f) / totalAudioLength
        } else {
            0f
        }
    }

    fun getStats(): String {
        val avgRTF = getAverageRealTimeFactor()
        val avgProcessingTime = if (processingCount > 0) totalProcessingTime / processingCount else 0
        
        return "평균 RTF: ${String.format("%.2f", avgRTF)}x, 평균 처리시간: ${avgProcessingTime}ms, 총 처리: ${processingCount}회"
    }

    fun reset() {
        totalProcessingTime = 0L
        totalAudioLength = 0f
        processingCount = 0
    }
}