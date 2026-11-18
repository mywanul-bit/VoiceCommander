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
 *
 * Live mode ([liveMode]): the restart loop keeps re-arming after
 * silent rounds too. It is NOT truly infinite — one SpeechRecognizer
 * session ends after one utterance, ~3-5 s of silence, or ~60 s, so
 * the loop re-arms it (see [scheduleRestart]). A watchdog stops the
 * loop after [MAX_SILENT_ROUNDS] silent rounds with no speech, so a
 * forgotten live session does not listen forever.
 */
class VoiceCommandService : Service() {

    companion object {
        const val ACTION_LOG = "com.mywanul.voicecommander.LOG"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LIVE = "live"
        const val ACTION_STOP = "com.mywanul.voicecommander.STOP_LISTENING"

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

        /**
         * Live mode: keep re-arming after silent rounds as well.
         * Read live on every re-arm, so MainActivity / the floating
         * bubble can switch it on or off while listening runs.
         */
        @Volatile
        var liveMode: Boolean = false

        // Idle bound: stop after this many silent rounds (no speech
        // heard) — live mode tolerates more silence than normal mode.
        private const val MAX_SILENT_ROUNDS_LIVE = 40
        private const val MAX_SILENT_ROUNDS_NORMAL = 10
        // A session that never delivers a terminal callback is forced
        // to reset after this long.
        private const val WATCHDOG_TIMEOUT_MS = 90_000L
        // Network failures back off instead of tight-looping.
        private const val NETWORK_BACKOFF_MS = 5_000L
        private const val MAX_NETWORK_STREAK = 3

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

    // Master switch for the restart loop. Every re-arm checks it, so
    // one place (stopListening / onDestroy) ends the loop for good.
    @Volatile
    private var wantListening = false

    // Turn counter: bumped on every startListening(). Callbacks carry
    // the turn they started in and bail out when it is stale, so a
    // late callback can never restart a session that was stopped.
    private var turn = 0
    private var sessionTurn = 0
    private var silentStreak = 0
    private var networkStreak = 0
    private var busyRecreated = false

    private val rearmRunnable = Runnable {
        if (wantListening && isRunning) startListening()
    }

    private val watchdogRunnable = Runnable {
        // A session with no terminal callback for 90 s is stuck —
        // force a reset instead of waiting forever.
        if (wantListening && listening) {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (_: Exception) {
            }
            recognizer = createRecognizer()
            listening = false
            if (wantListening) startListening()
        }
    }

    private lateinit var executor: CommandExecutor

    override fun onCreate() {
        super.onCreate()
        executor = CommandExecutor(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("শুনছি…"))
        recognizer = createRecognizer()
        wantListening = true
        isRunning = true
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_LIVE) == true) {
            liveMode = intent.getBooleanExtra(EXTRA_LIVE, liveMode)
        }
        if (!wantListening) {
            wantListening = true
            isRunning = true
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // A running task must not outlive listening — once the mic is
        // off there is no voice command left to stop it.
        ContinuousTaskManager.stop()
        stopListening()
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        isRunning = false
        super.onDestroy()
    }

