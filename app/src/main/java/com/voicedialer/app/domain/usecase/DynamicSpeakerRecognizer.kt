package com.voicedialer.app.domain.usecase

import android.content.Context
import android.util.Log
import java.io.File

class DynamicSpeakerRecognizer(private val context: Context) {

    private var labels: MutableList<String> = mutableListOf()
    private var embeddings: MutableList<MutableList<Double>> = mutableListOf()
    private var isLoaded = false
    private var lastError: String = ""

    companion object {
        private const val TAG = "DynamicSpeakerRec"
        private const val TEMPLATES_FILE = "user_templates.json"
    }

    init {
        loadTemplates()
    }

    private fun getTemplatesFile(): File {
        return File(context.filesDir, TEMPLATES_FILE)
    }

    private fun loadTemplates() {
        try {
            val file = getTemplatesFile()
            if (file.exists()) {
                val jsonStr = file.readText()
                val jsonObj = org.json.JSONObject(jsonStr)
                
                val labelsArray = jsonObj.optJSONArray("labels")
                val embeddingsArray = jsonObj.optJSONArray("embeddings")
                
                if (labelsArray != null && embeddingsArray != null) {
                    labels = (0 until labelsArray.length()).map { 
                        labelsArray.getString(it)
                    }.toMutableList()
                    
                    embeddings = (0 until embeddingsArray.length()).map { i ->
                        val emb = embeddingsArray.getJSONArray(i)
                        (0 until emb.length()).map { emb.getDouble(it) }.toMutableList()
                    }.toMutableList()
                    
                    Log.d(TAG, "Loaded ${labels.size} templates from storage")
                }
            } else {
                Log.d(TAG, "No templates file found, starting fresh")
            }
            
            isLoaded = true
        } catch (e: Exception) {
            lastError = "加载模板失败: ${e.message}"
            Log.e(TAG, lastError, e)
            isLoaded = true
        }
    }

    private fun saveTemplates() {
        try {
            val file = getTemplatesFile()
            val jsonObj = org.json.JSONObject()
            
            jsonObj.put("labels", org.json.JSONArray(labels))
            jsonObj.put("embeddings", org.json.JSONArray(embeddings.map { 
                org.json.JSONArray(it)
            }))
            
            file.writeText(jsonObj.toString())
            Log.d(TAG, "Saved ${labels.size} templates to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save templates", e)
        }
    }

    fun isReady(): Boolean = isLoaded

    fun getLastError(): String = lastError

    fun addTemplate(name: String, audioData: ByteArray, sampleRate: Int = 16000): Boolean {
        val features = extractRobustFeatures(audioData)
        if (features == null || features.isEmpty()) {
            lastError = "特征提取失败"
            return false
        }

        labels.add(name)
        embeddings.add(features.toMutableList())
        saveTemplates()
        
        Log.d(TAG, "Added template for '$name', total: ${labels.size}, features: ${features.size} dims")
        return true
    }

    fun removeTemplate(name: String): Boolean {
        val index = labels.indexOf(name)
        if (index == -1) {
            lastError = "未找到模板: $name"
            return false
        }
        
        labels.removeAt(index)
        embeddings.removeAt(index)
        saveTemplates()
        
        Log.d(TAG, "Removed template for '$name', remaining: ${labels.size}")
        return true
    }

    fun getRegisteredNames(): List<String> = labels.toList()

    fun clearAllTemplates() {
        labels.clear()
        embeddings.clear()
        saveTemplates()
        Log.d(TAG, "Cleared all templates")
    }

