package com.mywanul.voicecommander

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Device-control helpers for voice commands: alarm, torch, WiFi,
 * Bluetooth, volume and brightness. Everything uses only APIs that
 * exist at minSdk 26 / targetSdk 34. Anything a normal app may not
 * do directly (WiFi toggle on Android 10+, Bluetooth enable,
 * mobile data / airplane mode) falls back to the system settings
 * panel, where the AccessibilityService taps the toggle for the user.
 */

// ---------------------------------------------------------------------------
// Alarm
// ---------------------------------------------------------------------------

/** Fired by AlarmManager at the requested time: rings + vibrates + notifies. */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.mywanul.voicecommander.ALARM"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "voice_commander_alarm"

        @Volatile
        private var ringtone: Ringtone? = null

        @Volatile
        private var vibrator: Vibrator? = null

        /** Stops a ringing alarm (from voice or from the notification button). */
        fun stopRinging(context: Context) {
            try {
                ringtone?.stop()
            } catch (_: Exception) {
            }
            ringtone = null
            try {
                vibrator?.cancel()
            } catch (_: Exception) {
            }
            vibrator = null
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "অ্যালার্ম"

        // Default alarm ringtone (loops on Android 9+ so it is not missed).
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            val r = uri?.let { RingtoneManager.getRingtone(context, it) }
            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    r.isLooping = true
                }
                r.play()
                ringtone = r
            }
        } catch (_: Exception) {
        }

        // Vibration pattern.
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 1000, 500, 1000, 500), 0
                )
            )
            vibrator = vib
        } catch (_: Exception) {
        }

        // Ongoing notification with a "বন্ধ করো" (dismiss) button.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ভয়েস কমান্ডার অ্যালার্ম",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "অ্যালার্ম বাজার নোটিফিকেশন"
            nm.createNotificationChannel(channel)
        }
        val dismiss = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmDismissReceiver::class.java)
                .setAction(AlarmDismissReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("অ্যালার্ম বাজছে")
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_mic)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, "বন্ধ করো", dismiss)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}

/** Notification button / voice "অ্যালার্ম বন্ধ করো": stops the ringing. */
class AlarmDismissReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "com.mywanul.voicecommander.DISMISS_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        AlarmReceiver.stopRinging(context)
        AlarmHelper.cancelScheduled(context)
    }
}

object AlarmHelper {

    private const val PREFS = "voice_commander_alarms"
    private const val KEY_TRIGGER = "last_trigger_ms"

    /**
     * Schedules an alarm at [triggerAtMs]. Uses an exact alarm when the
     * app is allowed to; otherwise opens the system clock app with the
     * time prefilled (the user taps save there).
     */
    fun schedule(context: Context, triggerAtMs: Long, label: String): String {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !am.canScheduleExactAlarms()
        ) {
            val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                "সিস্টেমের ঘড়িতে অ্যালার্ম বসাতে খুললাম — অ্যাপের 'সঠিক সময়ের অ্যালার্ম' অনুমতি দিলে পরেরবার নিজে বসাবো"
            } catch (e: Exception) {
                "অ্যালার্ম বসানো যায়নি: ${e.message}"
            }
        }
        val pi = alarmPendingIntent(context, triggerAtMs, label)
        return try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_TRIGGER, triggerAtMs).apply()
            "অ্যালার্ম বসালাম: $label"
        } catch (e: Exception) {
            "অ্যালার্ম বসানো যায়নি: ${e.message}"
        }
    }

    /** Stops a ringing alarm and cancels a scheduled one. */
    fun dismiss(context: Context): String {
        AlarmReceiver.stopRinging(context)
        cancelScheduled(context)
        return "অ্যালার্ম বন্ধ করলাম"
    }

    fun cancelScheduled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trigger = prefs.getLong(KEY_TRIGGER, 0L)
        if (trigger > 0) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPendingIntent(context, trigger, "অ্যালার্ম"))
            prefs.edit().remove(KEY_TRIGGER).apply()
        }
    }

    private fun alarmPendingIntent(
        context: Context,
        triggerAtMs: Long,
        label: String
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_ALARM)
            .putExtra("label", label)
        val requestCode = (triggerAtMs / 60_000L).toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// ---------------------------------------------------------------------------
// Torch / flashlight
// ---------------------------------------------------------------------------

object TorchHelper {

    /** Turns the back camera flash on/off. Returns a Bengali result. */
    fun setTorch(context: Context, on: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "ক্যামেরার অনুমতি নেই — অ্যাপ থেকে অনুমতি দিন"
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        } ?: return "এই ফোনে ফ্ল্যাশ নেই"
        return try {
            cm.setTorchMode(cameraId, on)
            if (on) "টর্চ জ্বালালাম" else "টর্চ নেভালাম"
        } catch (e: Exception) {
            "টর্চ ${if (on) "জ্বালানো" else "নেভানো"} যায়নি: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
// WiFi (panel + settings fallbacks; direct toggle is blocked on Android 10+)
// ---------------------------------------------------------------------------

object WifiHelper {

    /** WiFi settings panel (Android 10+) or the WiFi settings page. */
    fun panelIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ---------------------------------------------------------------------------
// Bluetooth
// ---------------------------------------------------------------------------

object BluetoothHelper {

    /**
     * Tries to switch Bluetooth directly. Returns true when it worked,
     * false when the call itself failed, and null when a direct toggle
     * is not possible (no permission / no adapter) — the caller then
     * falls back to the Bluetooth settings panel.
     */
    fun setBluetooth(context: Context, on: Boolean): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager
        val adapter = manager.adapter ?: return null
        return try {
            if (on) adapter.enable() else adapter.disable()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            false
        }
    }

    /** Bluetooth settings page. There is no Bluetooth entry in
     *  Settings.Panel (only Internet, NFC, Volume, Wi-Fi), so open the
     *  Bluetooth settings page directly. */
    fun panelIntent(context: Context): Intent {
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

// ---------------------------------------------------------------------------
// Volume
// ---------------------------------------------------------------------------

object VolumeHelper {

    /** [adjust] is one of AudioManager.ADJUST_RAISE / LOWER / MUTE / UNMUTE. */
    fun adjust(context: Context, adjust: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI
        )
        return when (adjust) {
            AudioManager.ADJUST_RAISE -> "ভলিউম বাড়ালাম"
            AudioManager.ADJUST_LOWER -> "ভলিউম কমালাম"
            AudioManager.ADJUST_MUTE -> "সাউন্ড বন্ধ করলাম"
            AudioManager.ADJUST_UNMUTE -> "সাউন্ড চালু করলাম"
            else -> "ভলিউম বদলালাম"
        }
    }
}

// ---------------------------------------------------------------------------
// Brightness
// ---------------------------------------------------------------------------

object BrightnessHelper {

    /** Steps the screen brightness by 40 (out of 255). */
    fun adjust(context: Context, up: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                "ব্রাইটনেস বদলাতে সেটিংসে অনুমতি দিন — সেটিংস খুললাম"
            } catch (e: Exception) {
                "ব্রাইটনেসের সেটিংস খোলা যায়নি: ${e.message}"
            }
        }
        val cr = context.contentResolver
        val current = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            128
        }
        val next = (current + if (up) 40 else -40).coerceIn(10, 255)
        Settings.System.putInt(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
        return if (up) "ব্রাইটনেস বাড়ালাম" else "ব্রাইটনেস কমালাম"
    }
}
