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
        
        if (shouldProcess) {
            Log.d(TAG, "🔍 Audio buffer: ${audioData.size} samples (${String.format("%.1f", audioLengthSec)}s), Previous buffer: ${previousBuffer.size} words")
        }
        
        if (!shouldProcess) return
        
        lastProcessTime = currentTime
        isProcessing = true
        
        scope.launch {
            try {
                val processingStartTime = System.currentTimeMillis()
                Log.d(TAG, "🔄 Processing ${audioLengthSec}s for LocalAgreement")
                
                // 🎯 정확한 처리: prompt 사용, 최고 품질 설정
                val prompt = generatePrompt()
                Log.d(TAG, "📋 Using prompt: '${prompt.take(50)}${if (prompt.length > 50) "..." else ""}' (${prompt.length} chars)")
                
                val whisperStartTime = System.currentTimeMillis()
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
                val whisperEndTime = System.currentTimeMillis()
                val whisperDuration = whisperEndTime - whisperStartTime
                
                Log.d(TAG, "⏱️ Whisper processing took ${whisperDuration}ms for ${audioLengthSec}s audio (ratio: ${String.format("%.2f", whisperDuration / (audioLengthSec * 1000))})")
                
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
                    if (confirmedResults.isNotEmpty()) {
                        // Agreement 성공한 경우: 버퍼 교체
                        previousBuffer.clear()
                        previousBuffer.addAll(newWords)
                        Log.d(TAG, "🔄 Buffer updated after successful agreement")
                    } else if (previousBuffer.isEmpty()) {
                        // 첫 번째 결과인 경우: 무조건 저장
                        previousBuffer.addAll(newWords)
                        Log.d(TAG, "🔄 First result stored in buffer for future agreement")
                        Log.d(TAG, "🔍 Buffer now contains: ${previousBuffer.map { it.text }}")
                    } else {
                        // Agreement 실패했지만 이전 버퍼가 있는 경우: 보존
                        Log.d(TAG, "🔄 Agreement failed, keeping previous buffer for force confirm")
                        Log.d(TAG, "🔍 Previous buffer preserved: ${previousBuffer.map { it.text }}")
                        Log.d(TAG, "🔍 New words not agreed: ${newWords.map { it.text }}")
                    }
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
            Log.d(TAG, "🔍 First result stored: ${newWords.map { it.text }}")
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
        Log.d(TAG, "🔍 Force confirm called - previousBuffer size: ${previousBuffer.size}")
        if (previousBuffer.isNotEmpty()) {
            val forcedConfirmed = previousBuffer.toList()
            confirmedWords.addAll(forcedConfirmed)
            val confirmedText = forcedConfirmed.joinToString(" ") { it.text }
            
            // 🔬 고급 로깅 추가
            AdvancedLoggingSystem.logResult(
                AdvancedLoggingSystem.EventType.RESULT_CONFIRMED,
                "LocalAgreement-ForceConfirm",
                confirmedText,
                0.9f
            )
            
            onConfirmedResult(confirmedText)
            Log.d(TAG, "Force confirmed due to silence: '$confirmedText'")
            previousBuffer.clear()
        } else {
            Log.w(TAG, "⚠️ Force confirm called but previousBuffer is empty!")
        }
    }
    
    fun stop() {
        scope.cancel()
    }
}