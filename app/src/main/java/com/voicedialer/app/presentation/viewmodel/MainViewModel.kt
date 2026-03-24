package com.voicedialer.app.presentation.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.voicedialer.app.data.model.Contact
import com.voicedialer.app.data.repository.ContactRepository
import com.voicedialer.app.domain.usecase.TextToSpeechManager
import com.voicedialer.app.domain.usecase.DynamicSpeakerRecognizer
import com.voicedialer.app.domain.usecase.GrammarGenerator
import com.voicedialer.app.domain.usecase.VoiceRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val contactRepository = ContactRepository(application)
    val voiceRecognizer = VoiceRecognizer(application)
    private val speakerRecognizer = DynamicSpeakerRecognizer(application)
    private val grammarGenerator = GrammarGenerator(application)
    private val ttsManager = TextToSpeechManager(application)

    private val _contacts = MutableLiveData<List<Contact>>()
    val contacts: LiveData<List<Contact>> = _contacts

    private val _status = MutableLiveData<String>("按住说话")
    val status: LiveData<String> = _status

    private val _recognitionResult = MutableLiveData<String>()
    val recognitionResult: LiveData<String> = _recognitionResult

    private val _matchedContact = MutableLiveData<Contact?>()
    val matchedContact: LiveData<Contact?> = _matchedContact

    private var recognitionJob: Job? = null
    private var isRecording = false
    private var useSpeakerRecognition = false

    init {
        checkRecognitionMode()
        loadContacts()
    }

    fun refreshSpeakerRecognizer() {
        checkRecognitionMode()
    }

    private fun checkRecognitionMode() {
        Log.d("VoiceDialer", "=== Checking recognition mode ===")
        Log.d("VoiceDialer", "Speaker recognizer ready: ${speakerRecognizer.isReady()}")
        Log.d("VoiceDialer", "Voice recognizer ready: ${voiceRecognizer.isReady()}")
        
        val registeredNames = speakerRecognizer.getRegisteredNames()
        
        if (registeredNames.isNotEmpty()) {
            useSpeakerRecognition = true
            grammarGenerator.getRegisteredGrammar(registeredNames)
            val statusText = "已就绪，可识别: ${registeredNames.joinToString(", ")}"
            _status.value = statusText
            Log.d("VoiceDialer", statusText)
        } else {
            useSpeakerRecognition = false
            if (voiceRecognizer.isReady()) {
                _status.value = "语音识别就绪，长按说话\n或点击右上角注册用户声音"
            } else {
                val voiceError = voiceRecognizer.getInitError()
                _status.value = "请添加模型文件或注册用户: $voiceError"
            }
        }
    }

    fun loadContacts() {
        viewModelScope.launch {
            try {
                val contactList = contactRepository.getAllContacts()
                _contacts.value = contactList
            } catch (e: Exception) {
                _status.value = "加载联系人失败: ${e.message}"
            }
        }
    }

    fun startRecording() {
        if (isRecording) return

        Log.d("VoiceDialer", "Start recording, useSpeakerRecognition: $useSpeakerRecognition")
        
        if (useSpeakerRecognition) {
            startSpeakerRecognition()
        } else if (voiceRecognizer.isReady()) {
            startVoskRecognition()
        } else {
            val statusText = "识别未就绪 - 说话人: ${speakerRecognizer.getLastError()}, 语音: ${voiceRecognizer.getInitError()}"
            _status.value = statusText
            Log.e("VoiceDialer", statusText)
        }
    }

    private fun startSpeakerRecognition() {
        _matchedContact.value = null
        
        val started = voiceRecognizer.startRecording()
        if (!started) {
            _status.value = "录音启动失败: ${voiceRecognizer.getInitError()}"
            Log.e("VoiceDialer", "Failed to start recording")
            return
        }
        
        isRecording = true
        _status.value = "正在录音，请说话..."

        recognitionJob = viewModelScope.launch {
            var count = 0
            while (isActive && isRecording && count < 30) {
                voiceRecognizer.readAudioData()
                delay(100)
                count++
                if (count % 10 == 0) {
                    _status.value = "录音中... ${count / 10}秒"
                }
            }
            stopRecording()
        }
    }

    private fun startVoskRecognition() {
        isRecording = true
        _status.value = "正在录音..."
        _matchedContact.value = null
        _recognitionResult.value = ""

        voiceRecognizer.startRecording()

        recognitionJob = viewModelScope.launch {
            while (isActive && isRecording) {
                val result = voiceRecognizer.recognize()
                if (result.isNotEmpty()) {
                    try {
                        val json = org.json.JSONObject(result)
                        if (json.has("text") && json.getString("text").isNotEmpty()) {
                            val text = json.getString("text")
                            _recognitionResult.postValue(text)
                            matchContact(text)
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                delay(100)
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recognitionJob?.cancel()
        
        if (useSpeakerRecognition) {
            stopSpeakerRecognition()
        } else {
            voiceRecognizer.stopRecording()
            val finalResult = voiceRecognizer.getFinalResult()
            if (finalResult.isNotEmpty()) {
                try {
                    val json = org.json.JSONObject(finalResult)
                    if (json.has("text")) {
                        val text = json.getString("text")
                        _recognitionResult.value = text
                        matchContact(text)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun stopSpeakerRecognition() {
        voiceRecognizer.stopRecording()
        
        val audioData = voiceRecognizer.getRecordedData()
        
        Log.d("VoiceDialer", "Recorded data size: ${audioData.size}")
        
        if (audioData.isEmpty()) {
            _status.value = "录音失败，音频数据为空"
            return
        }
        
        _status.value = "录音完成，正在识别..."
        
        val recognizedName = speakerRecognizer.recognize(audioData)
        
        if (recognizedName != null) {
            _recognitionResult.value = recognizedName
            matchContact(recognizedName)
            _status.value = "识别到: $recognizedName"
        } else {
            val error = speakerRecognizer.getLastError()
            _status.value = "未能识别: $error"
        }
    }

    private fun matchContact(text: String) {
        val contactList = _contacts.value ?: return
        if (text.isBlank()) return

        val matched = contactRepository.findContactByName(text, contactList)
        if (matched != null) {
            _matchedContact.value = matched
            _status.value = "已匹配: ${matched.name}"
            makeCall(matched.phone)
        } else {
            _status.value = "未找到匹配联系人: $text"
        }
    }

    fun makeCall(phoneNumber: String) {
        _status.value = "正在拨打: $phoneNumber"
    }

    fun playContactName(contact: Contact) {
        ttsManager.speak(contact.name)
    }

    fun getCallIntent(phoneNumber: String): Intent {
        return Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognizer.release()
        ttsManager.release()
    }
}
