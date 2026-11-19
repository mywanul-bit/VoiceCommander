package com.mywanul.voicecommander

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen interaction helpers: browsing, copy/paste, comments on posts,
 * Google Drive (via the Drive app — no OAuth in this phase) and
 * read-aloud (TextToSpeech, Bengali). Everything uses only the
 * Android SDK; no new libraries.
 */

// ---------------------------------------------------------------------------
// Text-to-speech (Bengali, bn-BD)
// ---------------------------------------------------------------------------

/**
 * Text-to-speech (Bengali, bn-BD) — v1.3: the assistant TALKS BACK.
 *
 * Loud and clear on purpose: speech goes out on STREAM_MUSIC at
 * full utterance volume, a little slower than normal (0.9x reads
 * more clearly), and if the media volume is below 80% of the
 * maximum it is raised to 80% before speaking. [isSpeaking] is
 * true from the moment [speak] is called until the last queued
 * utterance finishes, so the listening loop can wait instead of
 * re-arming the microphone while its own voice is playing.
 */
object TtsHelper {

    private const val TAG = "TtsHelper"
    // The user's voice should be a bit loud: if the media volume is
    // below this share of the maximum, raise it to this level.
    private const val MIN_VOLUME_FRACTION = 0.8f
    // A little slower than normal reads more clearly in Bengali.
    private const val SPEECH_RATE = 0.9f
    private const val PITCH = 1.0f

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initStarted = false
    // v1.5 — the TTS engine failed to start at all (missing or
    // disabled engine): without this flag the queued speech sat in
    // pending forever, isSpeaking() stayed true forever, and the
    // listening loop waited forever — the app went deaf after the
    // first answer. When init fails the queue is dropped instead.
    private var initFailed = false
    private val pending = ArrayDeque<String>()
    // v1.5 — when the queue was filled but never started (engine
    // not ready yet): the moment it was filled, so isSpeechStuck()
    // can tell a stuck queue from one simply waiting its turn.
    private var pendingSinceMs = 0L
    private var utteranceCounter = 0
    // Utterances handed to the engine but not yet finished
    // (onDone / onError). Counted here — not only via
    // tts.isSpeaking — so the app knows it is talking from the
    // moment speak() is called, even before sound comes out.
    private val activeUtterances = AtomicInteger(0)

