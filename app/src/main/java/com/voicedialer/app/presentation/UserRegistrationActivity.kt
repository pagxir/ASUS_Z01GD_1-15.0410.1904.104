package com.voicedialer.app.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voicedialer.app.databinding.ActivityUserRegistrationBinding
import com.voicedialer.app.domain.usecase.DynamicSpeakerRecognizer
import com.voicedialer.app.domain.usecase.GrammarGenerator
import com.voicedialer.app.domain.usecase.VoiceRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UserRegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserRegistrationBinding
    private lateinit var speakerRecognizer: DynamicSpeakerRecognizer
    private lateinit var voiceRecognizer: VoiceRecognizer
    private lateinit var grammarGenerator: GrammarGenerator

    private var isRecording = false
    private var recordingCount = 0
    private var currentName: String = ""
    private var recordingJob: Job? = null
    private val maxRecordings = 3

    companion object {
        private const val TAG = "UserRegistration"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()

        speakerRecognizer = DynamicSpeakerRecognizer(this)
        voiceRecognizer = VoiceRecognizer(this)
        grammarGenerator = GrammarGenerator(this)

        setupUI()
        updateRegisteredList()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupUI() {
        binding.buttonRegister.setOnClickListener {
            val name = binding.editTextName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入名字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentName = name
            recordingCount = 0
            startRecording()
        }

        binding.buttonMic.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (currentName.isNotEmpty()) {
                    startRecording()
                } else {
                    Toast.makeText(this, "请先输入名字", Toast.LENGTH_SHORT).show()
                }
            } else if (event.action == android.view.MotionEvent.ACTION_UP || 
                event.action == android.view.MotionEvent.ACTION_CANCEL) {
                stopRecording()
            }
            false
        }

        binding.buttonDelete.setOnClickListener {
            deleteSelectedTemplate()
        }

        binding.buttonBack.setOnClickListener {
            finish()
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (currentName.isEmpty()) {
            Toast.makeText(this, "请先输入名字", Toast.LENGTH_SHORT).show()
            return
        }

        if (recordingCount >= maxRecordings) {
            Toast.makeText(this, "已达到最大录音次数", Toast.LENGTH_SHORT).show()
            return
        }

        val started = voiceRecognizer.startRecording()
        if (!started) {
            Toast.makeText(this, "录音启动失败: ${voiceRecognizer.getInitError()}", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        recordingCount++
        binding.textViewStatus.text = "正在录音 ($recordingCount/$maxRecordings)..."
        binding.progressBar.visibility = View.VISIBLE

        recordingJob = lifecycleScope.launch {
            var count = 0
            while (isActive && isRecording && count < 40) {
                voiceRecognizer.readAudioData()
                delay(100)
                count++
            }
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        recordingJob?.cancel()

        voiceRecognizer.stopRecording()
        binding.progressBar.visibility = View.GONE

        val audioData = voiceRecognizer.getRecordedData()

        if (audioData.isEmpty()) {
            binding.textViewStatus.text = "录音失败，请重试"
            return
        }

        val success = speakerRecognizer.addTemplate(currentName, audioData)

        if (success) {
            val registeredNames = speakerRecognizer.getRegisteredNames()
            grammarGenerator.getRegisteredGrammar(registeredNames)
            
            binding.textViewStatus.text = "已注册: $currentName ($recordingCount/$maxRecordings)"
            updateRegisteredList()
            
            if (recordingCount < maxRecordings) {
                Toast.makeText(this, "继续录音以提高识别率", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "注册完成!", Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.textViewStatus.text = "注册失败: ${speakerRecognizer.getLastError()}"
        }
    }

    private fun deleteSelectedTemplate() {
        val selectedName = binding.editTextName.text.toString().trim()
        if (selectedName.isEmpty()) {
            Toast.makeText(this, "请输入要删除的名字", Toast.LENGTH_SHORT).show()
            return
        }

        val success = speakerRecognizer.removeTemplate(selectedName)
        if (success) {
            Toast.makeText(this, "已删除: $selectedName", Toast.LENGTH_SHORT).show()
            updateRegisteredList()
            
            val registeredNames = speakerRecognizer.getRegisteredNames()
            grammarGenerator.getRegisteredGrammar(registeredNames)
        } else {
            Toast.makeText(this, "删除失败: ${speakerRecognizer.getLastError()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRegisteredList() {
        val names = speakerRecognizer.getRegisteredNames()
        binding.textViewRegisteredList.text = if (names.isEmpty()) {
            "尚未注册任何用户"
        } else {
            "已注册: ${names.joinToString(", ")}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecognizer.release()
    }
}