    /** Ends the restart loop for good. Safe to call more than once. */
    private fun stopListening() {
        wantListening = false
        turn++
        handler.removeCallbacks(rearmRunnable)
        handler.removeCallbacks(watchdogRunnable)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        listening = false
    }

    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(Listener())
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (listening || !wantListening || !isRunning) return
        if (recognizer == null) {
            recognizer = createRecognizer()
            if (recognizer == null) {
                log(this, KIND_ERROR, "শোনা শুরু হয়নি: recognizer নেই")
                scheduleRestart(1000)
                return
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Best-effort only: many recognizers ignore these silence
            // lengths, so the restart loop below is what really keeps
            // listening alive — never rely on these alone.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3000
            )
        }
        try {
            listening = true
            turn++
            sessionTurn = turn
            recognizer?.startListening(intent)
            handler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
        } catch (e: Exception) {
            listening = false
            log(this, KIND_ERROR, "শোনা শুরু হয়নি: ${e.message}")
            scheduleRestart(1000)
        }
    }

    /** One pending re-arm at most, after a short delay — never a tight loop. */
    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(rearmRunnable)
        handler.postDelayed(rearmRunnable, delayMs)
    }

    private fun maxSilentRounds(): Int =
        if (liveMode) MAX_SILENT_ROUNDS_LIVE else MAX_SILENT_ROUNDS_NORMAL

    /** Too much silence: stop live listening instead of looping forever. */
    private fun stopForIdle() {
        log(
            applicationContext, KIND_STATUS,
            "অনেকক্ষণ কথা শোনা যায়নি — লাইভ শোনা থামাচ্ছি"
        )
        liveMode = false
        stopSelf()
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log(applicationContext, KIND_STATUS, "শুনছি… কমান্ড বলুন")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // NOT a terminal event — onResults still follows, and that
            // is the one place that re-arms. Re-arming here too would
            // double-start and cause ERROR_RECOGNIZER_BUSY.
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            handler.removeCallbacks(watchdogRunnable)
            if (!wantListening || turn != sessionTurn) return
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Silence / no hypothesis. Silent re-arm, only
                    // while a continuous session is active — never
                    // show these as errors to the user.
                    silentStreak++
                    if (silentStreak <= maxSilentRounds()) {
                        scheduleRestart(200)
                    } else {
                        stopForIdle()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Stale session / double start: recreate once,
                    // then retry once. A second BUSY gives up.
                    if (!busyRecreated) {
                        busyRecreated = true
                        try {
                            recognizer?.destroy()
                        } catch (_: Exception) {
                        }
                        recognizer = createRecognizer()
                        scheduleRestart(500)
                    } else {
                        log(
                            applicationContext, KIND_ERROR,
                            "শোনার সেবা ব্যস্ত — আবার চেষ্টা করুন"
                        )
                    }
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    // Hard failure: show it and back off, never loop.
                    networkStreak++
                    log(
                        applicationContext, KIND_ERROR,
                        "নেটওয়ার্ক সমস্যা — কিছুক্ষণ পর আবার চেষ্টা করছি"
                    )
                    if (networkStreak <= MAX_NETWORK_STREAK) {
                        scheduleRestart(NETWORK_BACKOFF_MS)
                    } else {
                        log(
                            applicationContext, KIND_ERROR,
                            "নেটওয়ার্ক সমস্যা — শোনা থামাচ্ছি"
                        )
                        stopSelf()
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_CLIENT -> {
                    log(
                        applicationContext, KIND_ERROR,
                        "মাইকের অনুমতি নেই বা ভাষার মডেল ইনস্টল নেই"
                    )
                    // No restart: this will not fix itself by retrying.
                }
                else -> {
                    log(applicationContext, KIND_ERROR, "এরর $error")
                    scheduleRestart(500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            handler.removeCallbacks(watchdogRunnable)
            if (!wantListening || turn != sessionTurn) return
            silentStreak = 0
            networkStreak = 0
            busyRecreated = false
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (heard.isNotEmpty()) {
                log(applicationContext, KIND_HEARD, heard)
                handleCommand(heard)
            }
            // The single re-arm site for a finished utterance.
            scheduleRestart(300)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (partial.isNotEmpty()) {
                // Speech is arriving — this round is not silent.
                silentStreak = 0
                log(applicationContext, KIND_PARTIAL, partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleCommand(heard: String) {
        val command = CommandParser.parse(heard)
        if (command is VoiceCommand.StopListening) {
            if (ContinuousTaskManager.isRunning()) {
                // "থামো" stops the running task, but listening keeps
                // going so the user can give the next command. The
                // executor's StopListening case stops the task (and
                // the speech) and reports it in the log.
                executor.execute(command) { result ->
                    log(this, KIND_RESULT, result)
                }
                scheduleRestart(300)
                return
            }
            ContinuousTaskManager.stop()
            TtsHelper.stop(this)
            log(this, KIND_RESULT, "শোনা বন্ধ করছি")
            stopListening()
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