    private fun ensure(context: Context) {
        if (initStarted) return
        initStarted = true
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                // Engine broken or missing — drop anything queued so
                // the listening loop is never blocked on speech that
                // can never play.
                initFailed = true
                pending.clear()
                activeUtterances.set(0)
                Log.d(TAG, "TTS চালু হয়নি (status=$status) — কণ্ঠ বন্ধ থাকবে")
            }
            if (ready) {
                val bn = Locale("bn", "BD")
                val res = tts?.setLanguage(bn)
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    // No Bengali voice data on the phone — fall back to
                    // the system language instead of staying silent.
                    // (Bengali TTS depends on the phone's TTS engine,
                    // usually Google TTS — its Bengali voice data must
                    // be installed for a Bengali voice.)
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(PITCH)
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        // v1.4 — the bubble turns orange while the
                        // assistant talks, calm blue again when done.
                        override fun onStart(utteranceId: String?) {
                            BubbleState.set(BubbleWaveView.Mode.SPEAKING)
                        }

                        override fun onDone(utteranceId: String?) {
                            val remaining = activeUtterances.updateAndGet {
                                maxOf(0, it - 1)
                            }
                            if (remaining == 0) {
                                BubbleState.set(BubbleWaveView.Mode.IDLE)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        @Suppress("DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            val remaining = activeUtterances.updateAndGet {
                                maxOf(0, it - 1)
                            }
                            if (remaining == 0) {
                                BubbleState.set(BubbleWaveView.Mode.IDLE)
                            }
                        }
                    }
                )
                drain()
            }
        }
    }

    /** Speaks [text] aloud (long text is split into chunks). */
    fun speak(context: Context, text: String) {
        ensure(context)
        if (initFailed) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        boostVolume(context)
        if (pending.isEmpty()) pendingSinceMs = SystemClock.uptimeMillis()
        chunk(clean).forEach { pending.addLast(it) }
        if (ready) drain()
    }

    /**
     * The voice must be loud: if the media (STREAM_MUSIC) volume is
     * below 80% of the maximum, raise it to 80%. Logged so the user
     * can see the phone was turned up on purpose.
     */
    private fun boostVolume(context: Context) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE)
                as? AudioManager ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (max * MIN_VOLUME_FRACTION).toInt().coerceAtLeast(1)
            if (current < target) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.d(TAG, "মিডিয়া ভলিউম $current থেকে $target-এ বাড়ানো হলো (সর্বোচ্চ $max)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "ভলিউম বাড়ানো যায়নি: ${e.message}")
        }
    }

    /** Stops any ongoing speech and clears the queue. */
    fun stop(context: Context) {
        pending.clear()
        pendingSinceMs = 0L
        activeUtterances.set(0)
        BubbleState.set(BubbleWaveView.Mode.IDLE)
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * True while the assistant is talking — from the moment speak()
     * is called until the last queued utterance finishes. The live
     * listening loop uses this so it never re-arms the microphone
     * while its own voice is playing.
     */
    fun isSpeaking(): Boolean = try {
        activeUtterances.get() > 0 ||
            pending.isNotEmpty() ||
            tts?.isSpeaking == true
    } catch (_: Exception) {
        false
    }

    /**
     * v1.5 — true when speech was queued more than 15 s ago but
     * nothing is actually playing (the engine never became ready).
     * A long read-aloud is NOT stuck: while it plays,
     * activeUtterances > 0 or tts.isSpeaking is true.
     */
    fun isSpeechStuck(): Boolean = try {
        pending.isNotEmpty() &&
            activeUtterances.get() == 0 &&
            tts?.isSpeaking != true &&
            pendingSinceMs != 0L &&
            SystemClock.uptimeMillis() - pendingSinceMs > 15_000
    } catch (_: Exception) {
        false
    }

    private fun drain() {
        val engine = tts ?: return
        pendingSinceMs = 0L
        while (pending.isNotEmpty()) {
            val piece = pending.removeFirst()
            utteranceCounter++
            // Full utterance volume on the music stream, so the
            // answer is loud and clear.
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_MUSIC
                )
            }
            val res = engine.speak(
                piece, TextToSpeech.QUEUE_ADD, params, "vc-$utteranceCounter"
            )
            if (res == TextToSpeech.SUCCESS) activeUtterances.incrementAndGet()
        }
    }

    /** Splits at sentence ends into pieces of at most ~3000 characters. */
    private fun chunk(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[।.!?\\n])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString().trim())
                current.clear()
            }
        }
        for (s in sentences) {
            if (s.length > 3000) {
                flush()
                s.chunked(3000).forEach { out.add(it) }
                continue
            }
            if (current.length + s.length > 3000) flush()
            current.append(s).append(' ')
        }
        flush()
        return out
    }
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

object ClipboardHelper {

