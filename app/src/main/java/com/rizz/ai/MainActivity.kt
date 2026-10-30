package com.rizz.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rizz.ai.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startWingmanServices(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission is required for AI Wingman", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupApiKeySection()
        setupPermissionsSection()
        setupVibeSection()
        setupToggleWingmanButton()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButtonState()
        updateWingmanButtonState()
    }

    private fun setupApiKeySection() {
        val currentKey = prefs.geminiApiKey
        if (currentKey.isNotBlank()) {
            binding.etApiKey.setText(currentKey)
            if (prefs.cachedModel.isNotBlank()) {
                binding.txtKeyStatus.text = "✅ Connected to: ${prefs.cachedModel}"
                binding.txtKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            } else {
                testKeyConnection(currentKey, silent = true)
            }
        }

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter a valid Gemini API key", Toast.LENGTH_SHORT).show()
            } else {
                prefs.geminiApiKey = key
                testKeyConnection(key, silent = false)
            }
        }

        binding.btnTestApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter your Gemini API key first", Toast.LENGTH_SHORT).show()
            } else {
                prefs.geminiApiKey = key
                testKeyConnection(key, silent = false)
            }
        }
    }

    private fun testKeyConnection(key: String, silent: Boolean) {
        binding.txtKeyStatus.text = getString(R.string.key_status_testing)
        binding.txtKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        activityScope.launch {
            val client = GeminiRizzClient(key)
            val result = client.discoverActiveModel()

            if (result.isSuccess) {
                val modelName = result.getOrNull() ?: "Gemini Flash"
                prefs.cachedModel = modelName
                binding.txtKeyStatus.text = "✅ Connected! Active model: $modelName"
                binding.txtKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                if (!silent) {
                    Toast.makeText(this@MainActivity, "Connected to $modelName!", Toast.LENGTH_SHORT).show()
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown connection error"
                binding.txtKeyStatus.text = "❌ $errorMsg"
                binding.txtKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                if (!silent) {
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupPermissionsSection() {
        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantA11y.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updateOverlayButtonState() {
        if (Settings.canDrawOverlays(this)) {
            binding.btnGrantOverlay.text = "Granted"
            binding.btnGrantOverlay.isEnabled = false
        } else {
            binding.btnGrantOverlay.text = "Grant"
            binding.btnGrantOverlay.isEnabled = true
        }
    }

    private fun setupVibeSection() {
        when (prefs.vibeMode) {
            PreferencesManager.VIBE_SMOOTH -> binding.rbSmooth.isChecked = true
            PreferencesManager.VIBE_TEASE -> binding.rbTease.isChecked = true
            PreferencesManager.VIBE_CHILL -> binding.rbChill.isChecked = true
            PreferencesManager.VIBE_BOLD -> binding.rbBold.isChecked = true
            else -> binding.rbAuto.isChecked = true
        }

        binding.rgVibe.setOnCheckedChangeListener { _, checkedId ->
            val vibe = when (checkedId) {
                R.id.rbSmooth -> PreferencesManager.VIBE_SMOOTH
                R.id.rbTease -> PreferencesManager.VIBE_TEASE
                R.id.rbChill -> PreferencesManager.VIBE_CHILL
                R.id.rbBold -> PreferencesManager.VIBE_BOLD
                else -> PreferencesManager.VIBE_AUTO
            }
            prefs.vibeMode = vibe
        }
    }

    private fun setupToggleWingmanButton() {
        binding.btnToggleWingman.setOnClickListener {
            if (FloatingRizzService.isRunning) {
                stopWingmanServices()
            } else {
                launchWingmanFlow()
            }
        }
    }

    private fun launchWingmanFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant 'Display over other apps' permission first", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (prefs.geminiApiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API key first", Toast.LENGTH_LONG).show()
            binding.etApiKey.requestFocus()
            return
        }

        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(screenCaptureIntent)
    }

    private fun startWingmanServices(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, captureIntent)

        val overlayIntent = Intent(this, FloatingRizzService::class.java).apply {
            action = FloatingRizzService.ACTION_START
        }
        ContextCompat.startForegroundService(this, overlayIntent)

        updateWingmanButtonState()
        Toast.makeText(this, "Rizz AI Wingman active! Switch to any chat app.", Toast.LENGTH_SHORT).show()

        moveTaskToBack(true)
    }

    private fun stopWingmanServices() {
        stopService(Intent(this, FloatingRizzService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        updateWingmanButtonState()
        Toast.makeText(this, "Wingman stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateWingmanButtonState() {
        if (FloatingRizzService.isRunning) {
            binding.btnToggleWingman.text = getString(R.string.btn_stop_service)
            binding.btnToggleWingman.setBackgroundColor(ContextCompat.getColor(this, R.color.hud_border))
        } else {
            binding.btnToggleWingman.text = getString(R.string.btn_start_service)
            binding.btnToggleWingman.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
