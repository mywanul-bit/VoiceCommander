package com.mywanul.voicecommander

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat

/**
 * ভাসমান বাবল (Messenger-এর চ্যাট-হেডের মতো): ফোনের যে কোনো স্ক্রিনের
 * উপরে ভেসে থাকে। টেনে সরানো যায়, কিনারায় ছেড়ে দিলে কাছের কিনারায়
 * গিয়ে আটকে যায়। ট্যাপ করলে ভয়েস শোনা শুরু হয়, ৪০০ মিলিসেকেন্ড
 * চেপে ধরলে লাইভ শোনা শুরু হয়।
 *
 * বাবল নিজে অন্য অ্যাপে ক্লিক করে না — ক্লিক/স্ক্রল/টাইপ করে
 * [CommanderAccessibilityService] (লেখা বা content-description দিয়ে
 * খুঁজে, না পেলে gesture দিয়ে)। বাবল শুধু ভয়েস শোনা চালু করে।
 *
 * লাগবে: "অন্য অ্যাপের উপরে দেখান" (SYSTEM_ALERT_WINDOW) অনুমতি —
 * ব্যবহারকারীকে নিজে Settings থেকে চালু করতে হয়, কোড দিয়ে চালু
 * করা যায় না।
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.mywanul.voicecommander.STOP_BUBBLE"
        private const val CHANNEL_ID = "voice_commander"
        private const val NOTIFICATION_ID = 1002
        private const val PREFS_NAME = "bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val LONG_PRESS_MS = 400L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubbleView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragging = false
    private var longPressFired = false
    private val touchSlop: Int by lazy {
        ViewConfiguration.get(this).scaledTouchSlop
    }

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            bubbleView?.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS
            )
            bubbleView?.setBackgroundResource(R.drawable.circle_button_active)
            startListening(live = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        isRunning = true
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(longPressRunnable)
        removeBubble()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** ট্যাপ: শোনা শুরু। চেপে ধরা (৪০০ মি.সে.): লাইভ শোনা শুরু। */
    private fun startListening(live: Boolean) {
        if (live) VoiceCommandService.liveMode = true
        if (!VoiceCommandService.isRunning) {
            val intent = Intent(this, VoiceCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            // Already listening — bring the app to the front so the
            // user sees the log and the Live toggle.
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        // Overlay permission is special: without it addView() throws
        // BadTokenException, so never call it unchecked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }
        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_floating_bubble, null) as ImageView
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val size = dpToPx(64)
        val startX = prefs.getInt(KEY_X, metrics.widthPixels - size - dpToPx(8))
        val startY = prefs.getInt(KEY_Y, metrics.heightPixels / 3)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    longPressFired = false
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging &&
                        (kotlin.math.abs(dx) > touchSlop ||
                            kotlin.math.abs(dy) > touchSlop)
                    ) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        layoutParams.x = (initialX + dx).toInt()
                            .coerceIn(0, metrics.widthPixels - size)
                        layoutParams.y = (initialY + dy).toInt()
                            .coerceIn(0, metrics.heightPixels - size)
                        windowManager.updateViewLayout(v, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        // Snap to the nearest screen edge.
                        val midX = layoutParams.x + size / 2
                        layoutParams.x =
                            if (midX < metrics.widthPixels / 2) 0
                            else metrics.widthPixels - size
                        windowManager.updateViewLayout(v, layoutParams)
                        prefs.edit()
                            .putInt(KEY_X, layoutParams.x)
                            .putInt(KEY_Y, layoutParams.y)
                            .apply()
                    } else if (longPressFired) {
                        view.setBackgroundResource(R.drawable.circle_button)
                    } else {
                        startListening(live = false)
                    }
                    dragging = false
                    longPressFired = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    dragging = false
                    longPressFired = false
                    view.setBackgroundResource(R.drawable.circle_button)
                    true
                }
                else -> false
            }
        }
        bubbleView = view
        params = layoutParams
        if (view.parent == null) {
            windowManager.addView(view, layoutParams)
        }
    }

    private fun removeBubble() {
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        params = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ভয়েস কমান্ডার", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "ভয়েস কমান্ড শোনার সেবা"
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ভয়েস কমান্ডার")
            .setContentText("বাবল চালু আছে — ট্যাপ করলে শোনা শুরু হবে")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
