package com.mywanul.voicecommander

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * Foreground service that listens continuously in Bengali (bn-BD).
 * After every result or error it starts listening again, so the user
 * never has to press the mic button twice. Heard commands are sent to
 * [CommandExecutor]; status goes to MainActivity as broadcasts.
 */
class VoiceCommandService : Service() {

    companion object {
        const val ACTION_LOG = "com.mywanul.voicecommander.LOG"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEXT = "text"

        const val KIND_HEARD = "heard"
        const val KIND_PARTIAL = "partial"
        const val KIND_RESULT = "result"
        const val KIND_STATUS = "status"
        const val KIND_ERROR = "error"

        private const val CHANNEL_ID = "voice_commander"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun log(context: Context, kind: String, text: String) {
            val intent = Intent(ACTION_LOG).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_TEXT, text)
            }
            context.sendBroadcast(intent)
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var errorStreak = 0
    private lateinit var executor: CommandExecutor

    override fun onCreate() {
        super.onCreate()
        executor = CommandExecutor(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("শুনছি…"))
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(Listener())
        isRunning = true
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (listening || !isRunning) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            log(this, KIND_ERROR, "শোনা শুরু হয়নি: ${e.message}")
            scheduleRestart(1000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.postDelayed({ if (isRunning) startListening() }, delayMs)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log(applicationContext, KIND_STATUS, "শুনছি… কমান্ড বলুন")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            errorStreak++
            val name = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "অডিও সমস্যা"
                SpeechRecognizer.ERROR_CLIENT -> "ক্লায়েন্ট সমস্যা"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইকের অনুমতি নেই"
                SpeechRecognizer.ERROR_NETWORK -> "নেটওয়ার্ক সমস্যা"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "নেটওয়ার্ক সময় শেষ"
                SpeechRecognizer.ERROR_NO_MATCH -> "কথা বোঝা যায়নি"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ব্যস্ত"
                SpeechRecognizer.ERROR_SERVER -> "সার্ভার সমস্যা"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কথা শোনা যায়নি"
                else -> "এরর $error"
            }
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                log(applicationContext, KIND_ERROR, name)
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_CLIENT
            ) {
                return
            }
            val delay = if (errorStreak > 5) 2000L else 600L
            scheduleRestart(delay)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            errorStreak = 0
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (heard.isNotEmpty()) {
                log(applicationContext, KIND_HEARD, heard)
                handleCommand(heard)
            }
            scheduleRestart(300)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (partial.isNotEmpty()) {
                log(applicationContext, KIND_PARTIAL, partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleCommand(heard: String) {
        val command = CommandParser.parse(heard)
        if (command is VoiceCommand.StopListening) {
            log(this, KIND_RESULT, "শোনা বন্ধ করছি")
            stopSelf()
            return
        }
        executor.execute(command) { result ->
            log(this, KIND_RESULT, result)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ভয়েস কমান্ডার", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "ভয়েস কমান্ড শোনার সেবা"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ভয়েস কমান্ডার")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
