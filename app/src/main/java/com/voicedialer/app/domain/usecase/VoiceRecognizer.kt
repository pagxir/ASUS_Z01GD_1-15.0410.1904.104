package com.voicedialer.app.domain.usecase

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.LibVosk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File

class VoiceRecognizer(private val context: Context) {

    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var voskInitialized = false

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            
            val modelDir = File(context.filesDir, "vosk-model-cn")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val assetsModelPath = "models/vosk-model-cn"
            try {
                val assets = context.assets.list(assetsModelPath)
                if (assets != null && assets.isNotEmpty()) {
                    copyModelFromAssets(assetsModelPath, modelDir.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
                try {
                    val model = Model(modelDir.absolutePath)
                    recognizer = Recognizer(model, sampleRate.toFloat())
                    voskInitialized = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            if (!voskInitialized) {
                try {
                    val model = Model(context.filesDir.absolutePath + "/vosk-model-cn")
                    recognizer = Recognizer(model, sampleRate.toFloat())
                    voskInitialized = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun recognizeAudio(): Flow<String> = callbackFlow {
        if (!voskInitialized || recognizer == null) {
            trySend("识别引擎未初始化")
            close()
            return@callbackFlow
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            trySend("录音初始化失败")
            close()
            return@callbackFlow
        }

        isRecording = true
        audioRecord?.startRecording()

        val buffer = ByteArray(bufferSize)

        try {
            while (isRecording && currentCoroutineContext().isActive) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    recognizer?.acceptWaveForm(buffer, read)
                    
                    val result = recognizer?.result
                    if (!result.isNullOrEmpty()) {
                        trySend(result)
                    }
                }
            }
        } catch (e: Exception) {
            trySend("识别错误: ${e.message}")
        }

        awaitClose {
            stopRecording()
        }
    }.flowOn(Dispatchers.IO)

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
    }
}
