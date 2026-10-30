package com.rizz.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "rizz_ai_screen_capture"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            initMediaProjection(resultCode, resultData)
        } else {
            Log.w(TAG, "ScreenCaptureService started without valid projection data")
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rizz AI Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enables real-time screen analysis for chat responses"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rizz AI Wingman Active")
            .setContentText("Screen reading enabled for instant suggestions")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Android 14+ requires callback registration before creating virtual display
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                cleanupVirtualDisplay()
            }
        }, mainHandler)

        setupDisplayMetrics()
        setupVirtualDisplay()
    }

    private fun setupDisplayMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Capture at 720p aspect scale for optimal performance and network payload
        val scale = 720f / metrics.widthPixels.coerceAtLeast(1)
        screenWidth = 720
        screenHeight = (metrics.heightPixels * scale).toInt()
        screenDensity = metrics.densityDpi
    }

    private fun setupVirtualDisplay() {
        cleanupVirtualDisplay()

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RizzScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    fun captureBitmap(onComplete: (Bitmap?) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            Log.e(TAG, "ImageReader is null, cannot capture")
            onComplete(null)
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                // Wait briefly for a frame if not immediately available
                mainHandler.postDelayed({
                    var retryImage: Image? = null
                    try {
                        retryImage = reader.acquireLatestImage()
                        val bmp = retryImage?.let { imageToBitmap(it) }
                        onComplete(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring retry frame", e)
                        onComplete(null)
                    } finally {
                        retryImage?.close()
                    }
                }, 100)
                return
            }

            val bitmap = imageToBitmap(image)
            onComplete(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture frame", e)
            onComplete(null)
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val rawBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        rawBitmap.copyPixelsFromBuffer(buffer)

        // Trim row padding if present
        return if (rowPadding == 0) {
            rawBitmap
        } else {
            val cleanBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            rawBitmap.recycle()
            cleanBitmap
        }
    }

    private fun cleanupVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
    }

    override fun onDestroy() {
        instance = null
        cleanupVirtualDisplay()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        super.onDestroy()
    }
}
