package com.hiclone.whisperstt

import android.util.Log
import kotlin.system.measureTimeMillis

class RealtimePerformanceMonitor {
    companion object {
        private const val TAG = "RTPerformance"
    }

    private var windowCount = 0
    private var totalProcessingTime = 0L
    private val recentTimes = mutableListOf<Long>()
    private val maxRecentTimes = 10

    suspend fun recordProcessing(audioLengthSeconds: Float, processingBlock: suspend () -> String): String {
        windowCount++
        val processingTime = measureTimeMillis {
            processingBlock()
        }

        totalProcessingTime += processingTime
        recentTimes.add(processingTime)

        // 최근 10개만 유지
        if (recentTimes.size > maxRecentTimes) {
            recentTimes.removeAt(0)
        }

        val realTimeFactor = processingTime.toFloat() / (audioLengthSeconds * 1000)
        val avgRecentTime = recentTimes.average()
        val avgRecentFactor = avgRecentTime / (audioLengthSeconds * 1000)

        // 성능 로그 (5개 윈도우마다)
        if (windowCount % 5 == 0) {
            Log.i(TAG, "Window #$windowCount Performance:")
            Log.i(TAG, "  Current: ${processingTime}ms (${String.format("%.2f", realTimeFactor)}x RT)")
            Log.i(TAG, "  Recent avg: ${String.format("%.0f", avgRecentTime)}ms (${String.format("%.2f", avgRecentFactor)}x RT)")

            // 성능 경고
            when {
                avgRecentFactor > 1.5f -> Log.w(TAG, "⚠️ Too slow! Consider smaller window or model")
                avgRecentFactor > 1.0f -> Log.w(TAG, "⚠️ Slightly slower than real-time")
                avgRecentFactor < 0.5f -> Log.i(TAG, "✅ Excellent performance!")
                else -> Log.i(TAG, "✅ Good real-time performance")
            }
        }

        return processingBlock()
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalWindows" to windowCount,
            "avgProcessingTime" to if (windowCount > 0) totalProcessingTime / windowCount else 0,
            "recentAvgTime" to if (recentTimes.isNotEmpty()) recentTimes.average() else 0.0,
            "memoryMB" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        )
    }

    fun reset() {
        windowCount = 0
        totalProcessingTime = 0L
        recentTimes.clear()
    }
}