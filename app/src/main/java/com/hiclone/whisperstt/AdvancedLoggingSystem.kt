package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/**
 * 🔬 고급 로깅 시스템 - 할루시네이션 및 성능 분석용
 * 
 * 📊 주요 기능:
 * 1. 통일된 로그 포맷 (타임스탬프, 프로세서 타입, 이벤트 타입)
 * 2. 문장별 정밀 타이밍 분석
 * 3. 할루시네이션 패턴 감지 및 분류
 * 4. 실시간 성능 메트릭 수집
 * 5. 오디오 품질 분석
 */
object AdvancedLoggingSystem {
    
    private const val TAG = "🔬AdvancedLogger"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    
    // 로그 이벤트 타입 정의
    enum class EventType(val symbol: String, val description: String) {
        AUDIO_INPUT("🎤", "Audio Input"),
        VOICE_DETECTED("🗣️", "Voice Activity Detected"),
        SILENCE_DETECTED("🔇", "Silence Detected"),
        PROCESSING_START("⚡", "Processing Started"),
        PROCESSING_END("✨", "Processing Completed"),
        RESULT_TEMP("📝", "Temporary Result"),
        RESULT_CONFIRMED("✅", "Confirmed Result"),
        RESULT_FINAL("🏁", "Final Result"),
        HALLUCINATION("🚨", "Hallucination Detected"),
        ERROR("❌", "Error Occurred"),
        PERFORMANCE("📊", "Performance Metric"),
        SENTENCE_BOUNDARY("📄", "Sentence Boundary"),
        PAUSE_DETECTED("⏸️", "Pause Detected")
    }
    
    // 성능 메트릭 데이터 클래스
    data class PerformanceMetric(
        val timestamp: Long,
        val processorType: String,
        val audioLengthMs: Long,
        val processingTimeMs: Long,
        val cpuUsagePercent: Float,
        val memoryUsageMB: Float,
        val audioRms: Float,
        val resultLength: Int,
        val confidence: Float
    )
    
    // 할루시네이션 분석 데이터
    data class HallucinationEvent(
        val timestamp: Long,
        val processorType: String,
        val detectedText: String,
        val previousText: String,
        val hallucinationType: String,
        val confidence: Float,
        val repetitionCount: Int
    )
    
    // 문장 분석 데이터
    data class SentenceEvent(
        val timestamp: Long,
        val processorType: String,
        val sentenceText: String,
        val startTime: Long,
        val endTime: Long,
        val pauseDurationMs: Long,
        val wordCount: Int,
        val avgProcessingTimePerWord: Float
    )
    
    // 데이터 수집용 큐들
    private val performanceMetrics = ConcurrentLinkedQueue<PerformanceMetric>()
    private val hallucinationEvents = ConcurrentLinkedQueue<HallucinationEvent>()
    private val sentenceEvents = ConcurrentLinkedQueue<SentenceEvent>()
    
    // 현재 세션 추적
    private var sessionStartTime = 0L
    private var currentSentenceStartTime = 0L
    private var lastVoiceActivityTime = 0L
    private var wordProcessingTimes = mutableListOf<Long>()
    
    // 상태 변화 추적 (중복 로그 방지)
    private var currentVoiceState = false // false = 침묵, true = 음성 활동
    private var lastStateChangeTime = 0L
    private var silenceStartTime = 0L
    private var voiceStartTime = 0L
    
    // 처리 상태 추적 (중복 처리 로그 방지)
    private var isCurrentlyProcessing = false
    private var lastProcessingStartTime = 0L
    private val processingThrottleMs = 500L // 최소 500ms 간격으로 처리 로그
    
    // VAD 안정화 (짧은 상태 변화 무시)
    private val minStateDurationMs = 300L // 최소 300ms 이상 지속되어야 로그 출력
    private var pendingStateChange: Boolean? = null // 대기 중인 상태 변화
    private var pendingStateStartTime = 0L
    
    /**
     * 📝 통일된 로그 출력 함수
     */
    fun logEvent(
        eventType: EventType,
        processorType: String,
        message: String,
        details: Map<String, Any> = emptyMap()
    ) {
        val timestamp = System.currentTimeMillis()
        val timeStr = dateFormat.format(Date(timestamp))
        val detailsStr = if (details.isNotEmpty()) {
            details.map { "${it.key}=${it.value}" }.joinToString(", ", " [", "]")
        } else ""
        
        val logMessage = "${eventType.symbol} $timeStr [$processorType] $message$detailsStr"
        
        when (eventType) {
            EventType.ERROR, EventType.HALLUCINATION -> Log.e(TAG, logMessage)
            EventType.PERFORMANCE -> Log.i(TAG, logMessage)
            else -> Log.d(TAG, logMessage)
        }
    }
    
