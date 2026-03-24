package com.voicedialer.app.domain.usecase

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoiceRecognizer(private val context: Context) {

    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordedData = mutableListOf<Byte>()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var voskInitialized = false
    private var initError: String = ""

    companion object {
        private const val TAG = "VoiceRecognizer"
    }

    init {
        try {
            loadModel()
        } catch (e: Exception) {
            initError = e.message ?: "未知错误"
        }
    }

    private fun loadModel() {
        val modelDir = File(context.filesDir, "vosk-model-cn")
        
        if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() == true) {
            val assetsModelPath = "models/vosk-model-cn"
            try {
                val assets = context.assets.list(assetsModelPath)
                if (!assets.isNullOrEmpty()) {
                    copyModelFromAssets(assetsModelPath, modelDir.absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No Vosk model in assets: ${e.message}")
            }
        }
        
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            try {
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model!!, sampleRate.toFloat())
                voskInitialized = true
                Log.d(TAG, "Vosk model loaded")
            } catch (e: Exception) {
                initError = "模型加载失败: ${e.message}"
                Log.e(TAG, initError)
            }
        } else {
            initError = "无Vosk模型，跳过"
            Log.d(TAG, initError)
        }
    }

    private fun copyModelFromAssets(assetsPath: String, destPath: String) {
        try {
            val assets = context.assets.list(assetsPath) ?: return
            val destDir = File(destPath)
            
            for (asset in assets) {
                val srcPath = "$assetsPath/$asset"
                val destFile = File(destDir, asset)
                
                if (context.assets.list(srcPath)?.isNotEmpty() == true) {
                    destFile.mkdirs()
                    copyModelFromAssets(srcPath, destFile.absolutePath)
                } else {
                    destFile.parentFile?.mkdirs()
                    context.assets.open(srcPath).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model: ${e.message}")
        }
    }

    fun isReady(): Boolean = true

    fun getInitError(): String = initError

    fun getRecognizer(): Recognizer? = recognizer

    fun startRecording(): Boolean {
        Log.d(TAG, "Starting recording...")
        recordedData.clear()
        
        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            Log.d(TAG, "Buffer size: $bufferSize")
            
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size")
                return false
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val error = "AudioRecord init failed, state: ${audioRecord?.state}"
                Log.e(TAG, error)
                audioRecord?.release()
                audioRecord = null
                return false
            }

            isRecording = true
            audioRecord?.startRecording()
            Log.d(TAG, "Recording started successfully")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}")
            return false
        }
    }

    fun readAudioData(): Int {
        if (!isRecording || audioRecord == null) return 0
        
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val buffer = ByteArray(bufferSize)
        
        val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
        if (read > 0) {
            recordedData.addAll(buffer.toList().take(read))
            
            if (recognizer != null && voskInitialized) {
                recognizer?.acceptWaveForm(buffer, read)
            }
        }
        
        return read
    }

    fun recognize(): String {
        if (recognizer == null) return ""
        
        return try {
            readAudioData()
            recognizer?.result ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Recognize error: ${e.message}")
            ""
        }
    }

    fun stopRecording() {
        Log.d(TAG, "Stopping recording, data size: ${recordedData.size}")
        isRecording = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
        
        audioRecord = null
        recognizer?.reset()
    }

    fun getRecordedData(): ByteArray {
        Log.d(TAG, "Returning ${recordedData.size} bytes of audio data")
        return recordedData.toByteArray()
    }

    fun getRecordedDataSize(): Int = recordedData.size

    fun getFinalResult(): String {
        return recognizer?.finalResult ?: ""
    }

    fun release() {
        stopRecording()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
