package com.voicedialer.app.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicedialer.app.R
import com.voicedialer.app.databinding.ActivityMainBinding
import com.voicedialer.app.presentation.adapter.ContactAdapter
import com.voicedialer.app.presentation.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ContactAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.loadContacts()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupMicButton()
        setupRegisterButton()
        observeViewModel()
        checkPermissions()
    }

    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            onPlayClick = { contact ->
                viewModel.playContactName(contact)
            },
            onCallClick = { contact ->
                val intent = viewModel.getCallIntent(contact.phone)
                startActivity(intent)
            }
        )
        
        binding.recyclerViewContacts.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewContacts.adapter = adapter
        binding.recyclerViewContacts.addItemDecoration(
            androidx.recyclerview.widget.DividerItemDecoration(this, 
                androidx.recyclerview.widget.LinearLayoutManager.VERTICAL)
        )
    }

    private fun setupMicButton() {
        binding.buttonMic.setOnClickListener {
            // 点击无动作，需要长按
        }

        binding.buttonMic.setOnLongClickListener {
            viewModel.startRecording()
            true
        }

        binding.buttonMic.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP || 
                event.action == android.view.MotionEvent.ACTION_CANCEL) {
                viewModel.stopRecording()
            }
            false
        }
    }

    private fun setupRegisterButton() {
        binding.buttonRegister.setOnClickListener {
            val intent = Intent(this, UserRegistrationActivity::class.java)
            startActivity(intent)
        }
    }

    private fun observeViewModel() {
        viewModel.contacts.observe(this) { contacts ->
            adapter.submitList(contacts)
        }

        viewModel.status.observe(this) { status ->
            if (!status.isNullOrEmpty()) {
                binding.textViewStatus.text = status
            }
        }

        viewModel.recognitionResult.observe(this) { result ->
            if (result.isNotEmpty()) {
                Toast.makeText(this, "识别结果: $result", Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.matchedContact.observe(this) { contact ->
            if (contact != null) {
                Toast.makeText(this, getString(R.string.calling, contact.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSpeakerRecognizer()
    }
}
