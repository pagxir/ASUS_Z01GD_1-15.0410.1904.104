package com.voicedialer.app.domain.usecase

import android.util.Log

class DialectAdapter {

    companion object {
        private const val TAG = "DialectAdapter"
    }

    private val pinyinMap = mapOf(
        '阿' to listOf("a", "e", "a1", "e1"),
        '八' to listOf("ba", "ba1"),
        '姨' to listOf("yi", "yi2"),
        '九' to listOf("jiu", "jiu3"),
        '六' to listOf("liu", "liu4", "lu"),
        '三' to listOf("san", "san1"),
        '哥' to listOf("ge", "ge1"),
        '嫂' to listOf("sao", "sao3"),
        '以' to listOf("yi", "yi3"),
        '娟' to listOf("juan", "juan1"),
        '打' to listOf("da", "da3"),
        '电' to listOf("dian", "dian4"),
        '话' to listOf("hua", "hua4")
    )

    private val cantoneseMap = mapOf(
        '阿' to listOf("aa3", "a3"),
        '八' to listOf("baat3"),
        '姨' to listOf("yi4"),
        '九' to listOf("gau2"),
        '六' to listOf("luk6"),
        '三' to listOf("saam1"),
        '哥' to listOf("go1"),
        '嫂' to listOf("sou2"),
        '以' to listOf("yi5"),
        '娟' to listOf("gyn1"),
        '打' to listOf("daa2"),
        '电' to listOf("din6"),
        '话' to listOf("waa6")
    )

    private val sichuanMap = mapOf(
        '阿' to listOf("a", "nga"),
        '八' to listOf("ba"),
        '姨' to listOf("yi", "ni"),
        '九' to listOf("jiu", "diu"),
        '六' to listOf("liu", "nu"),
        '三' to listOf("san", "san"),
        '哥' to listOf("go", "guo"),
        '嫂' to listOf("so", "suo"),
        '以' to listOf("yi", "ni"),
        '娟' to listOf("juan", "jyen"),
        '打' to listOf("da", "da"),
        '电' to listOf("dian", "dian"),
        '话' to listOf("hua", "xua")
    )

    fun getPinyinVariants(name: String): List<String> {
        val variants = mutableListOf<String>()
        
        for (char in name) {
            if (!char.isWhitespace()) {
                val pinyins = pinyinMap[char] ?: listOf(char.toString())
                if (variants.isEmpty()) {
                    variants.addAll(pinyins)
                } else {
                    val newVariants = mutableListOf<String>()
                    for (v in variants) {
                        for (p in pinyins) {
                            newVariants.add(v + p)
                        }
                    }
                    variants.clear()
                    variants.addAll(newVariants)
                }
            }
        }
        
        return variants
    }

    fun applyToneSandhi(name: String): List<String> {
        val variants = mutableListOf<String>()
        
        val pinyins = getPinyinVariants(name)
        
        for (py in pinyins) {
            var variant = py
            
            if (variant.contains("yi1") && !variant.startsWith("yi")) {
                variant = variant.replace("yi1", "yi")
            }
            if (variant.contains("yi2") && !variant.startsWith("yi")) {
                variant = variant.replace("yi2", "yi")
            }
            if (variant.contains("yi3") && !variant.startsWith("yi")) {
                variant = variant.replace("yi3", "yi")
            }
            if (variant.contains("yi4") && !variant.startsWith("yi")) {
                variant = variant.replace("yi4", "yi")
            }
            
            if (variant.contains("san1")) {
                variant = variant.replace("san1", "san")
            }
            
            variants.add(variant)
        }
        
        return variants.distinct()
    }

    fun getDialectVariants(name: String): List<String> {
        val variants = mutableListOf<String>()
        
        variants.addAll(getCantonesePronunciation(name))
        
        variants.addAll(getSichuanPronunciation(name))
        
        return variants.distinct()
    }

    private fun getCantonesePronunciation(name: String): List<String> {
        val variants = mutableListOf<String>()
        
        var cantonese = ""
        for (char in name) {
            if (!char.isWhitespace()) {
                val py = cantoneseMap[char] ?: listOf(char.toString())
                cantonese += py.first()
            }
        }
        
        if (cantonese.isNotEmpty()) {
            variants.add(cantonese)
        }
        
        return variants
    }

    private fun getSichuanPronunciation(name: String): List<String> {
        val variants = mutableListOf<String>()
        
        var sichuan = ""
        for (char in name) {
            if (!char.isWhitespace()) {
                val py = sichuanMap[char] ?: listOf(char.toString())
                sichuan += py.first()
            }
        }
        
        if (sichuan.isNotEmpty()) {
            variants.add(sichuan)
        }
        
        return variants
    }

    fun getSupportedDialects(): List<String> {
        return listOf("普通话", "粤语", "四川话")
    }

    fun setDialectPreference(dialect: String) {
        Log.d(TAG, "Dialect preference set to: $dialect")
    }
}
