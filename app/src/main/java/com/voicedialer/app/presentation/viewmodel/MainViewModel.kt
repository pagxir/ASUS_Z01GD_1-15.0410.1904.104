package com.voicedialer.app.presentation.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.voicedialer.app.data.model.Contact
import com.voicedialer.app.data.repository.ContactRepository
import com.voicedialer.app.domain.usecase.TextToSpeechManager
import com.voicedialer.app.domain.usecase.VoiceRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val contactRepository = ContactRepository(application)
    val voiceRecognizer = VoiceRecognizer(application)
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

    init {
        checkVoskStatus()
        loadContacts()
    }

    private fun checkVoskStatus() {
        if (!voiceRecognizer.isReady()) {
            _status.value = "语音识别未就绪: ${voiceRecognizer.getInitError()}"
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
        
        if (!voiceRecognizer.isReady()) {
            _status.value = "语音识别未就绪，请检查模型文件"
            return
        }
        
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
                        val json = JSONObject(result)
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
        isRecording = false
        recognitionJob?.cancel()
        voiceRecognizer.stopRecording()
        
        val finalResult = voiceRecognizer.getFinalResult()
        if (finalResult.isNotEmpty()) {
            try {
                val json = JSONObject(finalResult)
                if (json.has("text")) {
                    val text = json.getString("text")
                    _recognitionResult.value = text
                    matchContact(text)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        
        _status.value = "松开结束录音"
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