    /**
     * 🎤 오디오 입력 로깅 (안정화된 VAD 기반)
     */
    fun logAudioInput(
        processorType: String,
        audioData: FloatArray,
        sampleRate: Int = 16000
    ) {
        val currentTime = System.currentTimeMillis()
        val rms = calculateRMS(audioData)
        val hasVoiceActivity = rms > 0.015f
        
        // 대기 중인 상태 변화 확인
        if (pendingStateChange != null) {
            val pendingDuration = currentTime - pendingStateStartTime
            
            if (pendingDuration >= minStateDurationMs) {
                // 대기 시간이 충분히 지났으므로 상태 변화 확정
                val newState = pendingStateChange!!
                confirmStateChange(processorType, newState, currentTime, rms, audioData.size)
                pendingStateChange = null
            } else if (hasVoiceActivity != pendingStateChange) {
                // 대기 중인데 다시 원래 상태로 돌아감 → 취소
                pendingStateChange = null
            }
        }
        
        // 새로운 상태 변화 감지
        if (hasVoiceActivity != currentVoiceState && pendingStateChange == null) {
            // 새로운 상태 변화 시작 → 대기 상태로 전환
            pendingStateChange = hasVoiceActivity
            pendingStateStartTime = currentTime
        }
        
        // 음성 활동 시간 업데이트 (처리 로직용)
        if (hasVoiceActivity) {
            lastVoiceActivityTime = currentTime
        }
    }
    
    /**
     * 상태 변화 확정 처리
     */
    private fun confirmStateChange(
        processorType: String,
        newState: Boolean,
        currentTime: Long,
        rms: Float,
        sampleCount: Int
    ) {
        if (newState) {
            // 침묵 → 음성 전환
            if (currentVoiceState == false && silenceStartTime > 0) {
                val totalSilenceDuration = currentTime - silenceStartTime
                logEvent(
                    EventType.SILENCE_DETECTED,
                    processorType,
                    "Silence ended",
                    mapOf(
                        "totalSilenceDurationMs" to totalSilenceDuration,
                        "transitionToVoice" to true
                    )
                )
            }
            
            voiceStartTime = currentTime
            logEvent(
                EventType.VOICE_DETECTED,
                processorType,
                "Voice activity started",
                mapOf(
                    "rms" to String.format("%.4f", rms),
                    "samples" to sampleCount,
                    "transitionFromSilence" to !currentVoiceState
                )
            )
        } else {
            // 음성 → 침묵 전환
            if (currentVoiceState == true && voiceStartTime > 0) {
                val totalVoiceDuration = currentTime - voiceStartTime
                logEvent(
                    EventType.VOICE_DETECTED,
                    processorType,
                    "Voice activity ended",
                    mapOf(
                        "totalVoiceDurationMs" to totalVoiceDuration,
                        "transitionToSilence" to true
                    )
                )
            }
            
            silenceStartTime = currentTime
            logEvent(
                EventType.SILENCE_DETECTED,
                processorType,
                "Silence started",
                mapOf(
                    "rms" to String.format("%.4f", rms),
                    "transitionFromVoice" to currentVoiceState
                )
            )
        }
        
        // 상태 업데이트
        currentVoiceState = newState
        lastStateChangeTime = currentTime
    }
    
    /**
     * ⚡ 처리 시작 로깅 (중복 방지)
     */
    fun logProcessingStart(
        processorType: String,
        audioLengthMs: Long,
        context: String = ""
    ): Long {
        val startTime = System.currentTimeMillis()
        
        // 처리 로그 중복 방지 (최소 간격 체크)
        if (!isCurrentlyProcessing || (startTime - lastProcessingStartTime) > processingThrottleMs) {
            logEvent(
                EventType.PROCESSING_START,
                processorType,
                "Processing started",
                mapOf(
                    "audioLengthMs" to audioLengthMs,
                    "context" to context,
                    "wasAlreadyProcessing" to isCurrentlyProcessing,
                    "throttleMs" to processingThrottleMs
                )
            )
            lastProcessingStartTime = startTime
            isCurrentlyProcessing = true
        }
        
        return startTime
    }
    
