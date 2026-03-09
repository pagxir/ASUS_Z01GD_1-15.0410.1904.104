package com.voicedialer.app.domain.usecase

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Model
import org.vosk.Recognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceRecognizer(private val context: Context) {

    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var voskInitialized = false
    private var initError: String = ""

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
                // ignore
            }
        }
        
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            try {
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model!!, sampleRate.toFloat())
                voskInitialized = true
            } catch (e: Exception) {
                initError = "模型加载失败: ${e.message}"
            }
        } else {
            initError = "模型文件不存在，请将vosk-model-cn文件夹放入app/src/main/assets/models/"
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
            e.printStackTrace()
        }
    }

    fun isReady(): Boolean = voskInitialized && recognizer != null

    fun getInitError(): String = initError

    fun getRecognizer(): Recognizer? = recognizer

    fun startRecording() {
        if (!voskInitialized || recognizer == null) {
            return
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()
    }

    fun recognize(): String {
        if (!isRecording || recognizer == null) {
            return ""
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val buffer = ByteArray(bufferSize)
        
        val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
        if (read > 0) {
            recognizer?.acceptWaveForm(buffer, read)
        }
        
        return recognizer?.result ?: ""
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recognizer?.reset()
    }

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
