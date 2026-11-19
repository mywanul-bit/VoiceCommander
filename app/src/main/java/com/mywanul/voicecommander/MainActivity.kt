package com.mywanul.voicecommander

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Simple Bengali UI: a big mic button to start/stop listening,
 * listening status, the last heard command, a command log,
 * and buttons for the microphone/contacts permissions and the
 * Accessibility service switch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnMic: ImageButton
    private lateinit var btnLive: Button
    private lateinit var btnBubble: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtTaskStatus: TextView
    private lateinit var txtLastCommand: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtA11yStatus: TextView
    private lateinit var txtBubbleStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var txtApiKeyStatus: TextView

    private var listening = false
    private var liveMode = false
    private var logStarted = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val kind = intent?.getStringExtra(VoiceCommandService.EXTRA_KIND) ?: return
            val text = intent.getStringExtra(VoiceCommandService.EXTRA_TEXT) ?: return
            when (kind) {
                VoiceCommandService.KIND_HEARD -> {
                    txtLastCommand.text = text
                    appendLog("🎙 $text")
                }
                VoiceCommandService.KIND_PARTIAL -> txtLastCommand.text = text
                VoiceCommandService.KIND_RESULT -> appendLog("✓ $text")
                VoiceCommandService.KIND_ERROR -> appendLog("⚠ $text")
                VoiceCommandService.KIND_STATUS -> txtStatus.text = text
            }
            updateTaskStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnMic = findViewById(R.id.btnMic)
        btnLive = findViewById(R.id.btnLive)
        btnBubble = findViewById(R.id.btnBubble)
        txtStatus = findViewById(R.id.txtStatus)
        txtTaskStatus = findViewById(R.id.txtTaskStatus)
        txtLastCommand = findViewById(R.id.txtLastCommand)
        txtLog = findViewById(R.id.txtLog)
        txtA11yStatus = findViewById(R.id.txtA11yStatus)
        txtBubbleStatus = findViewById(R.id.txtBubbleStatus)
        etApiKey = findViewById(R.id.etApiKey)
        txtApiKeyStatus = findViewById(R.id.txtApiKeyStatus)

        btnMic.setOnClickListener { toggleListening() }
        btnLive.setOnClickListener { toggleLive() }
        btnBubble.setOnClickListener { requestOverlayOrStartBubble() }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.btnExactAlarm).setOnClickListener {
            requestExactAlarmPermission()
        }
        findViewById<Button>(R.id.btnSaveApiKey).setOnClickListener { saveApiKey() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "ভয়েস কমান্ডার খুঁজে চালু করুন", Toast.LENGTH_LONG).show()
        }

        registerReceiver(logReceiver, IntentFilter(VoiceCommandService.ACTION_LOG))
        listening = VoiceCommandService.isRunning
        updateMicUi()
    }

    override fun onResume() {
        super.onResume()
        updateA11yStatus()
        updateBubbleStatus()
        updateExactAlarmStatus()
        updateApiKeyStatus()
        updateTaskStatus()
        val running = VoiceCommandService.isRunning
        if (running != listening) {
            listening = running
            updateMicUi()
        }
        liveMode = running && VoiceCommandService.liveMode
        updateLiveUi()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun toggleListening() {
        if (listening) {
            VoiceCommandService.liveMode = false
            liveMode = false
            updateLiveUi()
            stopService(Intent(this, VoiceCommandService::class.java))
            listening = false
            updateMicUi()
            txtStatus.setText(R.string.status_stopped)
            Toast.makeText(this, R.string.mic_off_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            return
        }
        VoiceCommandService.liveMode = false
        liveMode = false
        updateLiveUi()
        val intent = Intent(this, VoiceCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        listening = true
        updateMicUi()
        Toast.makeText(this, R.string.mic_on_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * Live mode: listening keeps re-arming after silent rounds too,
     * until the idle bound stops it (see VoiceCommandService).
     * Tapping again — or the mic button — turns it off.
     */
    private fun toggleLive() {
        if (liveMode) {
            VoiceCommandService.liveMode = false
            liveMode = false
            updateLiveUi()
            stopService(Intent(this, VoiceCommandService::class.java))
            listening = false
            updateMicUi()
            txtStatus.setText(R.string.status_stopped)
            Toast.makeText(this, R.string.live_stopped, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            return
        }
        VoiceCommandService.liveMode = true
        liveMode = true
        updateLiveUi()
        if (!VoiceCommandService.isRunning) {
            val intent = Intent(this, VoiceCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            listening = true
            updateMicUi()
        }
        Toast.makeText(this, R.string.live_started, Toast.LENGTH_SHORT).show()
    }

    /**
     * Floating bubble: needs the special "Display over other apps"
     * permission, granted only in Settings. The result code is
     * unreliable, so the status is re-checked in onResume().
     */
    private fun requestOverlayOrStartBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, R.string.bubble_prompt, Toast.LENGTH_LONG).show()
            return
        }
        startBubbleService()
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateBubbleStatus()
        Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_SHORT).show()
    }

    private fun updateBubbleStatus() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        txtBubbleStatus.setText(
            if (granted) R.string.bubble_on else R.string.bubble_off
        )
    }

    private fun updateLiveUi() {
        btnLive.setText(if (liveMode) R.string.live_on else R.string.live_off)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    /** Opens the system screen where exact-alarm permission is granted (Android 12+). */
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, R.string.exact_alarm_on, Toast.LENGTH_SHORT).show()
            return
        }
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (am.canScheduleExactAlarms()) {
            Toast.makeText(this, R.string.exact_alarm_on, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")
            )
        )
        Toast.makeText(this, R.string.exact_alarm_prompt, Toast.LENGTH_LONG).show()
    }

    /** Saves the free Gemini API key (from aistudio.google.com) for the AI features. */
    private fun saveApiKey() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.ai_key_missing, Toast.LENGTH_LONG).show()
            return
        }
        AIHelper.saveApiKey(this, key)
        etApiKey.text.clear()
        updateApiKeyStatus()
        Toast.makeText(this, R.string.ai_key_saved, Toast.LENGTH_SHORT).show()
    }

    private fun updateApiKeyStatus() {
        txtApiKeyStatus.setText(
            if (AIHelper.hasApiKey(this)) R.string.ai_key_saved_status
            else R.string.ai_key_missing_status
        )
    }

    private fun updateExactAlarmStatus() {
        val txt = findViewById<TextView>(R.id.txtExactAlarm)
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .canScheduleExactAlarms()
        } else {
            true
        }
        txt.setText(if (granted) R.string.exact_alarm_on else R.string.exact_alarm_off)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (granted) R.string.perm_granted else R.string.perm_needed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Shows the running continuous task ("করতে থাকো" / অনুবাদ), if any. */
    private fun updateTaskStatus() {
        txtTaskStatus.text = if (ContinuousTaskManager.isRunning()) {
            getString(
                R.string.task_status_running, ContinuousTaskManager.status()
            )
        } else {
            getString(R.string.task_status_none)
        }
    }

    private fun updateMicUi() {
        btnMic.setBackgroundResource(
            if (listening) R.drawable.circle_button_active else R.drawable.circle_button
        )
        if (!listening) txtStatus.setText(R.string.status_idle)
    }

    private fun updateA11yStatus() {
        txtA11yStatus.setText(
            if (isA11yEnabled()) R.string.a11y_on else R.string.a11y_off
        )
    }

    private fun isA11yEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        val myId = "$packageName/${CommanderAccessibilityService::class.java.name}"
        return services.any { it.id == myId }
    }

    private fun appendLog(line: String) {
        if (!logStarted) {
            txtLog.text = ""
            logStarted = true
        }
        val lines = txtLog.text.toString().split("\n").toMutableList()
        lines.add(line)
        // v1.2: cap the log so a future error flood can never fill
        // the screen (or memory) — only the last 50 lines are kept.
        val kept = lines.takeLast(50)
        txtLog.text = kept.joinToString("\n")
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
