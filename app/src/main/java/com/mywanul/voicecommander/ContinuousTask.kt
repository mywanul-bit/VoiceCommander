package com.mywanul.voicecommander

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Continuous-task mode ("করতে থাকো"): repeats one command on a loop
 * (optionally for a set time), or translates page after page into a
 * text file. Only one task runs at a time — starting a new one stops
 * the old one with a Bengali log line.
 *
 * The loop runs on its own thread and checks a stop flag between
 * steps, so "থামো" stops it quickly. Each step runs to completion
 * before the next, and there is always a delay between iterations
 * (10s for repeats, 2s between translated pages). A failed step is
 * retried once, then the task stops gracefully with a log line.
 * Everything is free — no new libraries, and translation uses the
 * same free Gemini key as the other AI commands.
 */
object ContinuousTaskManager {

    /** Longest single repeat run the user may ask for. */
    const val MAX_MINUTES = 120L

    /** Longest translation run (pages). */
    const val MAX_PAGES = 50

    private const val REPEAT_INTERVAL_MS = 10_000L
    private const val PAGE_WAIT_MS = 2_000L
    private const val STEP_TIMEOUT_MS = 30_000L

    private val lock = Any()
    private var worker: Thread? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var stopRequested = false

    @Volatile
    private var desc: String? = null

    @Volatile
    private var kind: String? = null // "repeat" or "translate"

    @Volatile
    private var iterations = 0

    @Volatile
    private var pagesDone = 0

    @Volatile
    private var totalPages = 0

    @Volatile
    private var deadlineMs: Long = 0

    @Volatile
    private var fileLocation: String? = null

    /** Bengali digits for display ("৩" instead of "3"). */
    private fun bn(n: Long): String {
        val map = mapOf(
            '0' to '০', '1' to '১', '2' to '২', '3' to '৩', '4' to '৪',
            '5' to '৫', '6' to '৬', '7' to '৭', '8' to '৮', '9' to '৯'
        )
        return n.toString().map { map[it] ?: it }.joinToString("")
    }

    fun isRunning(): Boolean = desc != null

    /** "কী করছো" — the running task and its progress, spoken and logged. */
    fun status(): String {
        val d = desc ?: return "কোনো কাজ চলছে না"
        return when (kind) {
            "translate" -> {
                val upto = if (totalPages > 0) {
                    " (মোট ${bn(totalPages.toLong())} পৃষ্ঠা পর্যন্ত)"
                } else {
                    ""
                }
                "অনুবাদ করতে আছি — ${bn(pagesDone.toLong())} পাতা হয়েছে$upto"
            }
            "repeat" -> {
                val left = if (deadlineMs > 0) {
                    val mins = (deadlineMs - System.currentTimeMillis()) / 60_000L
                    " — আর ${bn(maxOf(mins, 0L))} মিনিট বাকি"
                } else {
                    ""
                }
                "“$d” করতে আছি — ${bn(iterations.toLong())} বার হয়েছে$left"
            }
            else -> "“$d” করতে আছি"
        }
    }

    /**
     * Starts repeating [command] every 10 seconds until stopped, or
     * until [durationMs] (capped at 120 minutes) has passed.
     * Returns the Bengali start message for the log.
     */
    fun startRepeat(
        command: VoiceCommand,
        durationMs: Long?,
        executor: CommandExecutor,
        onLog: (String) -> Unit
    ): String {
        val wasRunning = stopInternal()
        val capped = durationMs?.let { minOf(it, MAX_MINUTES * 60_000L) }
        val descText = describe(command)
        val deadline = capped?.let { System.currentTimeMillis() + it }
        val myGen: Int
        synchronized(lock) {
            stopRequested = false
            desc = descText
            kind = "repeat"
            iterations = 0
            pagesDone = 0
            totalPages = 0
            deadlineMs = deadline ?: 0
            fileLocation = null
            myGen = generation
            val thread = Thread {
                runRepeatLoop(command, deadline, descText, executor, onLog, myGen)
            }
            worker = thread
            thread.start()
        }
        val timePart = if (capped != null) {
            " — ${bn(capped / 60_000L)} মিনিট ধরে"
        } else {
            ""
        }
        val prefix = if (wasRunning) "আগের কাজটি থামিয়ে " else ""
        return "${prefix}“$descText” করতে থাকছি$timePart — থামাতে “থামো” বলুন"
    }

