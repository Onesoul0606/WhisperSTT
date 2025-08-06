package com.hiclone.whisperstt

import android.util.Log
import kotlinx.coroutines.*

/**
 * LocalAgreement-2 전담 처리기
 * - 3초마다 정확한 결과 생성
 * - 논문의 LocalAgreement-2 알고리즘 정확 구현
 * - 임시 결과를 확정 결과로 승격
 */
class LocalAgreementProcessor(
    private val whisperSTT: WhisperSTT,
    private val onConfirmedResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "LocalAgreementProcessor"
        private const val CHUNK_SIZE_SEC = 3.0f
        private const val SAMPLE_RATE = 16000
        private const val PROMPT_SIZE_CHARS = 150  // 논문보다 약간 작게
    }
    
    data class TimestampedWord(
        val start: Double,
        val end: Double,
        val text: String
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false
    private var lastProcessTime = 0L
    
    // 🔄 LocalAgreement 전용 버퍼들
    private val previousBuffer = mutableListOf<TimestampedWord>()
    private val confirmedWords = mutableListOf<TimestampedWord>()
    private var bufferTimeOffset = 0.0
    
    fun processForConfirmedResult(audioData: FloatArray) {
        val currentTime = System.currentTimeMillis()
        val audioLengthSec = audioData.size / SAMPLE_RATE.toFloat()
        
        val shouldProcess = audioLengthSec >= CHUNK_SIZE_SEC &&
                           currentTime - lastProcessTime > (CHUNK_SIZE_SEC * 800) &&
                           !isProcessing
        
        if (!shouldProcess) return
        
        lastProcessTime = currentTime
        isProcessing = true
        
        scope.launch {
            try {
                Log.d(TAG, "Processing ${audioLengthSec}s for LocalAgreement")
                
                // 🎯 정확한 처리: prompt 사용, 최고 품질 설정
                val prompt = generatePrompt()
                val result = if (prompt.isNotEmpty()) {
                    whisperSTT.transcribeWithContext(
                        audioData.map { (it * Short.MAX_VALUE).toInt().toShort() }.toShortArray(),
                        prompt
                    )
                } else {
                    whisperSTT.transcribeAudioSync(
                        audioData.map { (it * Short.MAX_VALUE).toInt().toShort() }.toShortArray()
                    )
                }
                
                if (result.isNotEmpty() && !result.startsWith("ERROR")) {
                    val newWords = parseToTimestampedWords(result, audioLengthSec)
                    val confirmedResults = performLocalAgreement(newWords)
                    
                    if (confirmedResults.isNotEmpty()) {
                        val confirmedText = confirmedResults.joinToString(" ") { it.text }
                        confirmedWords.addAll(confirmedResults)
                        onConfirmedResult(confirmedText)
                        Log.d(TAG, "Confirmed via LocalAgreement: '$confirmedText'")
                    }
                    
                    // 다음 비교를 위해 현재 결과를 버퍼에 저장
                    previousBuffer.clear()
                    previousBuffer.addAll(newWords)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in LocalAgreement processing", e)
            } finally {
                isProcessing = false
            }
        }
    }
    
    private fun performLocalAgreement(newWords: List<TimestampedWord>): List<TimestampedWord> {
        if (previousBuffer.isEmpty() || newWords.isEmpty()) {
            Log.d(TAG, "LocalAgreement: No previous buffer, storing current result")
            return emptyList() // 첫 번째 결과는 저장만 하고 확정하지 않음
        }
        
        val confirmed = mutableListOf<TimestampedWord>()
        
        Log.d(TAG, "=== LocalAgreement Analysis ===")
        Log.d(TAG, "Previous buffer: ${previousBuffer.map { it.text }}")
        Log.d(TAG, "New words: ${newWords.map { it.text }}")
        
        // LocalAgreement-2: 첫 번째 단어부터 순차 비교
        val minSize = minOf(previousBuffer.size, newWords.size)
        
        for (i in 0 until minSize) {
            val prevWord = previousBuffer[i]
            val newWord = newWords[i]
            
            if (normalizeForComparison(prevWord.text) == normalizeForComparison(newWord.text)) {
                confirmed.add(newWord) // 새로운 타임스탬프를 사용
                Log.d(TAG, "LocalAgreement: '${newWord.text}' confirmed")
            } else {
                Log.d(TAG, "LocalAgreement: '${prevWord.text}' != '${newWord.text}' - stopping")
                break
            }
        }
        
        Log.d(TAG, "LocalAgreement confirmed ${confirmed.size} words")
        Log.d(TAG, "=== End LocalAgreement ===")
        
        return confirmed
    }
    
    private fun parseToTimestampedWords(text: String, audioLength: Float): List<TimestampedWord> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val timePerWord = audioLength / words.size
        
        return words.mapIndexed { index, word ->
            TimestampedWord(
                start = bufferTimeOffset + (index * timePerWord),
                end = bufferTimeOffset + ((index + 1) * timePerWord),
                text = word
            )
        }
    }
    
    private fun generatePrompt(): String {
        if (confirmedWords.isEmpty()) return ""
        
        val recentWords = confirmedWords.takeLast(20).map { it.text }
        val promptText = recentWords.joinToString(" ")
        
        return if (promptText.length > PROMPT_SIZE_CHARS) {
            promptText.takeLast(PROMPT_SIZE_CHARS)
        } else {
            promptText
        }
    }
    
    private fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[.,!?;:\"'\\-()\\[\\]{}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    fun forceConfirmPendingResults() {
        // 침묵 감지 시 대기 중인 결과들을 강제 확정
        if (previousBuffer.isNotEmpty()) {
            val forcedConfirmed = previousBuffer.toList()
            confirmedWords.addAll(forcedConfirmed)
            val confirmedText = forcedConfirmed.joinToString(" ") { it.text }
            onConfirmedResult(confirmedText)
            Log.d(TAG, "Force confirmed due to silence: '$confirmedText'")
            previousBuffer.clear()
        }
    }
    
    fun stop() {
        scope.cancel()
    }
}