    fun recognize(audioData: ByteArray, sampleRate: Int = 16000): String? {
        if (!isLoaded) {
            lastError = "未加载"
            return null
        }

        if (audioData.isEmpty()) {
            lastError = "音频数据为空"
            return null
        }

        if (embeddings.isEmpty()) {
            lastError = "没有注册的用户"
            return null
        }

        val features = extractRobustFeatures(audioData)
        if (features == null || features.isEmpty()) {
            lastError = "特征提取失败"
            return null
        }

        val userTemplates = mutableMapOf<String, MutableList<List<Double>>>()
        for ((index, emb) in embeddings.withIndex()) {
            val label = labels.getOrNull(index) ?: continue
            userTemplates.getOrPut(label) { mutableListOf() }.add(emb)
        }

        val scores = mutableMapOf<String, MutableList<Double>>()

        for ((name, templateList) in userTemplates) {
            val distances = mutableListOf<Double>()
            
            for (template in templateList) {
                val distance = dtwDistance(features, template)
                distances.add(distance)
            }
            
            distances.sort()
            val medianDist = if (distances.size > 1) distances[distances.size / 2] else distances.first()
            
            scores.getOrPut(name) { mutableListOf() }.add(medianDist)
        }

        var bestMatch: String? = null
        var bestScore = Double.MAX_VALUE

        for ((name, scoreList) in scores) {
            val avgScore = scoreList.average()
            Log.d(TAG, "Score for '$name': $avgScore")
            
            if (avgScore < bestScore) {
                bestScore = avgScore
                bestMatch = name
            }
        }

        Log.d(TAG, "Best match: $bestMatch with score: $bestScore")

        return if (bestScore < 0.6) bestMatch else null
    }

    private fun dtwDistance(a: List<Double>, b: List<Double>): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.MAX_VALUE.toDouble()