    /**
     * Starts translating the visible page, saving each translated page
     * to a text file, then scrolling to the next page. Runs for
     * [maxPages] pages (null = until the end of the document, capped
     * at 50 pages), or until the user says "থামো". Returns the
     * Bengali start message (or the missing-key message) for the log.
     * Needs the free Gemini key — checked by the caller.
     */
    fun startTranslation(
        context: Context,
        maxPages: Int?,
        onLog: (String) -> Unit
    ): String {
        val appContext = context.applicationContext
        val wasRunning = stopInternal()
        val cap = maxPages?.let { minOf(it, MAX_PAGES) } ?: MAX_PAGES
        val handle = TranslationFile.create(appContext)
            ?: return "অনুবাদ ফাইল তৈরি করা যায়নি — আবার চেষ্টা করুন"
        val myGen: Int
        synchronized(lock) {
            stopRequested = false
            desc = "অনুবাদ করা"
            kind = "translate"
            iterations = 0
            pagesDone = 0
            totalPages = cap
            deadlineMs = 0
            fileLocation = handle.location
            myGen = generation
            val thread = Thread {
                runTranslateLoop(appContext, handle, cap, onLog, myGen)
            }
            worker = thread
            thread.start()
        }
        val prefix = if (wasRunning) "আগের কাজটি থামিয়ে " else ""
        val upto = if (maxPages != null) {
            " — ${bn(cap.toLong())} পৃষ্ঠা পর্যন্ত"
        } else {
            " — শেষ পাতা পর্যন্ত (সর্বোচ্চ ${bn(MAX_PAGES.toLong())} পৃষ্ঠা)"
        }
        return "${prefix}অনুবাদ শুরু করলাম$upto — ফাইল: ${handle.location}। থামাতে “থামো” বলুন"
    }

    /**
     * Stops the running task, if any. Returns a Bengali message, or
     * null when no task was running.
     */
    fun stop(): String? = synchronized(lock) { stopNowLocked() }

    /**
     * Stops the running task only when it is translating
     * ("চুপ করো" — the translation is the thing speaking).
     */
    fun stopTranslation(): String? = synchronized(lock) {
        if (desc == null || kind != "translate") {
            null
        } else {
            stopNowLocked()
        }
    }

    /** Stops any running task; true when one was running. */
    private fun stopInternal(): Boolean = synchronized(lock) {
        stopNowLocked() != null
    }

    /** Caller must hold [lock]. Stops the task and clears its state. */
    private fun stopNowLocked(): String? {
        val d = desc ?: return null
        stopRequested = true
        generation++
        worker?.interrupt()
        clearStateLocked()
        return "“$d” থামালাম"
    }

    /** Caller must hold [lock]. */
    private fun clearStateLocked() {
        desc = null
        kind = null
        worker = null
        iterations = 0
        pagesDone = 0
        totalPages = 0
        deadlineMs = 0
        fileLocation = null
    }

    private fun shouldStop(myGen: Int): Boolean =
        stopRequested || generation != myGen

