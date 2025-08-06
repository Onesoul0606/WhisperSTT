package com.hiclone.whisperstt

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.TextView

/**
 * 실시간 스트리밍 STT UI 매니저
 * 
 * 🎨 UI 동작:
 * - 회색 텍스트: 임시 결과 (계속 변경됨)
 * - 검은 텍스트: 확정 결과 (더 이상 변경 안됨)
 * - 줄바꿈: 최종 결과 (문장 완료)
 */
class StreamingSTTUI(
    private val textView: TextView,
    private val context: Context
) {
    companion object {
        private const val TAG = "StreamingSTTUI"
        private val COLOR_TEMPORARY = Color.parseColor("#888888")  // 회색
        private val COLOR_CONFIRMED = Color.parseColor("#000000")  // 검은색
        private val COLOR_FINAL = Color.parseColor("#000000")      // 검은색
    }
    
    private var currentTemporaryText = ""
    private var currentConfirmedText = ""
    private val finalizedLines = mutableListOf<String>()
    
    fun handleTranscriptionResult(result: TrueStreamingSTTProcessor.TranscriptionResult) {
        when (result) {
            is TrueStreamingSTTProcessor.TranscriptionResult.Temporary -> {
                handleTemporaryResult(result.text, result.confidence)
            }
            is TrueStreamingSTTProcessor.TranscriptionResult.Confirmed -> {
                handleConfirmedResult(result.text)
            }
            is TrueStreamingSTTProcessor.TranscriptionResult.Final -> {
                handleFinalResult()
            }
        }
    }
    
    private fun handleTemporaryResult(text: String, confidence: Float) {
        currentTemporaryText = text
        updateUI()
        
        Log.d(TAG, "📝 Temporary UI update: '$text' (confidence: $confidence)")
    }
    
    private fun handleConfirmedResult(text: String) {
        // 임시 텍스트를 확정 텍스트로 승격
        currentConfirmedText += if (currentConfirmedText.isNotEmpty()) " $text" else text
        currentTemporaryText = "" // 임시 텍스트 클리어
        updateUI()
        
        Log.d(TAG, "✅ Confirmed UI update: '$text'")
    }
    
    private fun handleFinalResult() {
        // 현재 확정된 텍스트를 최종 라인으로 이동
        if (currentConfirmedText.isNotEmpty()) {
            finalizedLines.add(currentConfirmedText)
            currentConfirmedText = ""
        }
        
        // 남은 임시 텍스트도 최종으로 처리
        if (currentTemporaryText.isNotEmpty()) {
            finalizedLines.add(currentTemporaryText)
            currentTemporaryText = ""
        }
        
        updateUI()
        
        Log.d(TAG, "🏁 Final UI update - new line started")
    }
    
    private fun updateUI() {
        val displayText = buildDisplayText()
        
        textView.post {
            textView.text = displayText
            
            // 자동 스크롤 (선택사항)
            val parent = textView.parent
            if (parent is android.widget.ScrollView) {
                parent.post {
                    parent.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                }
            }
        }
    }
    
    private fun buildDisplayText(): String {
        val sb = StringBuilder()
        
        // 1. 완료된 라인들 (검은색)
        finalizedLines.forEach { line ->
            sb.append(line).append("\n")
        }
        
        // 2. 현재 확정된 텍스트 (검은색)
        if (currentConfirmedText.isNotEmpty()) {
            sb.append(currentConfirmedText)
        }
        
        // 3. 현재 임시 텍스트 (회색) - 실제 구현에서는 SpannableString 사용
        if (currentTemporaryText.isNotEmpty()) {
            if (currentConfirmedText.isNotEmpty()) {
                sb.append(" ")
            }
            sb.append("[TEMP] ").append(currentTemporaryText)
        }
        
        return sb.toString()
    }
    
    /**
     * 실제 프로덕션에서는 SpannableString을 사용해서 
     * 같은 TextView 내에서 다른 색상으로 표시
     */
    private fun buildStyledText(): android.text.SpannableStringBuilder {
        val spannableBuilder = android.text.SpannableStringBuilder()
        
        // 완료된 라인들
        finalizedLines.forEach { line ->
            val start = spannableBuilder.length
            spannableBuilder.append(line).append("\n")
            spannableBuilder.setSpan(
                android.text.style.ForegroundColorSpan(COLOR_FINAL),
                start, spannableBuilder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // 확정된 텍스트
        if (currentConfirmedText.isNotEmpty()) {
            val start = spannableBuilder.length
            spannableBuilder.append(currentConfirmedText)
            spannableBuilder.setSpan(
                android.text.style.ForegroundColorSpan(COLOR_CONFIRMED),
                start, spannableBuilder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // 임시 텍스트
        if (currentTemporaryText.isNotEmpty()) {
            if (currentConfirmedText.isNotEmpty()) {
                spannableBuilder.append(" ")
            }
            val start = spannableBuilder.length
            spannableBuilder.append(currentTemporaryText)
            spannableBuilder.setSpan(
                android.text.style.ForegroundColorSpan(COLOR_TEMPORARY),
                start, spannableBuilder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableBuilder.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                start, spannableBuilder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        return spannableBuilder
    }
    
    fun clear() {
        currentTemporaryText = ""
        currentConfirmedText = ""
        finalizedLines.clear()
        updateUI()
        
        Log.d(TAG, "🧹 UI cleared")
    }
    
    fun getCurrentText(): String {
        val allText = mutableListOf<String>()
        allText.addAll(finalizedLines)
        
        if (currentConfirmedText.isNotEmpty()) {
            allText.add(currentConfirmedText)
        }
        
        if (currentTemporaryText.isNotEmpty()) {
            allText.add(currentTemporaryText)
        }
        
        return allText.joinToString(" ")
    }
}