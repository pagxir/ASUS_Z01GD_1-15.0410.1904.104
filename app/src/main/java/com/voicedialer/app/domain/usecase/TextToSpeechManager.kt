package com.voicedialer.app.domain.usecase

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TextToSpeechManager(context: Context) {

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.CHINESE)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA && 
                               result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (!isInitialized) {
                    textToSpeech?.setLanguage(Locale.getDefault())
                    isInitialized = true
                }
                
                textToSpeech?.setSpeechRate(0.8f)
                textToSpeech?.setPitch(1.0f)
            }
        }
    }

    fun speak(text: String) {
        if (isInitialized) {
            val utteranceId = UUID.randomUUID().toString()
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        textToSpeech?.stop()
    }

    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