    /** Sleeps in small chunks so "থামো" wakes the loop quickly. */
    private fun sleepInterruptibly(ms: Long, myGen: Int): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop(myGen)) return false
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return !shouldStop(myGen)
    }

    // ---------- repeat loop ----------

    private fun runRepeatLoop(
        command: VoiceCommand,
        deadline: Long?,
        descText: String,
        executor: CommandExecutor,
        onLog: (String) -> Unit,
        myGen: Int
    ) {
        var count = 0
        while (!shouldStop(myGen)) {
            if (deadline != null && System.currentTimeMillis() >= deadline) {
                onLog("সময় শেষ — “$descText” থামালাম (${bn(count.toLong())} বার করেছি)")
                break
            }
            count++
            iterations = count
            if (!runCommandOnce(command, executor, onLog, myGen)) break
            onLog("“$descText” — ${bn(count.toLong())} বার হলো")
            if (!sleepInterruptibly(REPEAT_INTERVAL_MS, myGen)) break
        }
        synchronized(lock) {
            if (generation == myGen) clearStateLocked()
        }
    }

    /**
     * Runs [command] once through the executor and waits for its
     * result (max 30 seconds). A failed step is retried once; if it
     * still fails the loop stops gracefully. Returns false when the
     * loop must stop.
     */
    private fun runCommandOnce(
        command: VoiceCommand,
        executor: CommandExecutor,
        onLog: (String) -> Unit,
        myGen: Int
    ): Boolean {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            if (shouldStop(myGen)) return false
            val latch = CountDownLatch(1)
            var result: String? = null
            var threw = false
            try {
                executor.execute(command) { r ->
                    result = r
                    latch.countDown()
                }
            } catch (_: Exception) {
                threw = true
            }
            val done = try {
                latch.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!threw && done && result != null && !looksLikeError(result!!)) {
                return true
            }
            val msg = when {
                threw -> "কমান্ড চালাতে পারলাম না"
                !done -> "এক ধাপে বেশি সময় লাগলো"
                else -> result ?: "অজানা সমস্যা"
            }
            if (attempt == 1) {
                onLog("$msg — আবার চেষ্টা করছি")
            } else {
                onLog("কাজটি করতে গিয়ে সমস্যা হলো — থামালাম: $msg")
                return false
            }
        }
        return false
    }

    /** Heuristic: the executor's Bengali error wording. */
    private fun looksLikeError(result: String): Boolean {
        val markers = listOf(
            "পেলাম না", "যায়নি", "পারিনি", "পারলাম না", "নেই",
            "ভুল হয়েছে", "করা যায়নি", "খোলা যায়নি", "হয়নি"
        )
        return markers.any { result.contains(it) }
    }

    // ---------- translate loop ----------

    private fun runTranslateLoop(
        context: Context,
        handle: TranslationFile.Handle,
        cap: Int,
        onLog: (String) -> Unit,
        myGen: Int
    ) {
        var pages = 0
        var emptyStreak = 0
        var lastHash = 0
        var ended = ""
        while (!shouldStop(myGen) && pages < cap) {
            val service = CommanderAccessibilityService.instance
            if (service == null) {
                ended = "Accessibility সেবা পাওয়া যাচ্ছে না — অনুবাদ থামালাম"
                break
            }
            val text = try {
                service.collectVisibleText(6000)
            } catch (_: Exception) {
                ""
            }
            val hash = text.hashCode()
            if (text.isBlank() || hash == lastHash) {
                emptyStreak++
                if (emptyStreak >= 2) {
                    ended = if (pages == 0) {
                        "অনুবাদ করার মতো লেখা পেলাম না — লেখা আছে এমন পাতা খুলুন"
                    } else {
                        "লেখা শেষ মনে হচ্ছে — অনুবাদ থামালাম"
                    }
                    break
                }
                onLog("নতুন লেখা পেলাম না — পরের পাতায় যাচ্ছি")
                service.swipe(true)
                if (!sleepInterruptibly(PAGE_WAIT_MS, myGen)) break
                continue
            }
            emptyStreak = 0
            lastHash = hash
            // Translate (one retry on failure).
            var translated: AIHelper.AiResult? = null
            var tries = 0
            while (tries < 2 && !shouldStop(myGen)) {
                tries++
                translated = try {
                    AIHelper.translate(context, text)
                } catch (e: Exception) {
                    AIHelper.AiResult(false, "অনুবাদে সমস্যা: ${e.message}")
                }
                if (translated.ok) break
                if (tries == 1) onLog("অনুবাদে সমস্যা — আবার চেষ্টা করছি")
            }
            if (translated == null || !translated.ok) {
                ended = "অনুবাদে সমস্যা হলো — থামালাম: " +
                    (translated?.text ?: "অজানা ভুল")
                break
            }
            if (!TranslationFile.append(context, handle, pages + 1, translated.text)) {
                ended = "ফাইলে লিখতে পারলাম না — অনুবাদ থামালাম"
                break
            }
            pages++
            pagesDone = pages
            // Only a short progress line is spoken — never the whole
            // translation, so the user can keep reading in peace.
            val pageLine = "${bn(pages.toLong())} নম্বর পাতা শেষ"
            onLog(pageLine)
            TtsHelper.speak(context, pageLine)
            if (pages >= cap) {
                ended = "${bn(cap.toLong())} পৃষ্ঠা পর্যন্ত অনুবাদ শেষ"
                break
            }
            service.swipe(true)
            if (!sleepInterruptibly(PAGE_WAIT_MS, myGen)) break
        }
        if (!shouldStop(myGen)) {
            if (ended.isEmpty()) ended = "অনুবাদ শেষ — মোট ${bn(pages.toLong())} পাতা"
            onLog("$ended (ফাইল: ${handle.location})")
        }
        synchronized(lock) {
            if (generation == myGen) clearStateLocked()
        }
    }

    // ---------- describing a command in Bengali ----------

    private fun describe(cmd: VoiceCommand): String = when (cmd) {
        is VoiceCommand.Scroll -> if (cmd.down) "নিচে যাওয়া" else "উপরে যাওয়া"
        is VoiceCommand.OpenApp -> "“${cmd.appName}” খোলা"
        VoiceCommand.CloseApp -> "অ্যাপ বন্ধ করা"
        VoiceCommand.GoHome -> "হোমে যাওয়া"
        VoiceCommand.GoBack -> "পেছনে যাওয়া"
        is VoiceCommand.Tap -> "“${cmd.label}” চাপা"
        is VoiceCommand.TypeText -> "“${cmd.text}” লেখা"
        is VoiceCommand.Message -> "“${cmd.name}”-কে মেসেজ পাঠানো"
        is VoiceCommand.Call -> "“${cmd.name}”-কে কল করা"
        is VoiceCommand.Search -> "“${cmd.query}” সার্চ করা"
        is VoiceCommand.OpenLink -> "“${cmd.label}” লিংক খোলা"
        VoiceCommand.Bookmark -> "বুকমার্ক করা"
        VoiceCommand.CopyText -> "কপি করা"
        VoiceCommand.CopyAllText -> "সব কপি করা"
        VoiceCommand.PasteText -> "পেস্ট করা"
        VoiceCommand.ReadPage -> "পাতা পড়ে শোনানো"
        VoiceCommand.ReadClipboardText -> "কপি করা লেখা পড়ে শোনানো"
        VoiceCommand.Explain -> "বুঝিয়ে দেওয়া"
        VoiceCommand.DraftComment -> "কমেন্টের খসড়া লেখা"
        is VoiceCommand.CommentWrite -> "কমেন্ট লেখা"
        VoiceCommand.CommentPost -> "কমেন্ট পোস্ট করা"
        VoiceCommand.DriveOpen -> "ড্রাইভ খোলা"
        is VoiceCommand.DriveSearch -> "ড্রাইভে “${cmd.name}” খোঁজা"
        is VoiceCommand.AlarmSet -> "অ্যালার্ম বসানো"
        VoiceCommand.AlarmDismiss -> "অ্যালার্ম বন্ধ করা"
        is VoiceCommand.Torch -> if (cmd.on) "টর্চ জ্বালানো" else "টর্চ নেভানো"
        VoiceCommand.WifiOn -> "ওয়াইফাই চালু করা"
        VoiceCommand.WifiOff -> "ওয়াইফাই বন্ধ করা"
        is VoiceCommand.WifiConnect -> "ওয়াইফাই কানেক্ট করা"
        is VoiceCommand.Bluetooth ->
            if (cmd.on) "ব্লুটুথ চালু করা" else "ব্লুটুথ বন্ধ করা"
        VoiceCommand.VolumeUp -> "ভলিউম বাড়ানো"
        VoiceCommand.VolumeDown -> "ভলিউম কমানো"
        VoiceCommand.VolumeMute -> "সাউন্ড বন্ধ করা"
        VoiceCommand.VolumeUnmute -> "সাউন্ড চালু করা"
        is VoiceCommand.Brightness ->
            if (cmd.up) "ব্রাইটনেস বাড়ানো" else "ব্রাইটনেস কমানো"
        VoiceCommand.LockScreen -> "স্ক্রিন লক করা"
        VoiceCommand.TakeScreenshot -> "স্ক্রিনশট নেওয়া"
        VoiceCommand.OpenNotifications -> "নোটিফিকেশন খোলা"
        VoiceCommand.MobileData -> "মোবাইল ডাটা সেটিংস খোলা"
        VoiceCommand.AirplaneMode -> "এয়ারপ্লেন মোড সেটিংস খোলা"
        else -> "কাজটি"
    }
}

