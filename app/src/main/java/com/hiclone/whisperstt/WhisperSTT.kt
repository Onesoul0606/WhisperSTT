package com.hiclone.whisperstt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WhisperSTT {

    companion object {
        private const val TAG = "WhisperSTT"

        init {
            try {
                System.loadLibrary("whisperstt")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    // Native 메소드들
    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray): String
    private external fun releaseModel()
    private external fun isModelLoaded(): Boolean

    // 실시간 STT용 추가 변수들
    private var audioCapture: AudioCapture? = null
    private var slidingWindowProcessor: SlidingWindowProcessor? = null
    private var onRealtimeResult: ((String) -> Unit)? = null
    private var onStatusChanged: ((String) -> Unit)? = null

    /**
     * Assets에서 모델 파일을 내부 저장소로 복사하고 로드
     */
    fun loadModelFromAssets(context: Context, assetFileName: String): Boolean {
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

            val modelFile = copyAssetToFile(context, assetFileName)
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
        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            callback("ERROR: Model not loaded")
            return
        }

        Log.d(TAG, "Starting transcription with ${audioData.size} samples")

        // 백그라운드 스레드에서 음성 인식 실행
        Thread {
            try {
                val startTime = System.currentTimeMillis()
                val result = transcribe(audioData)
                val endTime = System.currentTimeMillis()

                Log.d(TAG, "Transcription completed in ${endTime - startTime}ms")
                callback(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error during transcription", e)
                callback("ERROR: Transcription failed - ${e.message}")
            }
        }.start()
    }

    /**
     * 동기식 음성 인식 (기존 메소드 유지)
     */
    fun transcribeAudioSync(audioData: FloatArray): String {
        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return "ERROR: Model not loaded"
        }

        Log.d(TAG, "Transcribing audio with ${audioData.size} samples")
        val startTime = System.currentTimeMillis()

        val result = transcribe(audioData)

        val endTime = System.currentTimeMillis()
        Log.d(TAG, "Transcription completed in ${endTime - startTime}ms")

        return result
    }

    /**
     * Short 배열용 동기식 음성 인식 (실시간 STT용 추가)
     */
    fun transcribeAudioSync(audioData: ShortArray): String {
        if (!isModelLoaded()) {
            Log.w(TAG, "Model not loaded, cannot transcribe")
            return ""
        }

        // Short 배열을 Float 배열로 변환 (정규화)
        val floatData = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
        return transcribeAudioSync(floatData)
    }

    /**
     * 실시간 STT 시작
     */
    fun startRealtimeSTT(
        onResult: (String) -> Unit,
        onStatus: (String) -> Unit
    ): Boolean {
        if (!isModelLoaded()) {
            Log.e(TAG, "Cannot start realtime STT: Model not loaded")
            onStatus("모델이 로드되지 않았습니다")
            return false
        }

        if (audioCapture?.isRecording() == true) {
            Log.w(TAG, "Realtime STT already running")
            return true
        }

        onRealtimeResult = onResult
        onStatusChanged = onStatus

        // AudioCapture 초기화
        audioCapture = AudioCapture()

        // SlidingWindowProcessor 초기화
        slidingWindowProcessor = SlidingWindowProcessor(this) { result ->
            onRealtimeResult?.invoke(result)
        }

        // 오디오 데이터 콜백 설정
        audioCapture?.setAudioDataCallback { audioData ->
            slidingWindowProcessor?.addAudioData(audioData)
        }

        // 녹음 시작
        val recordingStarted = audioCapture?.startRecording() ?: false
        if (!recordingStarted) {
            Log.e(TAG, "Failed to start audio recording")
            onStatus("마이크 녹음 시작 실패")
            return false
        }

        // 프로세싱 시작
        slidingWindowProcessor?.start()

        onStatus("실시간 STT 시작됨")
        Log.i(TAG, "Realtime STT started successfully")
        return true
    }

    /**
     * 실시간 STT 중지
     */
    fun stopRealtimeSTT() {
        slidingWindowProcessor?.stop()
        audioCapture?.stopRecording()

        onStatusChanged?.invoke("실시간 STT 중지됨")
        Log.i(TAG, "Realtime STT stopped")
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
        return isModelLoaded()
    }

    /**
     * 모델 메모리 해제
     */
    fun release() {
        // 실시간 STT 중지
        stopRealtimeSTT()

        // 리소스 해제
        audioCapture?.release()
        slidingWindowProcessor?.release()

        // 모델 해제
        releaseModel()
        Log.d(TAG, "Model released")

        // 콜백 초기화
        onRealtimeResult = null
        onStatusChanged = null
    }

    /**
     * Assets에서 파일을 내부 저장소로 복사
     */
    private fun copyAssetToFile(context: Context, assetFileName: String): File {
        val assetManager = context.assets
        val outputFile = File(context.filesDir, assetFileName)

        // 디렉토리가 없으면 생성
        val parentDir = outputFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            val dirCreated = parentDir.mkdirs()
            Log.d(TAG, "Parent directory created: $dirCreated, path: ${parentDir.absolutePath}")
        }

        // 이미 파일이 존재하면 재사용
        if (outputFile.exists()) {
            Log.d(TAG, "Model file already exists, reusing: ${outputFile.absolutePath}")
            return outputFile
        }

        Log.d(TAG, "Copying model from assets to: ${outputFile.absolutePath}")

        try {
            assetManager.open(assetFileName).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalBytes % (1024 * 1024) == 0) { // 1MB마다 로그
                            Log.d(TAG, "Copied ${totalBytes / (1024 * 1024)} MB...")
                        }
                    }
                    Log.d(TAG, "Model file copied successfully, total size: ${totalBytes / (1024 * 1024)} MB")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset file: $assetFileName", e)
            throw e
        }

        return outputFile
    }
}