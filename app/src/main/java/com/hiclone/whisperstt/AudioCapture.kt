package com.hiclone.whisperstt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture {
    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var audioDataCallback: ((FloatArray) -> Unit)? = null
    private var shortAudioDataCallback: ((ShortArray) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setAudioDataCallback(callback: (FloatArray) -> Unit) {
        audioDataCallback = callback
    }

    fun setShortAudioDataCallback(callback: (ShortArray) -> Unit) {
        shortAudioDataCallback = callback
    }

    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return false
        }

        return try {
            // 권한 체크 (런타임에 권한이 거부될 수 있음)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            isRecording.set(true)
            recordingJob = scope.launch {
                recordAudio()
            }

            Log.i(TAG, "Audio recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    fun stopRecording() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording")
            return
        }

        isRecording.set(false)
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        
        audioRecord = null
        Log.i(TAG, "Audio recording stopped")
    }

    fun isRecording(): Boolean = isRecording.get()

    fun release() {
        stopRecording()
        scope.cancel()
    }

    private suspend fun recordAudio() {
        val buffer = FloatArray(BUFFER_SIZE / 4) // Float는 4바이트

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            return
        }

        while (isRecording.get()) {
            try {
                val readSize = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                
                if (readSize > 0) {
                    // 메모리 안전성을 위한 복사
                    val audioData = FloatArray(readSize)
                    buffer.copyInto(audioData, 0, 0, readSize)
                    
                    // 콜백 호출 시 예외 처리
                    try {
                        audioDataCallback?.invoke(audioData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in audio data callback", e)
                    }
                    
                    // ShortArray로도 변환하여 제공
                    val shortAudioData = ShortArray(readSize)
                    for (i in 0 until readSize) {
                        shortAudioData[i] = (audioData[i] * Short.MAX_VALUE).toInt().toShort()
                    }
                    
                    try {
                        shortAudioDataCallback?.invoke(shortAudioData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in short audio data callback", e)
                    }
                } else if (readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                    break
                } else if (readSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord ERROR_BAD_VALUE")
                    break
                } else if (readSize == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.e(TAG, "AudioRecord ERROR_DEAD_OBJECT")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio data", e)
                break
            }
        }
    }
}