/**
 * Saves translations as a plain text file in Downloads/VoiceCommander/
 * (MediaStore on Android 10+; the app's external files dir below
 * that, so no storage permission is ever needed).
 */
object TranslationFile {

    data class Handle(
        val uri: Uri?,
        val file: File?,
        val displayName: String,
        val location: String
    )

    fun create(context: Context): Handle? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "translation-$stamp.txt"
        // MediaStore (Android 10+) — Downloads/VoiceCommander/.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/VoiceCommander"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    val handle = Handle(
                        uri, null, name, "Downloads/VoiceCommander/$name"
                    )
                    write(
                        context, handle,
                        "ভয়েস কমান্ডার — অনুবাদ\n" +
                            SimpleDateFormat(
                                "yyyy-MM-dd HH:mm", Locale.getDefault()
                            ).format(Date()) + "\n\n"
                    )
                    return handle
                }
            } catch (_: Exception) {
                // Fall through to the files-dir fallback below.
            }
        }
        // Fallback: the app's external files dir (no permission needed).
        return try {
            val dir = File(
                context.getExternalFilesDir(null), "VoiceCommander"
            ).apply { mkdirs() }
            val file = File(dir, name)
            val handle = Handle(
                null, file, name,
                "Android/data/${context.packageName}/files/VoiceCommander/$name"
            )
            write(
                context, handle,
                "ভয়েস কমান্ডার — অনুবাদ\n" +
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", Locale.getDefault()
                    ).format(Date()) + "\n\n"
            )
            handle
        } catch (_: Exception) {
            null
        }
    }

    /** Appends one translated page ("— পাতা N —" + the text). */
    fun append(
        context: Context,
        handle: Handle,
        pageNo: Int,
        translation: String
    ): Boolean {
        val block = "— পাতা $pageNo —\n${translation.trim()}\n\n"
        return write(context, handle, block)
    }

    private fun write(context: Context, handle: Handle, text: String): Boolean {
        return try {
            if (handle.uri != null) {
                context.contentResolver.openOutputStream(handle.uri, "wa")
                    ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: return false
            } else {
                handle.file?.appendText(text, Charsets.UTF_8) ?: return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
