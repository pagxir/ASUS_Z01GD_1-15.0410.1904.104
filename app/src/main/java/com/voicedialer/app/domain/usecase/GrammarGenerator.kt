package com.voicedialer.app.domain.usecase

import android.content.Context
import android.util.Log
import java.io.File

class GrammarGenerator(private val context: Context) {

    companion object {
        private const val TAG = "GrammarGenerator"
        private const val GRAMMAR_FILE = "dynamic_grammar.jsgf"
    }

    private val dialectAdapter = DialectAdapter()

    fun getGrammarFile(): File {
        return File(context.filesDir, GRAMMAR_FILE)
    }

    fun generateGrammar(contactNames: List<String>, registeredUsers: List<String>): String {
        val allNames = (contactNames + registeredUsers).distinct()
        
        val grammar = StringBuilder()
        grammar.append("#JSGF V1.0;\n")
        grammar.append("\n")
        grammar.append("grammar VoiceDialer;\n")
        grammar.append("\n")
        grammar.append("public <names> = \n")

        val nameVariants = allNames.flatMap { name ->
            generateNameVariants(name)
        }.distinct()

        nameVariants.forEachIndexed { index, variant ->
            val escaped = variant.replace("\"", "\\\"").replace("\n", "")
            grammar.append("    \"$escaped\"")
            if (index < nameVariants.size - 1) {
                grammar.append(" |")
            }
            grammar.append("\n")
        }

        grammar.append("    ;\n")

        val grammarStr = grammar.toString()
        saveGrammar(grammarStr)
        
        Log.d(TAG, "Generated grammar with ${nameVariants.size} variants for ${allNames.size} names")
        return grammarStr
    }

    private fun generateNameVariants(name: String): List<String> {
        val variants = mutableSetOf<String>()
        
        variants.add(name)
        variants.add(name.replace(" ", ""))
        
        val pinyinVariants = dialectAdapter.getPinyinVariants(name)
        variants.addAll(pinyinVariants)
        
        val toneVariants = dialectAdapter.applyToneSandhi(name)
        variants.addAll(toneVariants)
        
        val dialectVariants = dialectAdapter.getDialectVariants(name)
        variants.addAll(dialectVariants)
        
        return variants.toList()
    }

    private fun saveGrammar(grammar: String) {
        try {
            val file = getGrammarFile()
            file.writeText(grammar)
            Log.d(TAG, "Grammar saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save grammar", e)
        }
    }

    fun loadGrammar(): String? {
        return try {
            val file = getGrammarFile()
            if (file.exists()) {
                file.readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load grammar", e)
            null
        }
    }

    fun getRegisteredGrammar(registeredUsers: List<String>): String {
        val grammar = StringBuilder()
        grammar.append("#JSGF V1.0;\n")
        grammar.append("\n")
        grammar.append("grammar UserTemplates;\n")
        grammar.append("\n")
        grammar.append("public <userNames> = \n")

        val nameVariants = registeredUsers.flatMap { name ->
            generateNameVariants(name)
        }.distinct()

        nameVariants.forEachIndexed { index, variant ->
            val escaped = variant.replace("\"", "\\\"").replace("\n", "")
            grammar.append("    \"$escaped\"")
            if (index < nameVariants.size - 1) {
                grammar.append(" |")
            }
            grammar.append("\n")
        }

        grammar.append("    ;\n")

        return grammar.toString()
    }
}
