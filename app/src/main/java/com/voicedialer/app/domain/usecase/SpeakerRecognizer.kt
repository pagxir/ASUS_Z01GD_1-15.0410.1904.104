package com.voicedialer.app.domain.usecase

import android.content.Context
import android.util.Log
import java.io.InputStreamReader
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ln

class SpeakerRecognizer(private val context: Context) {

    private var labels: List<String> = emptyList()
    private var embeddings: List<List<Double>> = emptyList()
    private var isLoaded = false
    private var lastError: String = ""

    companion object {
        private const val TAG = "SpeakerRecognizer"
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            Log.d(TAG, "Loading speaker model...")
            val inputStream = context.assets.open("speaker_model.json")
            val reader = InputStreamReader(inputStream)
            val jsonStr = reader.readText()
            reader.close()

            val jsonObj = org.json.JSONObject(jsonStr)
            
            val labelsArray = jsonObj.getJSONArray("labels")
            labels = (0 until labelsArray.length()).map { 
                labelsArray.getString(it)
            }.distinct()
            Log.d(TAG, "Labels: ${labels.joinToString(", ")}")

            val embeddingsArray = jsonObj.getJSONArray("embeddings")
            embeddings = (0 until embeddingsArray.length()).map { i ->
                val emb = embeddingsArray.getJSONArray(i)
                (0 until emb.length()).map { emb.getDouble(it) }
            }
            Log.d(TAG, "Embeddings count: ${embeddings.size}")

            isLoaded = labels.isNotEmpty() && embeddings.isNotEmpty()
            Log.d(TAG, "Model loaded: isLoaded=$isLoaded")
        } catch (e: Exception) {
            lastError = "模型加载失败: ${e.message}"
            Log.e(TAG, lastError, e)
            isLoaded = false
        }
    }

    fun isReady(): Boolean = isLoaded

    fun getLastError(): String = lastError

    fun recognize(audioData: ByteArray, sampleRate: Int = 16000): String? {
        if (!isLoaded) {
            lastError = "模型未加载"
            return null
        }

        if (audioData.isEmpty()) {
            lastError = "音频数据为空"
            return null
        }

        Log.d(TAG, "Audio data size: ${audioData.size} bytes")

        val features = extractSimpleFeatures(audioData)
        if (features == null || features.isEmpty()) {
            lastError = "特征提取失败"
            Log.e(TAG, lastError)
            return null
        }

        Log.d(TAG, "Features: ${features.size} dims, first 5: ${features.take(5)}")

        var bestMatch: String? = null
        var bestScore = Double.MAX_VALUE

        for ((index, storedEmb) in embeddings.withIndex()) {
            val distance = cosineDistance(features, storedEmb)
            val label = labels.getOrNull(index) ?: "unknown"
            Log.d(TAG, "Distance to '$label': $distance")
            
            if (distance < bestScore) {
                bestScore = distance
                bestMatch = label
            }
        }

        Log.d(TAG, "Best match: $bestMatch with score: $bestScore")

        return if (bestScore < 2.0) bestMatch else null
    }

    private fun extractSimpleFeatures(audioData: ByteArray): List<Double>? {
        val samples = ByteArrayToDoubleArray(audioData)
        
        if (samples.size < 400) {
            Log.w(TAG, "Sample too short: ${samples.size}")
            return null
        }

        val features = mutableListOf<Double>()

        val frameSize = 400
        val hopSize = 160
        val numFrames = (samples.size - frameSize) / hopSize + 1
        
        if (numFrames <= 0) return null

        val frameEnergies = mutableListOf<Double>()
        val frameZCRs = mutableListOf<Double>()
        val frameMeans = mutableListOf<Double>()
        
        for (i in 0 until numFrames) {
            val start = i * hopSize
            val end = minOf(start + frameSize, samples.size)
            val frame = samples.sliceArray(start until end)
            
            val energy = frame.map { it * it }.sum() / frameSize
            frameEnergies.add(energy)
            
            var zcr = 0
            for (j in 1 until frame.size) {
                if (frame[j] * frame[j-1] < 0) zcr++
            }
            frameZCRs.add(zcr.toDouble() / frameSize)
            
            frameMeans.add(frame.average())
        }

        features.add(ln(frameEnergies.average() + 1e-10))
        features.add(frameZCRs.average())
        features.add(frameMeans.average())
        
        features.add(frameEnergies.minOrNull() ?: 0.0)
        features.add(frameEnergies.maxOrNull() ?: 0.0)
        features.add(frameEnergies.map { ln(it + 1e-10) }.standardDeviation())
        
        features.add(frameZCRs.minOrNull() ?: 0.0)
        features.add(frameZCRs.maxOrNull() ?: 0.0)
        features.add(frameZCRs.standardDeviation())

        val dftSize = 64
        val frameIndices = if (numFrames >= 3) listOf(0, numFrames / 2, numFrames - 1) else listOf(0)
        
        for (frameIdx in frameIndices) {
            val start = frameIdx * hopSize
            val end = minOf(start + frameSize, samples.size)
            val frame = samples.sliceArray(start until end)
            
            for (k in 0 until minOf(dftSize, frameSize / 2)) {
                var real = 0.0
                var imag = 0.0
                for (n in 0 until frameSize) {
                    real += frame[n] * cos(2.0 * PI * k * n / frameSize)
                    imag += frame[n] * sin(2.0 * PI * k * n / frameSize)
                }
                val mag = sqrt(real * real + imag * imag)
                features.add(mag)
            }
        }

        return features
    }

    private fun List<Double>.standardDeviation(): Double {
        if (isEmpty()) return 0.0
        val mean = average()
        return sqrt(map { (it - mean) * (it - mean) }.average())
    }

    private fun ByteArrayToDoubleArray(bytes: ByteArray): DoubleArray {
        if (bytes.size < 2) return DoubleArray(0)
        
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() shl 8
            shorts[i] = (low or high).toShort()
        }
        return shorts.map { (it.toDouble() / 32768.0) }.toDoubleArray()
    }

    private fun cosineDistance(a: List<Double>, b: List<Double>): Double {
        val minLen = minOf(a.size, b.size)
        if (minLen == 0) return Double.MAX_VALUE
        
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until minLen) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        if (normA == 0.0 || normB == 0.0) return Double.MAX_VALUE
        
        val similarity = dotProduct / (sqrt(normA) * sqrt(normB))
        return 1.0 - similarity
    }

    fun getLabels(): List<String> = labels
}
