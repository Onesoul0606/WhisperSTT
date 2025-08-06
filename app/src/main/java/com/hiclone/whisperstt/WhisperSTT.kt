package com.hiclone.whisperstt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.*

class WhisperSTT {

    companion object {
        private const val TAG = "WhisperSTT"
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("whisperstt")
                libraryLoaded = true
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                libraryLoaded = false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading native library", e)
                libraryLoaded = false
            }
        }
    }

    // Native 메소드들 (Step 1에서 추가한 JNI 메서드들)
    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray): String
    private external fun transcribeWithPrompt(audioData: FloatArray, prompt: String): String // ✨ 새로 추가
    private external fun transcribeRealtime(audioData: FloatArray): String // ✨ 새로 추가
    private external fun releaseModel()
    private external fun isModelLoaded(): Boolean

    // 실시간 STT용 변수들
    private var audioCapture: AudioCapture? = null
    private var streamingProcessor: Any? = null  // 프로세서 타입은 나중에 설정
    private var onRealtimeResult: ((String) -> Unit)? = null
    private var onStatusChanged: ((String) -> Unit)? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 전역 중지 상태 관리
    @Volatile
    private var isSTTStopped = false

    /**
     * 네이티브 라이브러리 로드 상태 확인
     */
    fun isNativeLibraryLoaded(): Boolean {
        return libraryLoaded
    }

    /**
     * Assets에서 모델 파일을 내부 저장소로 복사하고 로드
     */
    fun loadModelFromAssets(context: Context, assetFileName: String): Boolean {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot load model")
            return false
        }

        return try {
            // assets 폴더 내용 확인 (디버깅용)
            val assetManager = context.assets
            Log.d(TAG, "Checking assets folder contents...")

            try {
                val rootFiles = assetManager.list("")
                Log.d(TAG, "Root assets: ${rootFiles?.joinToString(", ")}")

                val modelsFiles = assetManager.list("models")
                Log.d(TAG, "Models folder: ${modelsFiles?.joinToString(", ")}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list assets", e)
            }

            // assetFileName이 "models/ggml-tiny.en.bin" 형태인지 확인
            val actualAssetPath = if (assetFileName.startsWith("models/")) {
                assetFileName
            } else {
                "models/$assetFileName"
            }
            
            val modelFile = copyAssetToFile(context, actualAssetPath)
            val result = loadModel(modelFile.absolutePath)
            Log.d(TAG, "Model loading result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets", e)
            false
        }
    }

    /**
     * 직접 파일 경로로 모델 로드
     */
    fun loadModelFromPath(modelPath: String): Boolean {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot load model")
            return false
        }

        val file = File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            return false
        }

        val result = loadModel(modelPath)
        Log.d(TAG, "Model loading result: $result")
        return result
    }

    /**
     * 오디오 데이터 음성 인식 (백그라운드 스레드에서 실행)
     */
    fun transcribeAudio(audioData: FloatArray, callback: (String) -> Unit) {
        // 중지 상태 확인
        if (isSTTStopped) {
            Log.d(TAG, "STT is stopped, ignoring transcription request")
            return
        }
        
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            callback("ERROR: Native library not loaded")
            return
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            callback("ERROR: Model not loaded")
            return
        }

        Log.d(TAG, "Starting transcription with ${audioData.size} samples")

        // 백그라운드 스레드에서 음성 인식 실행
        scope.launch {
            try {
                // 다시 한 번 중지 상태 확인
                if (isSTTStopped) {
                    Log.d(TAG, "STT stopped during transcription, aborting")
                    return@launch
                }
                
                val startTime = System.currentTimeMillis()
                val result = transcribe(audioData)
                val endTime = System.currentTimeMillis()

                // 결과 콜백 전에도 중지 상태 확인
                if (!isSTTStopped) {
                    Log.d(TAG, "Transcription completed in ${endTime - startTime}ms")
                    callback(result)
                } else {
                    Log.d(TAG, "STT stopped, ignoring transcription result")
                }
            } catch (e: Exception) {
                if (!isSTTStopped) {
                    Log.e(TAG, "Error during transcription", e)
                    callback("ERROR: Transcription failed - ${e.message}")
                }
            }
        }
    }

    /**
     * 동기식 음성 인식 (기존 메소드 유지)
     */
    fun transcribeAudioSync(audioData: FloatArray): String {
        // 중지 상태 확인
        if (isSTTStopped) {
            Log.d(TAG, "STT is stopped, ignoring sync transcription request")
            return ""
        }
        
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            return "ERROR: Native library not loaded"
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return "ERROR: Model not loaded"
        }

        Log.d(TAG, "Transcribing audio with ${audioData.size} samples")
        val startTime = System.currentTimeMillis()

        return try {
            // 처리 전에도 중지 상태 확인
            if (isSTTStopped) {
                Log.d(TAG, "STT stopped during sync transcription, aborting")
                return ""
            }
            
            val result = transcribe(audioData)

            // 결과 반환 전에도 중지 상태 확인
            if (isSTTStopped) {
                Log.d(TAG, "STT stopped, ignoring sync transcription result")
                return ""
            }

            val endTime = System.currentTimeMillis()
            Log.d(TAG, "Transcription completed in ${endTime - startTime}ms")
            result
        } catch (e: Exception) {
            if (!isSTTStopped) {
                Log.e(TAG, "Error in sync transcription", e)
                "ERROR: Transcription failed - ${e.message}"
            } else {
                ""
            }
        }
    }

    /**
     * Short 배열용 동기식 음성 인식 (실시간 STT용)
     */
    fun transcribeAudioSync(audioData: ShortArray): String {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            return ""
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return ""
        }

        return try {
            // Short 배열을 Float 배열로 변환 (정규화)
            val floatData = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
            transcribeAudioSync(floatData)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting short array to float array", e)
            ""
        }
    }

    /**
     * 논문 구현을 위한 Context Prompt 지원 음성 인식
     * Whisper Streaming 논문의 200자 제한 prompt 사용
     */
    fun transcribeWithContext(audioData: ShortArray, context: String): String {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            return ""
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return ""
        }

        return try {
            // Short 배열을 Float 배열로 변환
            val floatData = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
            
            if (context.isNotEmpty()) {
                // 논문의 200자 제한 적용
                val limitedPrompt = if (context.length > 200) {
                    context.takeLast(200)
                } else {
                    context
                }
                Log.d(TAG, "Transcribing with prompt (${limitedPrompt.length} chars): '${limitedPrompt.take(50)}${if (limitedPrompt.length > 50) "..." else ""}'")
                transcribeWithPrompt(floatData, limitedPrompt)
            } else {
                transcribe(floatData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in transcription with context", e)
            // Fallback to regular transcription
            try {
                transcribeAudioSync(audioData)
            } catch (fallbackE: Exception) {
                Log.e(TAG, "Fallback transcription also failed", fallbackE)
                "ERROR: ${e.message}"
            }
        }
    }
    
    /**
     * FloatArray용 Context 지원 음성 인식
     */
    fun transcribeWithContext(audioData: FloatArray, context: String): String {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            return ""
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return ""
        }

        return try {
            if (context.isNotEmpty()) {
                // 논문의 200자 제한 적용
                val limitedPrompt = if (context.length > 200) {
                    context.takeLast(200)
                } else {
                    context
                }
                transcribeWithPrompt(audioData, limitedPrompt)
            } else {
                transcribe(audioData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in transcription with context", e)
            // Fallback to regular transcription
            try {
                transcribe(audioData)
            } catch (fallbackE: Exception) {
                Log.e(TAG, "Fallback transcription also failed", fallbackE)
                "ERROR: ${e.message}"
            }
        }
    }

    /**
     * ✨ 실시간 최적화된 전사 (Step 2 추가)
     */
    fun transcribeRealtimeOptimized(audioData: ShortArray): String {
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded, cannot transcribe")
            return ""
        }

        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return ""
        }

        return try {
            // Short 배열을 Float 배열로 변환
            val floatData = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
            
            transcribeRealtime(floatData)
        } catch (e: Exception) {
            Log.e(TAG, "Error in realtime transcription", e)
            // Fallback to regular transcription
            transcribeAudioSync(audioData)
        }
    }

    /**
     * ✨ 프로세서 타입 선택 가능한 실시간 STT 시작 (Step 2 개선)
     */
    fun startRealtimeSTT(
        onResult: (String) -> Unit,
        onStatus: (String) -> Unit,
        processorType: StreamingProcessorType = StreamingProcessorType.WHISPER_STREAMING
    ): Boolean {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot start realtime STT: Native library not loaded")
            onStatus("네이티브 라이브러리가 로드되지 않았습니다")
            return false
        }

        if (!isModelLoaded()) {
            Log.e(TAG, "Cannot start realtime STT: Model not loaded")
            onStatus("모델이 로드되지 않았습니다")
            return false
        }

        if (audioCapture?.isRecording() == true) {
            Log.w(TAG, "Realtime STT already running")
            return true
        }

        // STT 시작 - 중지 상태 해제
        isSTTStopped = false
        
        onRealtimeResult = onResult
        onStatusChanged = onStatus

        // AudioCapture 초기화
        audioCapture = AudioCapture()

        // 선택된 프로세서 타입에 따라 초기화
        streamingProcessor = when (processorType) {
            StreamingProcessorType.SLIDING_WINDOW -> {
                SlidingWindowProcessor(this) { result ->
                    if (!isSTTStopped) {
                        onRealtimeResult?.invoke("🔄 $result")
                    }
                }
            }
            StreamingProcessorType.WHISPER_STREAMING -> {
                ImprovedWhisperStreamingProcessor(this) { text, isFinal ->
                    if (!isSTTStopped) {
                        if (isFinal) {
                            onRealtimeResult?.invoke("✅ $text")
                        } else {
                            onRealtimeResult?.invoke("⚡ $text")
                        }
                    }
                }
            }
        }

        // 오디오 데이터 콜백 설정
        audioCapture?.setAudioDataCallback { audioData ->
            try {
                when (val processor = streamingProcessor) {
                    is ImprovedWhisperStreamingProcessor -> processor.addAudioData(audioData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio data callback", e)
            }
        }
        
        // ShortArray 콜백 설정 (SlidingWindowProcessor용)
        audioCapture?.setShortAudioDataCallback { shortAudioData ->
            try {
                when (val processor = streamingProcessor) {
                    is SlidingWindowProcessor -> processor.addAudioData(shortAudioData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in short audio data callback", e)
            }
        }

        // 프로세서 시작
        when (val processor = streamingProcessor) {
            is SlidingWindowProcessor -> processor.start()
            is ImprovedWhisperStreamingProcessor -> processor.start()
        }

        // 오디오 녹음 시작
        val recordingStarted = audioCapture?.startRecording() ?: false
        if (recordingStarted) {
            onStatusChanged?.invoke("Realtime STT started with ${processorType.displayName}")
            Log.i(TAG, "Realtime STT started with ${processorType.displayName}")
            return true
        } else {
            Log.e(TAG, "Failed to start audio recording")
            onStatusChanged?.invoke("오디오 녹음을 시작할 수 없습니다")
            return false
        }
    }

    /**
     * 실시간 STT 중지
     */
    fun stopRealtimeSTT() {
        try {
            Log.i(TAG, "Stopping realtime STT...")
            
            // 0. 즉시 중지 상태 설정 (가장 먼저!)
            isSTTStopped = true
            
            // 1. 먼저 프로세서 중지 (새로운 데이터 처리 중단)
            when (val processor = streamingProcessor) {
                is SlidingWindowProcessor -> processor.stop()
                is ImprovedWhisperStreamingProcessor -> processor.stop()
            }
            
            // 2. 오디오 녹음 중지
            audioCapture?.stopRecording()
            
            // 3. 모든 실행 중인 코루틴 취소하고 새 스코프 생성
            scope.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // 4. 콜백 해제 (결과 무시)
            onRealtimeResult = null
            onStatusChanged = null
            
            // 5. 리소스 해제
            audioCapture?.release()
            audioCapture = null
            streamingProcessor = null
            
            Log.i(TAG, "All callbacks and resources cleared")
            Log.i(TAG, "Realtime STT stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping realtime STT", e)
            // 강제로 상태 초기화
            isSTTStopped = true
            audioCapture = null
            streamingProcessor = null
        }
    }

    /**
     * 실시간 STT 실행 중인지 확인
     */
    fun isRealtimeSTTRunning(): Boolean {
        return audioCapture?.isRecording() == true
    }

    /**
     * 모델 로드 상태 확인
     */
    fun isLoaded(): Boolean {
        return libraryLoaded && isModelLoaded()
    }

    /**
     * STT 중지 상태 확인 (프로세서에서 사용)
     */
    fun isSTTStopped(): Boolean {
        return isSTTStopped
    }

    /**
     * 리소스 해제
     */
    fun release() {
        try {
            stopRealtimeSTT()
            if (libraryLoaded) {
                releaseModel()
            }
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    /**
     * Assets 파일을 내부 저장소로 복사
     */
    private fun copyAssetToFile(context: Context, assetFileName: String): File {
        // assetFileName에서 파일명만 추출 (경로 제거)
        val fileName = File(assetFileName).name
        
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        val modelFile = File(modelDir, fileName)
        
        if (!modelFile.exists()) {
            try {
                context.assets.open(assetFileName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model file copied to: ${modelFile.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy model file", e)
                throw e
            }
        }

        return modelFile
    }
}

