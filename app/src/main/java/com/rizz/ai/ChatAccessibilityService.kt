package com.rizz.ai

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatA11yService"
        private const val DEBOUNCE_DELAY_MS = 2500L

        // Popular supported target apps
        private val MONITORED_PACKAGES = setOf(
            "com.tinder",
            "co.hinge.app",
            "com.bumble.app",
            "com.instagram.android",
            "com.whatsapp",
            "org.telegram.messenger"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L

    private val scanRunnable = Runnable {
        if (FloatingRizzService.isRunning) {
            val intent = Intent(this, FloatingRizzService::class.java).apply {
                action = FloatingRizzService.ACTION_TRIGGER_SCAN
            }
            startService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!MONITORED_PACKAGES.contains(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime > DEBOUNCE_DELAY_MS) {
                    lastTriggerTime = now
                    handler.removeCallbacks(scanRunnable)
                    handler.postDelayed(scanRunnable, 1200)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        handler.removeCallbacks(scanRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanRunnable)
        super.onDestroy()
    }
}
