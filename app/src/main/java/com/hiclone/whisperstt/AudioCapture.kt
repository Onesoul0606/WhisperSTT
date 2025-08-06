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
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var onAudioDataCallback: ((ShortArray) -> Unit)? = null

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ) * BUFFER_SIZE_MULTIPLIER

    fun setAudioDataCallback(callback: (ShortArray) -> Unit) {
        onAudioDataCallback = callback
    }

    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return true
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            // 코루틴으로 백그라운드에서 오디오 데이터 읽기
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudio()
            }

            Log.i(TAG, "Recording started successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return false
        }
    }

    private suspend fun recordAudio() {
        val audioBuffer = ShortArray(bufferSize / 2) // 16-bit samples

        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            try {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

                if (readResult > 0) {
                    // 실제 읽은 데이터만 콜백으로 전달
                    val actualData = audioBuffer.copyOf(readResult)
                    onAudioDataCallback?.invoke(actualData)
                } else {
                    Log.w(TAG, "AudioRecord read returned: $readResult")
                    delay(10) // 에러 시 잠시 대기
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio data", e)
                break
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.get()) {
            return
        }

        isRecording.set(false)
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "Recording stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    // 메모리 해제
    fun release() {
        stopRecording()
        onAudioDataCallback = null
    }
}