    fun copy(context: Context, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ভয়েস কমান্ডার", text))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun read(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (_: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Browsing / copy-paste / comments / Drive / read-aloud
// ---------------------------------------------------------------------------

object InteractionHelper {

    const val DRIVE_PKG = "com.google.android.apps.docs"

    private val COMMENT_LABELS = listOf(
        "কমেন্ট লিখুন", "কমেন্ট করুন", "মন্তব্য করুন", "মন্তব্য লিখুন",
        "মন্তব্য", "কমেন্ট", "write a comment", "add a comment", "comment"
    )

    private val POST_LABELS = listOf("পোস্ট করুন", "Post", "পোস্ট")

    private val BOOKMARK_LABELS = listOf(
        "bookmark", "বুকমার্ক", "save", "সংরক্ষণ", "add to bookmarks"
    )

    // ---------- browsing ----------

    /**
     * "সার্চ করো <কোয়েরি>": taps the search box, types the query and
     * submits it (the on-screen search button, else the first suggestion
     * containing the query — Android has no direct IME-Enter for an
     * accessibility service, so the keyboard Enter is the last resort).
     */
    fun search(service: CommanderAccessibilityService, query: String): String {
        val box = service.findSearchBox()
            ?: return "সার্চের ঘর পেলাম না — আগে ব্রাউজার খুলে সার্চ বক্সে ক্লিক করুন"
        if (!service.clickNode(box)) return "সার্চের ঘরে ক্লিক করা যায়নি"
        Thread.sleep(600)
        if (!service.setText(box, query)) return "সার্চে লেখা যায়নি"
        Thread.sleep(400)
        if (service.performImeEnter(box)) return "সার্চ করছি: $query"
        if (service.clickText("সার্চ") || service.clickText("Search")) {
            return "সার্চ করছি: $query"
        }
        val suggestion = service.findNonEditableByText(query)
        if (suggestion != null && service.clickNode(suggestion)) {
            return "সার্চ করছি: $query"
        }
        return "সার্চ লিখেছি, কিন্তু এন্টার চাপা যায়নি — কিবোর্ডের এন্টার চাপুন"
    }

    /** "লিংক খোলো <টেক্সট>": taps the clickable node with that text. */
    fun openLink(service: CommanderAccessibilityService, label: String): String {
        return if (service.clickText(label)) {
            "“$label” লিংক খুললাম"
        } else {
            "“$label” লিংক পেলাম না"
        }
    }

    /** "বুকমার্ক করো": taps the bookmark/star control if one is on screen. */
    fun bookmark(service: CommanderAccessibilityService): String {
        for (label in BOOKMARK_LABELS) {
            if (service.clickText(label)) return "বুকমার্ক করলাম"
        }
        return "বুকমার্কের বোতাম পেলাম না — এই পাতায় হয়তো বুকমার্ক নেই"
    }

    // ---------- copy / paste ----------

    /**
     * "কপি করো": if a text field is focused, select-all + copy from
     * it; otherwise copy all visible text on the screen.
     */
    fun copy(
        service: CommanderAccessibilityService,
        context: Context
    ): String {
        val focused = service.findFocusedEditable()
        if (focused != null) {
            return if (service.copyFrom(focused)) {
                "কার্সরের লেখা কপি করলাম"
            } else {
                "কপি করা যায়নি"
            }
        }
        return copyAll(service, context)
    }

    /** "সব কপি করো": copies every visible text node into the clipboard. */
    fun copyAll(
        service: CommanderAccessibilityService,
        context: Context
    ): String {
        val text = service.collectVisibleText()
        if (text.isBlank()) return "এই পাতায় কপি করার মতো লেখা পেলাম না"
        if (!ClipboardHelper.copy(context, text)) return "কপি করা যায়নি"
        return "পাতার লেখা কপি করলাম (${text.length} অক্ষর)"
    }

    /** "পেস্ট করো": pastes the clipboard into the focused text field. */
    fun paste(
        service: CommanderAccessibilityService,
        context: Context
    ): String {
        val clip = ClipboardHelper.read(context)?.takeIf { it.isNotBlank() }
            ?: return "ক্লিপবোর্ড খালি — আগে কিছু কপি করুন"
        val node = service.findEditable()
            ?: return "পেস্ট করার জায়গা পেলাম না — আগে কোনো লেখার ঘরে ক্লিক করুন"
        if (service.pasteInto(node)) return "পেস্ট করলাম"
        return if (service.setText(node, clip)) {
            "পেস্ট করলাম"
        } else {
            "পেস্ট করা যায়নি"
        }
    }

    /** "কপি করা লেখা পড়ো": reads the clipboard aloud. */
    fun readClipboardAloud(context: Context): String {
        val text = ClipboardHelper.read(context)?.takeIf { it.isNotBlank() }
            ?: return "ক্লিপবোর্ড খালি — আগে কিছু কপি করুন"
        TtsHelper.speak(context, text)
        return "কপি করা লেখা পড়ে শোনাচ্ছি: ${text.take(200)}"
    }

    // ---------- comments ----------

    /**
     * "কমেন্ট লিখো <টেক্সট>": finds the comment box by its hint or
     * label, taps it and types the text. Does NOT post it.
     */
    fun writeComment(
        service: CommanderAccessibilityService,
        text: String
    ): String {
        val box = service.findEditableWithHint(COMMENT_LABELS)
        if (box != null) {
            service.clickNode(box)
            Thread.sleep(500)
            return if (service.setText(box, text)) {
                "কমেন্ট লিখলাম (পোস্ট করিনি)"
            } else {
                "কমেন্ট লেখা যায়নি"
            }
        }
        // No hint-matched box: tap a "কমেন্ট লিখুন" label first,
        // then type into whatever editable field opens.
        for (label in COMMENT_LABELS) {
            val node = service.findByText(label)
            if (node != null && service.clickNode(node)) {
                Thread.sleep(900)
                val editable = service.findEditable()
                if (editable != null && service.setText(editable, text)) {
                    return "কমেন্ট লিখলাম (পোস্ট করিনি)"
                }
                return "কমেন্টের ঘর খুললাম, কিন্তু লেখা যায়নি"
            }
        }
        return "কমেন্টের ঘর পেলাম না — আগে পোস্টটি খুলে কমেন্টে ক্লিক করুন"
    }

    /** "কমেন্ট পোস্ট করো" / "পোস্ট করো": taps the post button. */
    fun postComment(service: CommanderAccessibilityService): String {
        for (label in POST_LABELS) {
            if (service.clickText(label)) return "কমেন্ট পোস্ট করলাম"
        }
        return "পোস্ট বোতাম পেলাম না — আগে কমেন্ট লিখুন"
    }

    // ---------- Google Drive (via the Drive app, no OAuth in this phase) ----------

    /** "ড্রাইভ খোলো": opens the Google Drive app. */
    fun openDrive(context: Context): String {
        if (!AppResolver.isInstalled(context, DRIVE_PKG)) {
            return "গুগল ড্রাইভ অ্যাপ ইনস্টল নেই — প্লে স্টোর থেকে ড্রাইভ ইনস্টল করুন"
        }
        val intent = context.packageManager.getLaunchIntentForPackage(DRIVE_PKG)
            ?: return "ড্রাইভ খোলা যায়নি"
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "ড্রাইভ খুললাম"
    }

    /**
     * "ড্রাইভে খুঁজো <নাম>": opens Drive, types the name in its
     * search box and taps the first matching result.
     */
    fun searchDrive(
        context: Context,
        service: CommanderAccessibilityService,
        name: String
    ): String {
        val opened = openDrive(context)
        if (opened != "ড্রাইভ খুললাম") return opened
        service.waitForPackage(DRIVE_PKG, 7000)
        Thread.sleep(1500)
        val box = service.findSearchBox()
            ?: return "ড্রাইভ খুললাম, কিন্তু সার্চের ঘর পেলাম না"
        if (!service.clickNode(box)) return "ড্রাইভের সার্চে ক্লিক করা যায়নি"
        Thread.sleep(600)
        if (!service.setText(box, name)) return "ড্রাইভে লেখা যায়নি"
        Thread.sleep(2000)
        val result = service.findNonEditableByText(name)
        if (result != null && service.clickNode(result)) {
            return "ড্রাইভে “$name” খুলছি"
        }
        return "ড্রাইভে “$name” খুঁজছি — ফলাফল এলে একটিতে ক্লিক করুন"
    }

    // ---------- read aloud ----------

    /** "এই পাতাটা পড়ে শোনাও": reads the visible screen text aloud. */
    fun readPageAloud(
        service: CommanderAccessibilityService,
        context: Context
    ): String {
        val text = service.collectVisibleText()
        if (text.isBlank()) return "এই পাতায় পড়ার মতো লেখা পেলাম না"
        TtsHelper.speak(context, text)
        return "পাতাটা পড়ে শোনাচ্ছি (${text.length} অক্ষর)"
    }
}
