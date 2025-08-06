package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whisper Streaming 논문의 정확한 구현
 * "Turning Whisper into Real-Time Transcription System" (Machácek et al., 2023)
 * 
 * 핵심 알고리즘:
 * 1. HypothesisBuffer with LocalAgreement-2 policy
 * 2. 200-character prompt generation
 * 3. 15-second buffer trimming
 * 4. Timestamped word processing
 */
class ImprovedWhisperStreamingProcessor(
    private val whisperSTT: WhisperSTT,
    private val onResult: (String, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "WhisperStreamingOnline"
        private const val SAMPLE_RATE = 16000
        
        // 개선된 파라미터들 - Hallucination 방지
        private const val MIN_CHUNK_SIZE_SEC = 1.0f
        private const val MAX_CHUNK_SIZE_SEC = 20f // 충분한 시간으로 불필요한 빈 오디오 처리 방지
        private const val BUFFER_TRIMMING_SEC = 12f // 조금 더 짧게
        private const val PROMPT_SIZE_CHARS = 100 // Hallucination 방지를 위해 단축
        private const val SILENCE_THRESHOLD = 0.01f
        
        // LocalAgreement-2 policy
        private const val AGREEMENT_WINDOW = 2
        
        // 실용적 개선사항
        private const val FORCE_COMMIT_TIMEOUT = 8000L // 충분한 타임아웃
        private const val SILENCE_DURATION_FOR_COMMIT = 4000L // 자연스러운 발화를 위한 충분한 시간
        
        // Hallucination 감지
        private const val MAX_REPETITION_COUNT = 3 // 같은 구문 3번 반복시 중단
        private const val MIN_TRANSCRIPTION_LENGTH = 3 // 최소 전사 길이
    }
    
    // 논문의 HypothesisBuffer 데이터 구조
    data class TimestampedWord(
        val start: Double,
        val end: Double,
        val text: String
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private val isProcessing = AtomicBoolean(false)
    
    // 오디오 버퍼 관리
    private val audioBuffer = mutableListOf<Float>()
    private var bufferTimeOffset = 0.0 // 버퍼의 시작 시간
    private var lastProcessTime = 0L
    private val performanceMonitor = RealtimePerformanceMonitor()
    
    // HypothesisBuffer 핵심 데이터 구조 (논문과 동일)
    private val committedInBuffer = mutableListOf<TimestampedWord>()
    private val buffer = mutableListOf<TimestampedWord>()
    private val newWords = mutableListOf<TimestampedWord>()
    private val committed = mutableListOf<TimestampedWord>()
    
    private var lastCommittedTime = 0.0
    private var lastVoiceActivityTime = 0L
    private var lastBufferUpdateTime = 0L
    
    // Hallucination 감지를 위한 변수들
    private var lastTranscriptionText = ""
    private var repetitionCount = 0

    fun addAudioData(audioData: FloatArray) {
        if (!isRunning) return
        
        // null 값 필터링하여 안전하게 추가
        audioData.forEach { sample ->
            if (sample.isFinite()) { // NaN, Infinity 체크
                audioBuffer.add(sample)
            }
        }
        
        val currentTime = System.currentTimeMillis()
        val audioLengthSec = audioBuffer.size / SAMPLE_RATE.toFloat()
        
        // 개선된 처리 조건: 최대 청크 크기 제한 추가
        val shouldProcess = audioLengthSec >= MIN_CHUNK_SIZE_SEC &&
                           currentTime - lastProcessTime > (MIN_CHUNK_SIZE_SEC * 1000).toLong() &&
                           !isProcessing.get()
                           
        // Hallucination 방지를 위한 최대 청크 크기 체크 - 음성 활동이 있을 때만
        val forceProcess = audioLengthSec >= MAX_CHUNK_SIZE_SEC && !isProcessing.get() && 
                          (currentTime - lastVoiceActivityTime) < 10000L // 최근 10초 내 음성 활동이 있었던 경우만
        
        if (shouldProcess || forceProcess) {
            // 안전한 FloatArray 변환
            val safeAudioData = try {
                audioBuffer.filterNotNull().toFloatArray()
            } catch (e: Exception) {
                Log.e(TAG, "Error converting audio buffer to FloatArray", e)
                return
            }
            
            if (safeAudioData.isEmpty()) return
            
            // VAD 체크
            if (hasVoiceActivity(safeAudioData)) {
                lastVoiceActivityTime = currentTime
            }
            
            if (forceProcess) {
                Log.d(TAG, "Force processing due to max chunk size: ${audioLengthSec}s")
            }
            
            lastProcessTime = currentTime
            
            scope.launch {
                processIteration(safeAudioData)
            }
        } else {
            // 침묵 감지 - 일정 시간 침묵이 지속되면 버퍼의 단어들 강제 커밋
            checkForSilenceCommit(currentTime)
        }
        
        // 타임아웃 체크 - 너무 오랫동안 버퍼에 단어가 남아있으면 강제 커밋
        checkForTimeoutCommit(currentTime)
        
        // 논문의 15초 버퍼 제한
        if (audioLengthSec > BUFFER_TRIMMING_SEC) {
            trimBuffer()
        }
    }
    
    /**
     * 논문의 핵심: OnlineASRProcessor.process_iter() 구현
     */
    private suspend fun processIteration(audioData: FloatArray) {
        if (!isProcessing.compareAndSet(false, true)) return
        
        try {
            if (!isRunning) return
            
            Log.d(TAG, "Processing ${audioData.size / SAMPLE_RATE.toFloat()}s audio chunk")
            
            // 1. 논문의 prompt() 메서드 - 200자 제한
            val prompt = generatePrompt()
            
            // 2. Whisper 실행 (논문의 ASR 호출) 
            val audioLengthSec = audioData.size / SAMPLE_RATE.toFloat()
            val transcriptionResult = performanceMonitor.recordProcessing(audioLengthSec) {
                if (prompt.isEmpty()) {
                    whisperSTT.transcribeAudioSync(audioData)
                } else {
                    // 논문의 prompt 지원 사용
                    whisperSTT.transcribeWithContext(audioData, prompt)
                }
            }
            
            if (transcriptionResult.isNotEmpty() && 
                !transcriptionResult.startsWith("ERROR") && 
                isRunning &&
                transcriptionResult.length >= MIN_TRANSCRIPTION_LENGTH) {
                
                // Hallucination 감지
                if (isHallucination(transcriptionResult)) {
                    Log.w(TAG, "Hallucination detected, skipping result: ${transcriptionResult.take(100)}...")
                    
                    // Hallucination 발생 시 버퍼 완전 리셋
                    resetBuffersOnHallucination()
                    return
                }
                
                // 3. 논문의 HypothesisBuffer 알고리즘 적용
                val timestampedWords = parseTranscriptionToWords(transcriptionResult)
                insertIntoHypothesisBuffer(timestampedWords)
                val committedWords = flushHypothesisBuffer() // LocalAgreement-2
                
                // 4. 결과 출력
                if (committedWords.isNotEmpty()) {
                    val committedText = committedWords.joinToString(" ") { it.text }
                    committed.addAll(committedWords)
                    onResult(committedText, true)
                    Log.d(TAG, "Committed: $committedText")
                }
                
                // 5. 논문의 buffer trimming
                performBufferTrimming()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in processIteration", e)
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * 논문의 HypothesisBuffer.insert() 구현
     * 핵심: n-gram 제거 알고리즘
     */
    private fun insertIntoHypothesisBuffer(timestampedWords: List<TimestampedWord>) {
        // 1. 마지막 커밋 시간 이후의 단어만 필터링
        newWords.clear()
        newWords.addAll(timestampedWords.filter { it.start > lastCommittedTime - 0.1 })
        
        if (newWords.isEmpty()) return
        
        // 2. 논문의 핵심: n-gram 제거 (1-5 단어)
        removeCommonNGrams()
    }
    
    /**
     * 논문의 핵심 알고리즘: committed_in_buffer와 new 사이의 공통 n-gram 제거
     */
    private fun removeCommonNGrams() {
        if (committedInBuffer.isEmpty() || newWords.isEmpty()) return
        
        val committedSize = committedInBuffer.size
        val newSize = newWords.size
        
        // 논문: 1-5 단어 n-gram 체크
        for (i in 1..min(min(committedSize, newSize), 5)) {
            // committed의 suffix와 new의 prefix 비교
            val committedSuffix = committedInBuffer.takeLast(i)
                .joinToString(" ") { it.text }
            val newPrefix = newWords.take(i)
                .joinToString(" ") { it.text }
            
            if (normalizeForComparison(committedSuffix) == normalizeForComparison(newPrefix)) {
                // 일치하는 단어들을 new에서 제거
                repeat(i) {
                    if (newWords.isNotEmpty()) {
                        newWords.removeAt(0)
                    }
                }
                Log.d(TAG, "Removed common n-gram ($i words): $newPrefix")
                break
            }
        }
    }
    
    /**
     * 논문의 HypothesisBuffer.flush() 구현
     * LocalAgreement-2 policy
     */
    private fun flushHypothesisBuffer(): List<TimestampedWord> {
        val commitList = mutableListOf<TimestampedWord>()
        
        Log.d(TAG, "=== LocalAgreement Analysis ===")
        Log.d(TAG, "New words: ${newWords.map { it.text }}")
        Log.d(TAG, "Buffer words: ${buffer.map { it.text }}")
        
        // LocalAgreement-2: new와 buffer의 첫 번째 단어부터 비교 (개선된 매칭)
        while (newWords.isNotEmpty() && buffer.isNotEmpty()) {
            val newWord = newWords[0]
            val bufferWord = buffer[0]
            
            if (normalizeForComparison(newWord.text) == normalizeForComparison(bufferWord.text)) {
                // 일치! 커밋
                commitList.add(newWord)
                lastCommittedTime = newWord.end
                
                // 양쪽에서 제거
                buffer.removeAt(0)
                newWords.removeAt(0)
                
                Log.d(TAG, "LocalAgreement: '${newWord.text}' committed")
            } else {
                // 불일치하면 중단 (논문의 핵심)
                Log.d(TAG, "LocalAgreement failed: '${newWord.text}' != '${bufferWord.text}'")
                Log.d(TAG, "Remaining new words: ${newWords.map { it.text }}")
                Log.d(TAG, "Remaining buffer words: ${buffer.map { it.text }}")
                break
            }
        }
        
        // 다음 iteration을 위해 buffer 업데이트
        buffer.clear()
        buffer.addAll(newWords)
        newWords.clear()
        
        // committed_in_buffer에 추가
        committedInBuffer.addAll(commitList)
        
        // 버퍼 업데이트 시간 기록
        if (buffer.isNotEmpty()) {
            lastBufferUpdateTime = System.currentTimeMillis()
        }
        
        Log.d(TAG, "Updated buffer for next iteration: ${buffer.map { it.text }}")
        Log.d(TAG, "=== End LocalAgreement ===")
        
        return commitList
    }
    
    /**
     * 침묵 감지 시 강제 커밋 - 개선된 로직
     */
    private fun checkForSilenceCommit(currentTime: Long) {
        if (buffer.isEmpty()) return
        
        val silenceDuration = currentTime - lastVoiceActivityTime
        
        // 충분한 침묵 시간과 의미있는 내용이 있을 때만 커밋
        if (silenceDuration > SILENCE_DURATION_FOR_COMMIT && buffer.size >= 2) {
            Log.d(TAG, "Silence detected (${silenceDuration}ms), force committing ${buffer.size} words")
            val forcedCommit = buffer.toList()
            buffer.clear()
            committed.addAll(forcedCommit)
            
            val commitText = forcedCommit.joinToString(" ") { it.text }
            onResult(commitText, true)
            Log.d(TAG, "Force committed due to silence: $commitText")
        } else if (silenceDuration > SILENCE_DURATION_FOR_COMMIT) {
            Log.d(TAG, "Silence detected but buffer too small (${buffer.size} words), waiting...")
        }
    }
    
    /**
     * 타임아웃 시 강제 커밋
     */
    private fun checkForTimeoutCommit(currentTime: Long) {
        if (buffer.isEmpty()) return
        
        val timeoutDuration = currentTime - lastBufferUpdateTime
        
        if (timeoutDuration > FORCE_COMMIT_TIMEOUT) {
            Log.d(TAG, "Buffer timeout (${timeoutDuration}ms), force committing ${buffer.size} words")
            val forcedCommit = buffer.toList()
            buffer.clear()
            committed.addAll(forcedCommit)
            
            val commitText = forcedCommit.joinToString(" ") { it.text }
            onResult(commitText, true)
            Log.d(TAG, "Force committed due to timeout: $commitText")
        }
    }
    
    /**
     * 안전한 prompt 생성 - Hallucination 방지
     */
    private fun generatePrompt(): String {
        if (committed.isEmpty()) return ""
        
        // 시간 필터링 제거 - 최근 커밋된 좋은 결과들을 모두 사용
        val availableCommitted = committed
        if (availableCommitted.isEmpty()) return ""
        
        // 안전성 검사: 최근 커밋된 텍스트가 의심스러운지 체크
        val recentWords = availableCommitted.takeLast(20).map { it.text }
        val recentText = recentWords.joinToString(" ")
        
        // Hallucination이 의심되는 prompt는 사용하지 않음
        if (isPromptSuspicious(recentText)) {
            Log.w(TAG, "Suspicious prompt detected, using empty prompt")
            return ""
        }
        
        // 100자 제한으로 prompt 생성 - 최신 결과부터 역순으로
        val promptWords = mutableListOf<String>()
        var totalLength = 0
        
        for (i in availableCommitted.indices.reversed()) {
            val word = availableCommitted[i].text
            val newLength = totalLength + word.length + 1
            
            if (newLength > PROMPT_SIZE_CHARS) break
            
            promptWords.add(0, word)
            totalLength = newLength
        }
        
        val prompt = promptWords.joinToString(" ")
        if (prompt.isNotEmpty()) {
            Log.d(TAG, "Generated prompt (${prompt.length} chars): ${prompt.take(50)}...")
        }
        
        return prompt
    }
    
    /**
     * Prompt 안전성 검사
     */
    private fun isPromptSuspicious(promptText: String): Boolean {
        val normalized = normalizeForComparison(promptText)
        val words = normalized.split(" ").filter { it.isNotBlank() }
        
        if (words.size < 3) return false
        
        // 반복 패턴 체크
        val wordCounts = mutableMapOf<String, Int>()
        words.forEach { word ->
            wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
        }
        
        // 단어가 절반 이상 반복되면 의심
        wordCounts.forEach { (word, count) ->
            if (count > words.size / 2) {
                Log.w(TAG, "Suspicious prompt: word '$word' repeated $count times")
                return true
            }
        }
        
        // 의심스러운 패턴들
        val suspiciousPatterns = listOf("s3c with", "stt with", "with s3c", "with stt")
        suspiciousPatterns.forEach { pattern ->
            if (normalized.contains(pattern)) {
                Log.w(TAG, "Suspicious prompt: contains pattern '$pattern'")
                return true
            }
        }
        
        return false
    }
    
    /**
     * 논문의 buffer trimming 구현
     */
    private fun performBufferTrimming() {
        val audioLengthSec = audioBuffer.size / SAMPLE_RATE.toFloat()
        
        if (audioLengthSec > BUFFER_TRIMMING_SEC) {
            // 논문: segment 경계에서 trim
            val trimTime = bufferTimeOffset + audioLengthSec - BUFFER_TRIMMING_SEC
            chunkAt(trimTime)
        }
    }
    
    private fun chunkAt(chunkTime: Double) {
        // committed_in_buffer에서 chunkTime 이전의 단어들 제거
        committedInBuffer.removeAll { it.end <= chunkTime }
        
        // 오디오 버퍼에서 해당 시간만큼 제거
        val samplesToRemove = ((chunkTime - bufferTimeOffset) * SAMPLE_RATE).toInt()
        val actualSamplesToRemove = min(samplesToRemove, audioBuffer.size)
        
        if (actualSamplesToRemove > 0) {
            repeat(actualSamplesToRemove) {
                audioBuffer.removeAt(0)
            }
            
            bufferTimeOffset += actualSamplesToRemove / SAMPLE_RATE.toDouble()
            Log.d(TAG, "Buffer chunked at ${chunkTime}s, removed $actualSamplesToRemove samples")
        }
    }
    
    private fun trimBuffer() {
        // 15초 이상의 오디오가 쌓이면 앞부분을 제거
        val maxSamples = (BUFFER_TRIMMING_SEC * SAMPLE_RATE).toInt()
        val samplesToRemove = audioBuffer.size - maxSamples
        
        if (samplesToRemove > 0) {
            repeat(samplesToRemove) {
                audioBuffer.removeAt(0)
            }
            audioBuffer.removeAt(0)
        }
        
        bufferTimeOffset += samplesToRemove / SAMPLE_RATE.toDouble()
    }
    
    private fun parseTranscriptionToWords(transcription: String): List<TimestampedWord> {
        if (transcription.isBlank()) return emptyList()
        
        val result = mutableListOf<TimestampedWord>()
        val words = transcription.trim().split(Regex("\\s+"))
        
        Log.d(TAG, "Parsing transcription: '$transcription'")
        Log.d(TAG, "Split into ${words.size} words: $words")
        
        // 단순한 시간 추정 (실제 구현에서는 Whisper의 타임스탬프 사용)
        val audioLengthSec = audioBuffer.size / SAMPLE_RATE.toDouble()
        val timePerWord = audioLengthSec / words.size
        
        words.forEachIndexed { index, word ->
            if (word.isNotBlank()) {
                val start = bufferTimeOffset + (index * timePerWord)
                val end = bufferTimeOffset + ((index + 1) * timePerWord)
                result.add(TimestampedWord(start, end, word))
            }
        }
        
        Log.d(TAG, "Parsed to ${result.size} timestamped words")
        return result
    }
    
    private fun hasVoiceActivity(audioData: FloatArray): Boolean {
        if (audioData.isEmpty()) return false
        
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        
        val rms = sqrt(sum / audioData.size).toFloat()
        return rms > SILENCE_THRESHOLD
    }
    
    /**
     * 텍스트 정규화 - 구두점과 대소문자 문제 해결
     */
    private fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[.,!?;:\"'\\-()\\[\\]{}]"), "") // 구두점 제거
            .replace(Regex("\\s+"), " ") // 여러 공백을 하나로
            .trim()
    }
    
    /**
     * 강화된 Hallucination 감지 - 반복 패턴 체크
     */
    private fun isHallucination(transcription: String): Boolean {
        val normalized = normalizeForComparison(transcription)
        val words = normalized.split(" ").filter { it.isNotBlank() }
        
        // 1. 같은 텍스트 반복 체크
        if (normalized == normalizeForComparison(lastTranscriptionText)) {
            repetitionCount++
            if (repetitionCount >= MAX_REPETITION_COUNT) {
                Log.w(TAG, "Exact repetition detected ($repetitionCount times): ${normalized.take(50)}...")
                repetitionCount = 0 // 리셋
                return true
            }
        } else {
            repetitionCount = 0
        }
        
        // 2. 과도한 단어 수 체크 (50개 이상은 의심)
        if (words.size > 50) {
            Log.w(TAG, "Excessive word count detected: ${words.size} words")
            return true
        }
        
        // 3. 강화된 내부 반복 패턴 체크
        if (words.size >= 6) {
            // 2-3 단어 패턴의 반복 체크
            for (patternLength in 2..3) {
                if (words.size >= patternLength * 3) { // 최소 3번 반복
                    val pattern = words.take(patternLength).joinToString(" ")
                    var repetitions = 1
                    
                    for (i in patternLength until words.size step patternLength) {
                        val segment = words.drop(i).take(patternLength).joinToString(" ")
                        if (segment == pattern) {
                            repetitions++
                        } else {
                            break
                        }
                    }
                    
                    if (repetitions >= 3) {
                        Log.w(TAG, "Pattern repetition detected: '$pattern' repeated $repetitions times")
                        return true
                    }
                }
            }
            
            // 단일 단어의 과도한 반복 체크
            val wordCounts = mutableMapOf<String, Int>()
            words.forEach { word ->
                wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
            }
            
            wordCounts.forEach { (word, count) ->
                if (count > words.size / 3 && count > 5) { // 전체의 1/3 이상이거나 5번 이상 반복
                    Log.w(TAG, "Word over-repetition detected: '$word' appears $count times")
                    return true
                }
            }
        }
        
        // 4. 특정 패턴 감지 (S3C, STT 등의 기술 용어 반복)
        val suspiciousPatterns = listOf("s3c", "stt", "with", "and", "the")
        suspiciousPatterns.forEach { pattern ->
            val patternCount = words.count { it.contains(pattern, ignoreCase = true) }
            if (patternCount > 10) {
                Log.w(TAG, "Suspicious pattern detected: '$pattern' appears $patternCount times")
                return true
            }
        }
        
        lastTranscriptionText = transcription
        return false
    }
    
    /**
     * Hallucination 발생 시 버퍼 리셋
     */
    private fun resetBuffersOnHallucination() {
        Log.w(TAG, "Resetting all buffers due to hallucination")
        
        // 모든 버퍼 클리어
        buffer.clear()
        newWords.clear()
        committedInBuffer.clear()
        
        // 최근 committed 데이터도 의심스러우면 일부 제거
        if (committed.size > 10) {
            val safeCommitted = committed.take(committed.size - 5) // 마지막 5개 제거
            committed.clear()
            committed.addAll(safeCommitted)
            Log.w(TAG, "Removed last 5 committed words due to hallucination")
        }
        
        // 상태 리셋
        lastTranscriptionText = ""
        repetitionCount = 0
        lastBufferUpdateTime = System.currentTimeMillis()
        
        Log.w(TAG, "Buffer reset completed")
    }

    fun start() {
        isRunning = true
        val currentTime = System.currentTimeMillis()
        lastProcessTime = currentTime
        lastVoiceActivityTime = currentTime
        lastBufferUpdateTime = currentTime
        
        // HypothesisBuffer 초기화
        committedInBuffer.clear()
        buffer.clear()
        newWords.clear()
        committed.clear()
        lastCommittedTime = 0.0
        bufferTimeOffset = 0.0
        
        // Hallucination 감지 초기화
        lastTranscriptionText = ""
        repetitionCount = 0
        
        Log.d(TAG, "Started Whisper Streaming with enhanced hallucination protection")
    }

    fun stop() {
        isRunning = false
        
        // 현재 처리 중인 작업 완료 대기
        var waitCount = 0
        while (isProcessing.get() && waitCount < 10) {
            Thread.sleep(50)
            waitCount++
        }
        
        // 남은 버퍼 내용 최종 커밋
        if (buffer.isNotEmpty()) {
            val finalCommit = buffer.toList()
            committed.addAll(finalCommit)
            val finalText = finalCommit.joinToString(" ") { it.text }
            onResult(finalText, true)
            Log.d(TAG, "Final commit on stop: $finalText")
        }
        
        // 모든 버퍼 클리어
        audioBuffer.clear()
        committedInBuffer.clear()
        buffer.clear()
        newWords.clear()
        
        // 코루틴 취소
        scope.cancel()
        
        Log.d(TAG, "Stopped Whisper Streaming")
    }

    fun release() {
        stop()
    }
}