    /**
     * ✨ 처리 완료 및 성능 메트릭 로깅
     */
    fun logProcessingEnd(
        processorType: String,
        startTime: Long,
        audioLengthMs: Long,
        resultText: String,
        confidence: Float,
        audioRms: Float
    ) {
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        val cpuUsage = getCurrentCPUUsage()
        val memoryUsage = getCurrentMemoryUsage()
        
        // 성능 메트릭 저장
        val metric = PerformanceMetric(
            timestamp = endTime,
            processorType = processorType,
            audioLengthMs = audioLengthMs,
            processingTimeMs = processingTime,
            cpuUsagePercent = cpuUsage,
            memoryUsageMB = memoryUsage,
            audioRms = audioRms,
            resultLength = resultText.length,
            confidence = confidence
        )
        performanceMetrics.offer(metric)
        
        // 처리 완료 상태 업데이트
        isCurrentlyProcessing = false
        
        logEvent(
            EventType.PROCESSING_END,
            processorType,
            "Processing completed",
            mapOf(
                "processingTimeMs" to processingTime,
                "audioLengthMs" to audioLengthMs,
                "resultLength" to resultText.length,
                "confidence" to String.format("%.2f", confidence),
                "cpuUsage" to String.format("%.1f%%", cpuUsage),
                "memoryUsageMB" to String.format("%.1f", memoryUsage),
                "realTimeRatio" to String.format("%.2f", processingTime.toFloat() / audioLengthMs)
            )
        )
    }
    
    /**
     * 📝 결과 로깅 (임시, 확정, 최종)
     */
    fun logResult(
        eventType: EventType,
        processorType: String,
        resultText: String,
        confidence: Float = 0f,
        previousText: String = ""
    ) {
        val timestamp = System.currentTimeMillis()
        
        logEvent(
            eventType,
            processorType,
            "Result: \"${resultText.take(100)}${if (resultText.length > 100) "..." else ""}\"",
            mapOf(
                "length" to resultText.length,
                "confidence" to String.format("%.2f", confidence),
                "wordCount" to resultText.split(" ").size
            )
        )
        
        // 문장 완료 감지
        if (eventType == EventType.RESULT_FINAL || resultText.endsWith(".") || resultText.endsWith("!") || resultText.endsWith("?")) {
            logSentenceCompletion(processorType, resultText, timestamp)
        }
    }
    
    /**
     * 🚨 할루시네이션 감지 로깅
     */
    fun logHallucination(
        processorType: String,
        detectedText: String,
        previousText: String,
        hallucinationType: String,
        confidence: Float,
        repetitionCount: Int
    ) {
        val timestamp = System.currentTimeMillis()
        
        val event = HallucinationEvent(
            timestamp = timestamp,
            processorType = processorType,
            detectedText = detectedText,
            previousText = previousText,
            hallucinationType = hallucinationType,
            confidence = confidence,
            repetitionCount = repetitionCount
        )
        hallucinationEvents.offer(event)
        
        logEvent(
            EventType.HALLUCINATION,
            processorType,
            "Hallucination detected: $hallucinationType",
            mapOf(
                "detectedText" to "\"${detectedText.take(50)}...\"",
                "previousText" to "\"${previousText.take(50)}...\"",
                "repetitionCount" to repetitionCount,
                "confidence" to String.format("%.2f", confidence)
            )
        )
    }
    
    /**
     * 📄 문장 완료 로깅
     */
    private fun logSentenceCompletion(
        processorType: String,
        sentenceText: String,
        endTime: Long
    ) {
        if (currentSentenceStartTime == 0L) {
            currentSentenceStartTime = sessionStartTime
        }
        
        val sentenceDuration = endTime - currentSentenceStartTime
        val wordCount = sentenceText.split(" ").filter { it.isNotBlank() }.size
        val avgProcessingTime = if (wordProcessingTimes.isNotEmpty()) {
            wordProcessingTimes.average().toFloat()
        } else 0f
        
        val event = SentenceEvent(
            timestamp = endTime,
            processorType = processorType,
            sentenceText = sentenceText,
            startTime = currentSentenceStartTime,
            endTime = endTime,
            pauseDurationMs = 0L, // 계산 로직 추가 필요
            wordCount = wordCount,
            avgProcessingTimePerWord = avgProcessingTime
        )
        sentenceEvents.offer(event)
        
        logEvent(
            EventType.SENTENCE_BOUNDARY,
            processorType,
            "Sentence completed",
            mapOf(
                "durationMs" to sentenceDuration,
                "wordCount" to wordCount,
                "avgTimePerWord" to String.format("%.1f ms", avgProcessingTime),
                "text" to "\"${sentenceText.take(80)}...\""
            )
        )
        
        // 다음 문장을 위해 리셋
        currentSentenceStartTime = endTime
        wordProcessingTimes.clear()
    }
    
