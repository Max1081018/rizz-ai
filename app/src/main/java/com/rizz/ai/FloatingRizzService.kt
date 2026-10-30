package com.rizz.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.rizz.ai.model.RizzResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingRizzService : Service() {

    companion object {
        private const val TAG = "FloatingRizzService"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "rizz_ai_floating_hud"

        const val ACTION_START = "com.rizz.ai.action.START_FLOATING"
        const val ACTION_STOP = "com.rizz.ai.action.STOP_FLOATING"
        const val ACTION_TRIGGER_SCAN = "com.rizz.ai.action.TRIGGER_SCAN"

        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var preferencesManager: PreferencesManager
    private var geminiClient: GeminiRizzClient? = null

    // Bubble UI
    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleProgress: ProgressBar? = null

    // HUD UI
    private var hudView: View? = null
    private lateinit var hudParams: WindowManager.LayoutParams
    private var layoutLoading: LinearLayout? = null
    private var txtPsychologyInsight: TextView? = null
    private var txtVibeHeading: TextView? = null
    private var txtReply1: TextView? = null
    private var lblReply1Tag: TextView? = null
    private var txtReply2: TextView? = null
    private var lblReply2Tag: TextView? = null
    private var txtReply3: TextView? = null
    private var lblReply3Tag: TextView? = null

    private var isHudVisible = false
    private var isScanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferencesManager = PreferencesManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        initGeminiClient()
        setupBubbleView()
        setupHudView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_SCAN -> {
                triggerScanAndAnalyze()
            }
        }
        return START_STICKY
    }

    private fun initGeminiClient() {
        val key = preferencesManager.geminiApiKey
        if (key.isNotBlank()) {
            geminiClient = GeminiRizzClient(key)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rizz AI Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating Wingman controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rizz AI Wingman Overlay")
            .setContentText("Tap floating icon over any dating app for suggestions")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==========================================
    // Bubble Floating View
    // ==========================================
    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupBubbleView() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)
        bubbleProgress = bubbleView?.findViewById(R.id.bubbleProgress)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            bubbleParams.x = initialX + dx
                            bubbleParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating bubble position", e)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            toggleHud()
                        } else {
                            snapBubbleToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubble view", e)
        }
    }

    private fun snapBubbleToEdge() {
        val displaySize = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(displaySize)
        val screenWidth = displaySize.x
        val middle = screenWidth / 2

        bubbleParams.x = if (bubbleParams.x >= middle) {
            screenWidth - (bubbleView?.width ?: 120) - 20
        } else {
            20
        }
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error snapping bubble", e)
        }
    }

    // ==========================================
    // Expanded HUD View
    // ==========================================
    @SuppressLint("InflateParams")
    private fun setupHudView() {
        val inflater = LayoutInflater.from(this)
        hudView = inflater.inflate(R.layout.layout_floating_hud, null)

        layoutLoading = hudView?.findViewById(R.id.layoutLoading)
        txtPsychologyInsight = hudView?.findViewById(R.id.txtPsychologyInsight)
        txtVibeHeading = hudView?.findViewById(R.id.txtVibeHeading)

        val btnHudClose = hudView?.findViewById<ImageButton>(R.id.btnHudClose)
        val btnHudScan = hudView?.findViewById<Button>(R.id.btnHudScan)

        val btnReply1 = hudView?.findViewById<LinearLayout>(R.id.btnReply1)
        lblReply1Tag = hudView?.findViewById(R.id.lblReply1Tag)
        txtReply1 = hudView?.findViewById(R.id.txtReply1)

        val btnReply2 = hudView?.findViewById<LinearLayout>(R.id.btnReply2)
        lblReply2Tag = hudView?.findViewById(R.id.lblReply2Tag)
        txtReply2 = hudView?.findViewById(R.id.txtReply2)

        val btnReply3 = hudView?.findViewById<LinearLayout>(R.id.btnReply3)
        lblReply3Tag = hudView?.findViewById(R.id.lblReply3Tag)
        txtReply3 = hudView?.findViewById(R.id.txtReply3)

        btnHudClose?.setOnClickListener {
            hideHud()
        }

        btnHudScan?.setOnClickListener {
            triggerScanAndAnalyze()
        }

        btnReply1?.setOnClickListener {
            copyTextToClipboard(txtReply1?.text?.toString() ?: "")
        }

        btnReply2?.setOnClickListener {
            copyTextToClipboard(txtReply2?.text?.toString() ?: "")
        }

        btnReply3?.setOnClickListener {
            copyTextToClipboard(txtReply3?.text?.toString() ?: "")
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun toggleHud() {
        if (isHudVisible) {
            hideHud()
        } else {
            showHud()
            triggerScanAndAnalyze()
        }
    }

    private fun showHud() {
        if (!isHudVisible && hudView != null) {
            try {
                windowManager.addView(hudView, hudParams)
                isHudVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "Error showing HUD view", e)
            }
        }
    }

    private fun hideHud() {
        if (isHudVisible && hudView != null) {
            try {
                windowManager.removeView(hudView)
                isHudVisible = false
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding HUD view", e)
            }
        }
    }

    // ==========================================
    // Real-Time Analysis Flow
    // ==========================================
    fun triggerScanAndAnalyze() {
        if (isScanning) return
        isScanning = true

        initGeminiClient()
        if (geminiClient == null) {
            Toast.makeText(this, "Please set Gemini API key in Rizz AI app", Toast.LENGTH_LONG).show()
            isScanning = false
            return
        }

        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Toast.makeText(this, "Screen Capture not active. Launch from app first.", Toast.LENGTH_LONG).show()
            isScanning = false
            return
        }

        showLoading(true)

        // Hide overlay momentarily so it doesn't obstruct screenshot
        if (isHudVisible && hudView != null) {
            hudView?.visibility = View.INVISIBLE
        }
        bubbleView?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            captureService.captureBitmap { bitmap ->
                mainHandler.post {
                    // Restore overlay visibility
                    if (isHudVisible && hudView != null) {
                        hudView?.visibility = View.VISIBLE
                    }
                    bubbleView?.visibility = View.VISIBLE

                    if (bitmap == null) {
                        showLoading(false)
                        isScanning = false
                        Toast.makeText(this@FloatingRizzService, "Screen capture failed", Toast.LENGTH_SHORT).show()
                        return@post
                    }

                    // Process with Gemini Vision
                    serviceScope.launch {
                        val result = geminiClient?.analyzeChatScreen(
                            bitmap = bitmap,
                            preferredVibe = preferencesManager.vibeMode
                        )

                        showLoading(false)
                        isScanning = false

                        if (result != null && result.isSuccess) {
                            val rizz = result.getOrNull()
                            if (rizz != null) {
                                updateHudWithResult(rizz)
                                if (!isHudVisible) {
                                    showHud()
                                }
                            }
                        } else {
                            val err = result?.exceptionOrNull()?.message ?: "Error analyzing screen"
                            Toast.makeText(this@FloatingRizzService, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }, 80)
    }

    private fun showLoading(loading: Boolean) {
        layoutLoading?.visibility = if (loading) View.VISIBLE else View.GONE
        bubbleProgress?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun updateHudWithResult(result: RizzResult) {
        txtPsychologyInsight?.text = result.psychologyInsight
        txtVibeHeading?.text = "PSYCHOLOGICAL READ • ${result.detectedVibe.uppercase()}"

        val s1 = result.suggestions.getOrNull(0)
        if (s1 != null) {
            lblReply1Tag?.text = s1.tag
            txtReply1?.text = s1.text
        }

        val s2 = result.suggestions.getOrNull(1)
        if (s2 != null) {
            lblReply2Tag?.text = s2.tag
            txtReply2?.text = s2.text
        }

        val s3 = result.suggestions.getOrNull(2)
        if (s3 != null) {
            lblReply3Tag?.text = s3.tag
            txtReply3?.text = s3.text
        }
    }

    private fun copyTextToClipboard(text: String) {
        if (text.isBlank()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Rizz AI Reply", text)
        clipboard.setPrimaryClip(clip)

        // Haptic feedback
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback unavailable", e)
        }

        Toast.makeText(this, getString(R.string.overlay_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()

        try {
            if (bubbleView != null) {
                windowManager.removeView(bubbleView)
                bubbleView = null
            }
            if (isHudVisible && hudView != null) {
                windowManager.removeView(hudView)
                hudView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay views on destroy", e)
        }

        super.onDestroy()
    }
}
