package com.translator.gemini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: Button? = null
    private var overlayContainer: FrameLayout? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var apiKey: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("DATA")
        apiKey = intent?.getStringExtra("API_KEY") ?: ""

        val metrics = windowManager.defaultDisplay
        val size = Point()
        metrics.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y
        screenDensity = resources.displayMetrics.densityDpi

        if (data != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupImageReader()
            setupFloatingButton()
        }

        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupFloatingButton() {
        floatingButton = Button(this).apply {
            text = "AI Translate"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                captureAndTranslate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                captureAndTranslate()
                return true
            }
        })

        floatingButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (gestureDetector.onTouchEvent(event)) return true
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingButton, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButton, layoutParams)
    }

    private fun captureAndTranslate() {
        floatingButton?.text = "..."
        clearOverlays()

        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                floatingButton?.text = "AI Translate"
                return@postDelayed
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

            CoroutineScope(Dispatchers.Main).launch {
                val bubbles = GeminiApiClient.translateScreen(croppedBitmap, apiKey)
                renderTranslatedOverlays(bubbles)
                floatingButton?.text = "AI Translate"
            }
        }, 150)
    }

    private fun renderTranslatedOverlays(bubbles: List<TranslatedBubble>) {
        if (overlayContainer == null) {
            overlayContainer = FrameLayout(this)
            val containerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(overlayContainer, containerParams)
        }

        overlayContainer?.removeAllViews()

        for (bubble in bubbles) {
            val tv = TextView(this).apply {
                text = bubble.text
                setTextColor(Color.BLACK)
                textSize = 11f
                setBackgroundColor(Color.WHITE)
                setPadding(8, 4, 8, 4)
            }

            val left = (bubble.xmin / 1000f * screenWidth).toInt()
            val top = (bubble.ymin / 1000f * screenHeight).toInt()
            val width = ((bubble.xmax - bubble.xmin) / 1000f * screenWidth).toInt().coerceAtLeast(100)
            val height = ((bubble.ymax - bubble.ymin) / 1000f * screenHeight).toInt().coerceAtLeast(60)

            val params = FrameLayout.LayoutParams(width, height).apply {
                this.leftMargin = left
                this.topMargin = top
            }
            overlayContainer?.addView(tv, params)
        }
    }

    private fun clearOverlays() {
        overlayContainer?.removeAllViews()
    }

    private fun createNotification(): Notification {
        val channelId = "gemini_overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Gemini Screen Translator", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gemini Translator Active")
            .setContentText("Tap overlay pill or double-tap to translate raws")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    override fun onDestroy() {
        floatingButton?.let { windowManager.removeView(it) }
        overlayContainer?.let { windowManager.removeView(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}