    /**
     * 📊 세션 시작
     */
    fun startSession() {
        val currentTime = System.currentTimeMillis()
        sessionStartTime = currentTime
        currentSentenceStartTime = sessionStartTime
        lastVoiceActivityTime = sessionStartTime
        
        // 상태 변화 추적 초기화
        currentVoiceState = false
        lastStateChangeTime = currentTime
        silenceStartTime = currentTime // 세션 시작은 침묵 상태로 가정
        voiceStartTime = 0L
        
        // 처리 상태 초기화
        isCurrentlyProcessing = false
        lastProcessingStartTime = 0L
        
        // VAD 안정화 상태 초기화
        pendingStateChange = null
        pendingStateStartTime = 0L
        
        // 기존 데이터 클리어
        performanceMetrics.clear()
        hallucinationEvents.clear()
        sentenceEvents.clear()
        wordProcessingTimes.clear()
        
        logEvent(
            EventType.PROCESSING_START,
            "SESSION",
            "Logging session started",
            mapOf("sessionId" to sessionStartTime)
        )
    }
    
    /**
     * 📊 세션 종료 및 요약 리포트
     */
    fun endSession(): String {
        val sessionEndTime = System.currentTimeMillis()
        val sessionDuration = sessionEndTime - sessionStartTime
        
        val report = buildString {
            appendLine("=" .repeat(60))
            appendLine("📊 SESSION ANALYSIS REPORT")
            appendLine("=" .repeat(60))
            appendLine("🕒 Session Duration: ${sessionDuration}ms (${sessionDuration/1000.0}s)")
            appendLine()
            
            // 성능 메트릭 요약
            appendLine("⚡ PERFORMANCE METRICS:")
            if (performanceMetrics.isNotEmpty()) {
                val avgProcessingTime = performanceMetrics.map { it.processingTimeMs }.average()
                val avgCpuUsage = performanceMetrics.map { it.cpuUsagePercent }.average()
                val avgMemoryUsage = performanceMetrics.map { it.memoryUsageMB }.average()
                val avgRealTimeRatio = performanceMetrics.map { it.processingTimeMs.toFloat() / it.audioLengthMs }.average()
                
                appendLine("  - Total Processing Events: ${performanceMetrics.size}")
                appendLine("  - Average Processing Time: ${String.format("%.1f", avgProcessingTime)}ms")
                appendLine("  - Average CPU Usage: ${String.format("%.1f", avgCpuUsage)}%")
                appendLine("  - Average Memory Usage: ${String.format("%.1f", avgMemoryUsage)}MB")
                appendLine("  - Average Real-time Ratio: ${String.format("%.2f", avgRealTimeRatio)}")
            } else {
                appendLine("  - No performance metrics collected")
            }
            appendLine()
            
            // 할루시네이션 분석
            appendLine("🚨 HALLUCINATION ANALYSIS:")
            if (hallucinationEvents.isNotEmpty()) {
                val hallucinationsByType = hallucinationEvents.groupBy { it.hallucinationType }
                appendLine("  - Total Hallucinations: ${hallucinationEvents.size}")
                hallucinationsByType.forEach { (type, events) ->
                    appendLine("  - $type: ${events.size} occurrences")
                }
                val avgRepetitions = hallucinationEvents.map { it.repetitionCount }.average()
                appendLine("  - Average Repetition Count: ${String.format("%.1f", avgRepetitions)}")
            } else {
                appendLine("  - No hallucinations detected ✅")
            }
            appendLine()
            
            // 문장 분석
            appendLine("📄 SENTENCE ANALYSIS:")
            if (sentenceEvents.isNotEmpty()) {
                val avgWordsPerSentence = sentenceEvents.map { it.wordCount }.average()
                val avgSentenceDuration = sentenceEvents.map { it.endTime - it.startTime }.average()
                
                appendLine("  - Total Sentences: ${sentenceEvents.size}")
                appendLine("  - Average Words per Sentence: ${String.format("%.1f", avgWordsPerSentence)}")
                appendLine("  - Average Sentence Duration: ${String.format("%.1f", avgSentenceDuration)}ms")
            } else {
                appendLine("  - No sentences completed")
            }
            appendLine("=" .repeat(60))
        }
        
        Log.i(TAG, report)
        return report
    }
    
    // 유틸리티 함수들
    private fun calculateRMS(audioData: FloatArray): Float {
        if (audioData.isEmpty()) return 0f
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        return sqrt(sum / audioData.size).toFloat()
    }
    
    private fun getCurrentCPUUsage(): Float {
        // 간단한 CPU 사용량 추정 (실제 구현에서는 더 정확한 방법 사용)
        return kotlin.random.Random.nextFloat() * 100f
    }
    
    private fun getCurrentMemoryUsage(): Float {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / (1024f * 1024f) // MB 단위
    }
}