        val dtw = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE } }
        dtw[0][0] = 0.0

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = kotlin.math.abs(a[i - 1] - b[j - 1])
                dtw[i][j] = cost + minOf(
                    dtw[i - 1][j],
                    dtw[i][j - 1],
                    dtw[i - 1][j - 1]
                )
            }
        }

        return dtw[n][m] / (n + m)
    }

    private fun vad(samples: DoubleArray, frameSize: Int, hopSize: Int): Pair<Int, Int> {
        val numFrames = (samples.size - frameSize) / hopSize + 1
        if (numFrames <= 0) return Pair(0, samples.size)

        val energies = mutableListOf<Double>()
        for (i in 0 until numFrames) {
            val start = i * hopSize
            val end = minOf(start + frameSize, samples.size)
            val frame = samples.sliceArray(start until end)
            val energy = frame.map { it * it }.average()
            energies.add(energy)
        }

        val maxEnergy = energies.maxOrNull() ?: 0.0
        val threshold = maxEnergy * 0.1

        var startFrame = 0
        var endFrame = numFrames - 1

        for (i in energies.indices) {
            if (energies[i] > threshold) {
                startFrame = i
                break
            }
        }

        for (i in energies.indices.reversed()) {
            if (energies[i] > threshold) {
                endFrame = i
                break
            }
        }

        val startSample = startFrame * hopSize
        val endSample = minOf((endFrame + 1) * hopSize + frameSize, samples.size)

        return Pair(startSample, endSample)
    }

    private fun extractRobustFeatures(audioData: ByteArray): List<Double>? {
        var samples = ByteArrayToDoubleArray(audioData)
        
        if (samples.size < 400) {
            Log.w(TAG, "Sample too short: ${samples.size}")
            return null
        }

        val (start, end) = vad(samples, 400, 160)
        if (end - start < 200) {
            Log.w(TAG, "VAD filtered too much: ${end - start}, using whole signal")
        } else {
            samples = samples.copyOfRange(start, end)
        }

        samples = normalizeAudio(samples)

        val features = mutableListOf<Double>()

        val frameSize = 400
        val hopSize = 160
        val numFrames = (samples.size - frameSize) / hopSize + 1
        
        if (numFrames <= 0) return null

        val frameEnergies = mutableListOf<Double>()
        val frameZCRs = mutableListOf<Double>()
        val frameStds = mutableListOf<Double>()
        val frameDeltas = mutableListOf<Double>()
        
        var prevEnergy = 0.0
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
            
            frameStds.add(frame.std())
            frameDeltas.add(kotlin.math.abs(energy - prevEnergy))
            prevEnergy = energy
        }

        features.add(Math.log(frameEnergies.average() + 1e-10))
        features.add(frameZCRs.average())
        features.add(frameStds.toList().average())
        features.add(frameDeltas.toList().average())
        
        features.add(frameEnergies.min())
        features.add(frameEnergies.max())
        features.add(frameEnergies.toList().std())
        
        features.add(frameZCRs.min())
        features.add(frameZCRs.max())
        features.add(frameZCRs.toList().std())

        features.add(frameStds.min())
        features.add(frameStds.max())
        features.add(frameStds.toList().std())

        val quantiles = listOf(0.1, 0.25, 0.5, 0.75, 0.9)
        for (q in quantiles) {
            features.add(frameEnergies.sorted()[ (q * frameEnergies.size).toInt().coerceIn(0, frameEnergies.size - 1) ])
            features.add(frameZCRs.sorted()[ (q * frameZCRs.size).toInt().coerceIn(0, frameZCRs.size - 1) ])
        }

        val dftSize = 128
        val keyFrames = if (numFrames >= 5) {
            listOf(0, numFrames / 4, numFrames / 2, 3 * numFrames / 4, numFrames - 1)
        } else {
            listOf(0, maxOf(0, numFrames - 1))
        }
        
        for (frameIdx in keyFrames) {
            val start = frameIdx * hopSize
            val end = minOf(start + frameSize, samples.size)
            val frame = samples.sliceArray(start until end)
            
            val spectrum = computeSpectrum(frame, dftSize)
            features.addAll(spectrum.take(32))
            
            val spectralCentroid = spectrum.mapIndexed { idx, mag -> idx * mag }.sum() / (spectrum.sum() + 1e-10)
            features.add(spectralCentroid)
            
            val spectralEnergy = spectrum.take(20).sum()
            features.add(spectralEnergy)
        }

        return normalizeFeatures(features)
    }

    private fun computeSpectrum(frame: DoubleArray, dftSize: Int): List<Double> {
        val spectrum = mutableListOf<Double>()
        val n = minOf(dftSize, frame.size)
        
        for (k in 0 until n) {
            var real = 0.0
            var imag = 0.0
            for (i in 0 until frame.size) {
                val window = 0.5 * (1 - kotlin.math.cos(2 * kotlin.math.PI * i / (frame.size - 1)))
                real += frame[i] * window * kotlin.math.cos(2.0 * kotlin.math.PI * k * i / frame.size)
                imag += frame[i] * window * kotlin.math.sin(2.0 * kotlin.math.PI * k * i / frame.size)
            }
            spectrum.add(kotlin.math.sqrt(real * real + imag * imag))
        }
        
        return spectrum
    }

    private fun normalizeAudio(samples: DoubleArray): DoubleArray {
        if (samples.isEmpty()) return samples
        
        val mean = samples.average()
        val centered = samples.map { it - mean }
        
        val std = centered.std()
        if (std < 1e-10) return samples
        
        return centered.map { it / std * 0.1 }.toDoubleArray()
    }

    private fun normalizeFeatures(features: List<Double>): List<Double> {
        if (features.isEmpty()) return features
        
        val mean = features.average()
        val std = features.std()
        
        if (std < 1e-10) return features
        
        return features.map { (it - mean) / std }
    }

    private fun DoubleArray.std(): Double {
        if (size == 0) return 0.0
        val mean = average()
        return map { (it - mean) * (it - mean) }.average().let { kotlin.math.sqrt(it) }
    }

    private fun List<Double>.std(): Double {
        if (isEmpty()) return 0.0
        val mean = average()
        return map { (it - mean) * (it - mean) }.average().let { kotlin.math.sqrt(it) }
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
}
