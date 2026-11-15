package com.mywanul.voicecommander

/** One voice command, parsed from what the user said (Bengali and/or English). */
sealed class VoiceCommand {
    data class OpenApp(val appName: String) : VoiceCommand()
    object CloseApp : VoiceCommand()
    object GoHome : VoiceCommand()
    object GoBack : VoiceCommand()
    data class Tap(val label: String) : VoiceCommand()
    data class Scroll(val down: Boolean) : VoiceCommand()
    data class TypeText(val text: String) : VoiceCommand()
    data class Message(val name: String, val body: String) : VoiceCommand()
    data class Call(val name: String) : VoiceCommand()
    object StopListening : VoiceCommand()

    // ---- device control ----
    data class AlarmSet(val triggerAtMs: Long, val label: String) : VoiceCommand()
    object AlarmDismiss : VoiceCommand()
    data class Torch(val on: Boolean) : VoiceCommand()
    object WifiOn : VoiceCommand()
    object WifiOff : VoiceCommand()
    data class WifiConnect(val ssid: String, val password: String?) : VoiceCommand()
    data class Bluetooth(val on: Boolean) : VoiceCommand()
    object VolumeUp : VoiceCommand()
    object VolumeDown : VoiceCommand()
    object VolumeMute : VoiceCommand()
    object VolumeUnmute : VoiceCommand()
    data class Brightness(val up: Boolean) : VoiceCommand()
    object LockScreen : VoiceCommand()
    object TakeScreenshot : VoiceCommand()
    object OpenNotifications : VoiceCommand()
    object MobileData : VoiceCommand()
    object AirplaneMode : VoiceCommand()

    // ---- browsing / copy-paste / comments / Drive / read-aloud / AI ----
    data class Search(val query: String) : VoiceCommand()
    data class OpenLink(val label: String) : VoiceCommand()
    object Bookmark : VoiceCommand()
    object CopyText : VoiceCommand()
    object CopyAllText : VoiceCommand()
    object PasteText : VoiceCommand()
    object ReadClipboardText : VoiceCommand()
    data class CommentWrite(val text: String) : VoiceCommand()
    object CommentPost : VoiceCommand()
    object DriveOpen : VoiceCommand()
    data class DriveSearch(val name: String) : VoiceCommand()
    object ReadPage : VoiceCommand()
    object StopSpeech : VoiceCommand()
    object Explain : VoiceCommand()
    object DraftComment : VoiceCommand()

    // ---- continuous tasks: "করতে থাকো" / "X পৃষ্ঠা পর্যন্ত অনুবাদ করো" ----
    data class RepeatLast(val durationMs: Long? = null) : VoiceCommand()
    data class RepeatCommand(
        val inner: VoiceCommand,
        val durationMs: Long? = null
    ) : VoiceCommand()
    data class TranslatePages(val maxPages: Int?) : VoiceCommand()
    object TranslatePageOnce : VoiceCommand()
    object TaskStatus : VoiceCommand()
    object StopTask : VoiceCommand()

    data class Unknown(val raw: String) : VoiceCommand()
}

/**
 * Parses Bengali and English voice commands.
 * Examples:
 *  - "হোয়াটসঅ্যাপ খোলো" / "open whatsapp"
 *  - "রিফাতকে মেসেজ দাও, আমি আসছি"
 *  - "রিফাতকে কল করো"
 *  - "ব্যাকে যাও" / "হোমে যাও" / "নিচে যাও"
 *  - "লিখো আমি এখন বাসায় যাচ্ছি"
 *  - "সকাল ৭টায় অ্যালার্ম দাও", "টর্চ জ্বালাও", "ওয়াইফাই চালু করো"
 *  - "সার্চ করো হাদিসের বই", "লিংক খোলো আরও দেখুন", "কপি করো", "পেস্ট করো"
 *  - "কমেন্ট লিখো খুব সুন্দর", "কমেন্ট পোস্ট করো", "ড্রাইভ খোলো",
 *    "ড্রাইভে খুঁজো আল মুহান্নাদ", "এই পাতাটা পড়ে শোনাও", "এটা বুঝিয়ে দাও"
 *  - "নিচে যেতে থাকো", "১০ মিনিট ধরে করতে থাকো", "কী করছো",
 *    "১০ পৃষ্ঠা পর্যন্ত অনুবাদ করো", "এই পাতাটা অনুবাদ করো", "টাস্ক বন্ধ করো"
 */
object CommandParser {

    fun parse(rawInput: String): VoiceCommand {
        // Bengali digits (৭, ১০:৩০) become ASCII so time regexes work.
        val input = bnToEn(rawInput.trim().lowercase().replace("\\s+".toRegex(), " "))
        if (input.isEmpty()) return VoiceCommand.Unknown(rawInput)

        if (input == "থামো" || input == "থেমে যাও" || input == "stop" ||
            input == "stop listening" || input == "শোনা বন্ধ করো"
        ) {
            return VoiceCommand.StopListening
        }

        // Continuous tasks: checked before the interaction and generic
        // rules — "নিচে যেতে থাকো" would otherwise be swallowed by the
        // scroll rule, and "টাস্ক বন্ধ করো" by CloseApp.
        parseContinuous(input)?.let { return it }

        // Interaction commands (browsing, copy/paste, comments, Drive,
        // read-aloud, AI) are checked BEFORE the device and generic
        // rules: "কমেন্ট পোস্ট করো" and "ড্রাইভ খোলো" would otherwise be
        // swallowed by the generic handlers below, and "চুপ করো" must
        // stop speech instead of muting (see parseVolume).
        parseInteraction(input)?.let { return it }

        // Device-control commands are checked BEFORE the generic open /
        // close rules: "ওয়াইফাই চালু করো" ends in "চালু করো" and
        // "সাউন্ড বন্ধ করো" ends in "বন্ধ করো" — both would otherwise be
        // swallowed by OpenApp / CloseApp.
        parseAlarm(input)?.let { return it }
        parseTorch(input)?.let { return it }
        parseWifi(input)?.let { return it }
        parseBluetooth(input)?.let { return it }
        parseVolume(input)?.let { return it }
        parseBrightness(input)?.let { return it }
        parseScreen(input)?.let { return it }
        parseConnectivity(input)?.let { return it }

        parseMessage(input, rawInput)?.let { return it }
        parseCall(input)?.let { return it }
        parseOpen(input)?.let { return it }

        // Close the current app
        if (input == "বন্ধ করো" || input == "বন্ধ কর" || input == "close" ||
            input == "close app" || input.contains("অ্যাপ বন্ধ") ||
            input.endsWith("বন্ধ করো")
        ) {
            return VoiceCommand.CloseApp
        }

        // Home / back
        if (input == "হোমে যাও" || input == "হোম" || input == "go home" ||
            input == "home" || input.contains("হোমে যাও")
        ) {
            return VoiceCommand.GoHome
        }
        if (input == "ব্যাকে যাও" || input == "পেছনে যাও" || input == "ব্যাক" ||
            input == "back" || input == "go back" || input.contains("ব্যাকে যাও")
        ) {
            return VoiceCommand.GoBack
        }

        // Scroll
        if (input.contains("নিচে") || input == "scroll down") {
            return VoiceCommand.Scroll(down = true)
        }
        if (input.contains("উপরে") || input == "scroll up") {
            return VoiceCommand.Scroll(down = false)
        }

        // Send button inside a chat: "সেন্ড করো" / "পাঠিয়ে দাও" / "send"
        if (input == "সেন্ড করো" || input == "পাঠিয়ে দাও" || input == "send") {
            return VoiceCommand.Tap("Send")
        }

        parseTap(input)?.let { return it }
        parseType(input)?.let { return it }

        return VoiceCommand.Unknown(rawInput)
    }

    // ---------- interaction: browsing / copy-paste / comments / Drive / AI ----------

    private fun parseInteraction(input: String): VoiceCommand? {
        parseBrowse(input)?.let { return it }
        parseCopyPaste(input)?.let { return it }
        parseComment(input)?.let { return it }
        parseDrive(input)?.let { return it }
        parseReadAloud(input)?.let { return it }
        parseAi(input)?.let { return it }
        parseStopSpeech(input)?.let { return it }
        return null
    }

    // Browsing: "সার্চ করো <কোয়েরি>" / "লিংক খোলো <টেক্সট>" / "বুকমার্ক করো".
    private fun parseBrowse(input: String): VoiceCommand? {
        for (prefix in listOf(
            "সার্চ করো ", "সার্চ কর ", "খুঁজো ", "খোঁজো ",
            "search ", "search for "
        )) {
            if (input.startsWith(prefix)) {
                val query = input.removePrefix(prefix).trim()
                if (query.isNotEmpty()) return VoiceCommand.Search(query)
            }
        }
        for (prefix in listOf(
            "লিংক খোলো ", "লিংকটি খোলো ", "open link ", "open the link "
        )) {
            if (input.startsWith(prefix)) {
                val label = input.removePrefix(prefix).trim()
                if (label.isNotEmpty()) return VoiceCommand.OpenLink(label)
            }
        }
        if (input.contains("বুকমার্ক") || input.contains("bookmark")) {
            return VoiceCommand.Bookmark
        }
        return null
    }

    // Copy / paste: "কপি করো" / "সব কপি করো" / "পেস্ট করো" /
    // "কপি করা লেখা পড়ো". Copy-all is checked before plain copy.
    private fun parseCopyPaste(input: String): VoiceCommand? {
        if (input.contains("কপি করা লেখা পড়ো") ||
            input.contains("কপি করা লেখা পড়ে শোনাও") ||
            input == "read clipboard" || input.contains("read clipboard")
        ) {
            return VoiceCommand.ReadClipboardText
        }
        if (input.contains("সব কপি করো") || input == "copy all" ||
            input.contains("copy all")
        ) {
            return VoiceCommand.CopyAllText
        }
        if (input == "কপি করো" || input == "কপি" || input == "copy" ||
            input.contains("কপি করো")
        ) {
            return VoiceCommand.CopyText
        }
        if (input == "পেস্ট করো" || input == "পেস্ট" || input == "paste" ||
            input.contains("পেস্ট করো")
        ) {
            return VoiceCommand.PasteText
        }
        return null
    }

    // Comments: "কমেন্ট লিখো <টেক্সট>" / "কমেন্ট পোস্ট করো" / "পোস্ট করো".
    private fun parseComment(input: String): VoiceCommand? {
        if (input == "কমেন্ট পোস্ট করো" || input == "পোস্ট করো" ||
            input == "পোস্ট করুন" || input == "post comment" ||
            input.contains("কমেন্ট পোস্ট করো")
        ) {
            return VoiceCommand.CommentPost
        }
        for (prefix in listOf(
            "কমেন্ট লিখো ", "কমেন্ট লেখো ", "কমেন্ট করো ",
            "মন্তব্য লিখো ", "মন্তব্য লেখো ", "write a comment ", "comment "
        )) {
            if (input.startsWith(prefix)) {
                val text = input.removePrefix(prefix).trim()
                if (text.isNotEmpty()) return VoiceCommand.CommentWrite(text)
            }
        }
        return null
    }

    // Drive: "ড্রাইভ খোলো" / "ড্রাইভে খুঁজো <নাম>" / "ড্রাইভ থেকে খোলো <নাম>".
    // Must run before parseOpen — "ড্রাইভ খোলো" ends in "খোলো".
    private fun parseDrive(input: String): VoiceCommand? {
        if (input == "ড্রাইভ খোলো" || input == "ড্রাইভ" ||
            input == "open drive" || input.contains("ড্রাইভ খোলো") ||
            input.contains("গুগল ড্রাইভ খোলো")
        ) {
            return VoiceCommand.DriveOpen
        }
        for (prefix in listOf(
            "ড্রাইভে খুঁজো ", "ড্রাইভে খোঁজো ", "ড্রাইভ থেকে খোলো ",
            "drive search ", "search drive for ", "search in drive "
        )) {
            if (input.startsWith(prefix)) {
                val name = input.removePrefix(prefix).trim()
                if (name.isNotEmpty()) return VoiceCommand.DriveSearch(name)
            }
        }
        return null
    }

    // Read aloud: "এই পাতাটা পড়ে শোনাও" / "read this page".
    // Checked after copy-paste so "কপি করা লেখা পড়ে শোনাও" keeps its
    // clipboard meaning.
    private fun parseReadAloud(input: String): VoiceCommand? {
        if (input.contains("পড়ে শোনাও") || input.contains("পড়ে শোনান") ||
            input.contains("এই পাতাটা পড়ো") || input.contains("পাতাটা পড়ো") ||
            input == "read this page" || input.contains("read this page")
        ) {
            return VoiceCommand.ReadPage
        }
        return null
    }

    // AI (needs the free Gemini key): "এটা বুঝিয়ে দাও" / "কমেন্ট লিখে দাও".
    private fun parseAi(input: String): VoiceCommand? {
        if (input.contains("বুঝিয়ে দাও") || input.contains("বুঝিয়ে বলো") ||
            input.contains("বুঝিয়ে দিন") || input == "explain this" ||
            input.contains("explain this")
        ) {
            return VoiceCommand.Explain
        }
        if (input.contains("কমেন্ট লিখে দাও") ||
            input.contains("কমেন্ট লিখে দিন") ||
            input == "write a comment for me" ||
            input.contains("write a comment for me")
        ) {
            return VoiceCommand.DraftComment
        }
        return null
    }

    // Stop reading: "চুপ করো" / "পড়া থামাও". This is checked before
    // parseVolume, so "চুপ করো" stops speech instead of muting
    // ("সাউন্ড বন্ধ করো" still mutes).
    private fun parseStopSpeech(input: String): VoiceCommand? {
        if (input == "চুপ করো" || input.contains("পড়া থামাও") ||
            input.contains("পড়া বন্ধ করো") || input.contains("আর পড়ো না") ||
            input == "stop reading" || input.contains("stop speaking")
        ) {
            return VoiceCommand.StopSpeech
        }
        return null
    }

    // ---------- continuous tasks: "করতে থাকো" / "X পৃষ্ঠা পর্যন্ত অনুবাদ করো" ----------

    private fun parseContinuous(input: String): VoiceCommand? {
        // Stop a running task: "টাস্ক বন্ধ করো" / "কাজ বন্ধ করো" /
        // "কাজ থামাও".
        if (input.contains("টাস্ক বন্ধ") || input.contains("কাজ বন্ধ") ||
            input.contains("টাস্ক থামাও") || input.contains("কাজ থামাও") ||
            input.contains("কাজটি বন্ধ") || input == "stop task" ||
            input.contains("stop the task")
        ) {
            return VoiceCommand.StopTask
        }

        // Status: "কী করছো" / "কাজের অবস্থা".
        if (input.contains("কাজের অবস্থা") || input.contains("কী করছো") ||
            input.contains("কী করছ") || input.contains("কি করছ") ||
            input == "what are you doing" ||
            input.contains("what are you doing")
        ) {
            return VoiceCommand.TaskStatus
        }

        // Translation — checked before the generic repeat below, because
        // "অনুবাদ করতে থাকো" also contains "করতে থাকো".
        parseTranslate(input)?.let { return it }

        // Repeat: "... করতে থাকো" / "... যেতে থাকো" / "করতে থাকো".
        if (input.contains("করতে থাকো") || input.contains("করতে থাক") ||
            input.contains("যেতে থাকো") || input == "keep doing it" ||
            input.contains("keep doing it") || input.contains("keep doing this") ||
            input.contains("keep scrolling")
        ) {
            val durationMs = extractDurationMs(input)
            var base = removeDuration(input)
            base = base
                .removeSuffix("করতে থাকো").removeSuffix("করতে থাক")
                .removeSuffix("যেতে থাকো")
                .removeSuffix("keep doing it").removeSuffix("keep doing this")
                .trim()
            if (base.isEmpty() || base == "এটা" || base == "এই কাজটা" ||
                base == "এই কাজ" || base == "এইটা" || base == "সেটা"
            ) {
                return VoiceCommand.RepeatLast(durationMs)
            }
            val inner = parseInnerForRepeat(base) ?: return null
            return VoiceCommand.RepeatCommand(inner, durationMs)
        }
        return null
    }

    // Translation: a loop of pages, or one page now. The loop patterns
    // are checked first — "এই পাতাটা অনুবাদ করতে থাকো" must not fall
    // into the one-shot branch.
    private fun parseTranslate(input: String): VoiceCommand? {
        // Pages: "X পৃষ্ঠা পর্যন্ত অনুবাদ করো" /
        // "এই লেখাটা ১০ পৃষ্ঠা পর্যন্ত অনুবাদ করো" /
        // "translate this text up to 10 pages".
        val pageMatch = Regex("(\\d+)\\s*(পৃষ্ঠা|পাতা|পেজ|page|pages)")
            .find(input)
        if (pageMatch != null && (input.contains("পর্যন্ত") ||
                input.contains("up to") || input.contains("অনুবাদ করো") ||
                input.contains("অনুবাদ করতে"))
        ) {
            val n = pageMatch.groupValues[1].toIntOrNull()
            if (n != null && n > 0) {
                return VoiceCommand.TranslatePages(minOf(n, 50))
            }
        }
        // Loop without a page count: "অনুবাদ করতে থাকো" /
        // "এই লেখাটা অনুবাদ করতে থাকো" / "keep translating".
        if (input.contains("অনুবাদ করতে থাকো") ||
            input.contains("অনুবাদ করতে থাক") ||
            input.contains("অনুবাদ করতেই থাকো") ||
            input.contains("keep translating")
        ) {
            return VoiceCommand.TranslatePages(null)
        }
        // One page: "এই পাতাটা অনুবাদ করো" / "এই লেখাটা অনুবাদ করো" /
        // "translate this page".
        if (input.contains("এই পাতাটা অনুবাদ") ||
            input.contains("এই পেজটা অনুবাদ") ||
            input.contains("এই পৃষ্ঠাটা অনুবাদ") ||
            input.contains("এই লেখাটা অনুবাদ") ||
            input == "translate this page" ||
            input.contains("translate this page")
        ) {
            return VoiceCommand.TranslatePageOnce
        }
        return null
    }

    /**
     * Maps the inner phrase of "<কাজ> করতে থাকো" to a command, using
     * the normal parser (so "নিচে যেতে থাকো" becomes a scroll). The
     * continuous types themselves are not repeatable.
     */
    private fun parseInnerForRepeat(base: String): VoiceCommand? {
        if (base == "স্ক্রল" || base == "scroll" ||
            base == "নিচে স্ক্রল" || base == "scroll down"
        ) {
            return VoiceCommand.Scroll(down = true)
        }
        if (base == "উপরে স্ক্রল" || base == "scroll up") {
            return VoiceCommand.Scroll(down = false)
        }
        return when (val parsed = parse(base)) {
            is VoiceCommand.Unknown -> null
            is VoiceCommand.RepeatLast,
            is VoiceCommand.RepeatCommand,
            is VoiceCommand.TranslatePages,
            VoiceCommand.TranslatePageOnce,
            VoiceCommand.TaskStatus,
            VoiceCommand.StopTask,
            VoiceCommand.StopListening,
            VoiceCommand.StopSpeech -> null
            else -> parsed
        }
    }

    /**
     * "১০ মিনিট ধরে" / "২ ঘণ্টা ধরে" / "for 30 minutes" -> milliseconds.
     * Capped at 120 minutes per run.
     */
    private fun extractDurationMs(input: String): Long? {
        Regex("(\\d+)\\s*(মিনিট|minute|minutes|min)").find(input)?.let {
            val mins = it.groupValues[1].toLongOrNull() ?: return@let
            return minOf(mins, 120L) * 60_000L
        }
        Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hour|hours|hr)").find(input)?.let {
            val hours = it.groupValues[1].toLongOrNull() ?: return@let
            return minOf(hours * 60L, 120L) * 60_000L
        }
        return null
    }

    /** Removes the duration segment ("১০ মিনিট ধরে" / "for 10 minutes"). */
    private fun removeDuration(input: String): String {
        var s = input
        s = Regex("for\\s+\\d+\\s*(minute|minutes|hour|hours|hr)")
            .replace(s, " ")
        s = Regex("\\d+\\s*(মিনিট|minute|minutes|min)\\s*(ধরে|for)?")
            .replace(s, " ")
        s = Regex("\\d+\\s*(ঘণ্টা|ঘন্টা|hour|hours|hr)\\s*(ধরে|for)?")
            .replace(s, " ")
        return s.replace("\\s+".toRegex(), " ").trim()
    }

    // ---------- device control ----------

    // Alarm: "সকাল ৭টায় অ্যালার্ম দাও", "অ্যালার্ম দাও ১০ মিনিট পর",
    // "অ্যালার্ম বন্ধ করো", "set alarm at 7 am".
    private fun parseAlarm(input: String): VoiceCommand? {
        if (!input.contains("অ্যালার্ম") && !input.contains("alarm")) return null
        if (input.contains("বন্ধ") || input.contains("বাতিল") ||
            input.contains("alarm off") || input.contains("stop alarm") ||
            input.contains("dismiss alarm")
        ) {
            return VoiceCommand.AlarmDismiss
        }
        val parsed = parseAlarmTime(input) ?: return null
        return VoiceCommand.AlarmSet(parsed.first, parsed.second)
    }

    /** Returns (trigger time in ms, Bengali label) or null if no time is found. */
    private fun parseAlarmTime(input: String): Pair<Long, String>? {
        val now = System.currentTimeMillis()

        // Relative: "১০ মিনিট পর" / "১ ঘণ্টা পর" / "আধা ঘণ্টা পর" / "in 10 minutes".
        if (input.contains("আধা ঘণ্টা পর") || input.contains("আধ ঘণ্টা পর") ||
            input.contains("half an hour")
        ) {
            return Pair(now + 30L * 60_000L, "৩০ মিনিট পর")
        }
        Regex("(\\d+)\\s*(মিনিট|minute|minutes|min)\\s*(পর|পরে|later|from now)")
            .find(input)?.let {
                val mins = it.groupValues[1].toIntOrNull() ?: return@let
                return Pair(now + mins * 60_000L, "$mins মিনিট পর")
            }
        Regex("in\\s+(\\d+)\\s*(minute|minutes)")
            .find(input)?.let {
                val mins = it.groupValues[1].toIntOrNull() ?: return@let
                return Pair(now + mins * 60_000L, "$mins মিনিট পর")
            }
        Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hour|hours|hr)\\s*(পর|পরে|later|from now)")
            .find(input)?.let {
                val hours = it.groupValues[1].toIntOrNull() ?: return@let
                return Pair(now + hours * 3_600_000L, "$hours ঘণ্টা পর")
            }

        // Absolute: "সকাল ৭টা", "রাত ১০:৩০", "৭টা ৩০ মিনিট", "7 am".
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?").find(input) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        var minute = m.groupValues[2].toIntOrNull()
        if (minute == null) {
            // "৭টা ৩০ মিনিট" — minutes written after the hour.
            val rest = input.substring(minOf(m.range.last + 1, input.length))
            minute = Regex("(\\d+)\\s*মিনিট").find(rest)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        if (hour > 23 || minute > 59) return null

        val hasAm = input.contains(" am") || input.endsWith("am") ||
            input.contains("এএম")
        val hasPm = input.contains(" pm") || input.endsWith("pm") ||
            input.contains("পিএম")
        hour = when {
            hasAm -> hour % 12
            hasPm -> hour % 12 + 12
            input.contains("দুপুর") ->
                if (hour == 12) 12 else if (hour < 12) hour + 12 else hour
            input.contains("বিকেল") || input.contains("সন্ধ্যা") ->
                if (hour < 12) hour + 12 else hour
            input.contains("রাত") -> when {
                hour == 12 -> 0
                hour < 5 -> hour
                hour < 12 -> hour + 12
                else -> hour
            }
            input.contains("সকাল") || input.contains("ভোর") -> hour % 12
            else -> hour
        }
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val period = when {
            input.contains("সকাল") -> "সকাল "
            input.contains("ভোর") -> "ভোর "
            input.contains("দুপুর") -> "দুপুর "
            input.contains("বিকেল") -> "বিকেল "
            input.contains("সন্ধ্যা") -> "সন্ধ্যা "
            input.contains("রাত") -> "রাত "
            else -> ""
        }
        val label = "$period${"%02d".format(hour)}:${"%02d".format(minute)} অ্যালার্ম"
        return Pair(cal.timeInMillis, label)
    }

    // Torch: "টর্চ জ্বালাও" / "টর্চ নেভাও" / "টর্চ অন করো" / "torch off".
    private fun parseTorch(input: String): VoiceCommand? {
        if (!input.contains("টর্চ") && !input.contains("torch") &&
            !input.contains("ফ্ল্যাশলাইট") && !input.contains("flashlight")
        ) {
            return null
        }
        if (input.contains("নেভাও") || input.contains("নিভাও") ||
            input.contains("বন্ধ") || input.contains("অফ") ||
            input.contains("off")
        ) {
            return VoiceCommand.Torch(false)
        }
        if (input.contains("জ্বালাও") || input.contains("জ্বালান") ||
            input.contains("চালু") || input.contains(" অন") ||
            input.endsWith("on") || input.contains("on করো")
        ) {
            return VoiceCommand.Torch(true)
        }
        return null
    }

    // WiFi: "ওয়াইফাই চালু করো" / "ওয়াইফাই বন্ধ করো" /
    // "ওয়াইফাই কানেক্ট করো আমার_নেটওয়ার্ক পাসওয়ার্ড 1234".
    private fun parseWifi(input: String): VoiceCommand? {
        if (!input.contains("ওয়াইফাই") && !input.contains("wifi") &&
            !input.contains("ওয়াই-ফাই")
        ) {
            return null
        }
        if (input.contains("কানেক্ট") || input.contains("connect")) {
            var rest = input.substringAfter("কানেক্ট করো", "")
            if (rest.isEmpty()) rest = input.substringAfter("connect to wifi", "")
            if (rest.isEmpty()) rest = input.substringAfter("connect", "")
            rest = rest.trim().removePrefix("ওয়াইফাই").removePrefix("wifi").trim()
            if (rest.isEmpty()) return null
            var ssid = rest
            var password: String? = null
            val pwIdx = rest.indexOf("পাসওয়ার্ড")
            val enIdx = rest.indexOf(" password ")
            when {
                pwIdx >= 0 -> {
                    ssid = rest.substring(0, pwIdx).trim()
                    password = rest.substring(pwIdx + "পাসওয়ার্ড".length)
                        .trim().trimStart(':', ' ').ifEmpty { null }
                }
                enIdx >= 0 -> {
                    ssid = rest.substring(0, enIdx).trim()
                    password = rest.substring(enIdx + " password ".length)
                        .trim().ifEmpty { null }
                }
            }
            if (ssid.isEmpty()) return null
            return VoiceCommand.WifiConnect(ssid, password)
        }
        if (input.contains("বন্ধ") || input.contains("off")) {
            return VoiceCommand.WifiOff
        }
        if (input.contains("চালু") || input.contains("অন") ||
            input.contains("on")
        ) {
            return VoiceCommand.WifiOn
        }
        return null
    }

    // Bluetooth: "ব্লুটুথ চালু করো" / "ব্লুটুথ বন্ধ করো".
    private fun parseBluetooth(input: String): VoiceCommand? {
        if (!input.contains("ব্লুটুথ") && !input.contains("bluetooth")) return null
        if (input.contains("বন্ধ") || input.contains("off")) {
            return VoiceCommand.Bluetooth(false)
        }
        if (input.contains("চালু") || input.contains("অন") ||
            input.contains("on")
        ) {
            return VoiceCommand.Bluetooth(true)
        }
        return null
    }

    // Volume: "ভলিউম বাড়াও" / "ভলিউম কমাও" / "সাউন্ড বন্ধ করো" / "সাউন্ড চালু করো".
    private fun parseVolume(input: String): VoiceCommand? {
        if (input.contains("ভলিউম বাড়াও") || input.contains("ভলিউম বাড়ান") ||
            input.contains("volume up") || input.contains("ভলিউম আপ")
        ) {
            return VoiceCommand.VolumeUp
        }
        if (input.contains("ভলিউম কমাও") || input.contains("ভলিউম কমান") ||
            input.contains("volume down") || input.contains("ভলিউম ডাউন")
        ) {
            return VoiceCommand.VolumeDown
        }
        if (input.contains("সাউন্ড বন্ধ") || input.contains("মিউট") ||
            input.contains("mute")
        ) {
            return VoiceCommand.VolumeMute
        }
        if (input.contains("সাউন্ড চালু") || input.contains("আনমিউট") ||
            input.contains("unmute")
        ) {
            return VoiceCommand.VolumeUnmute
        }
        return null
    }

    // Brightness: "ব্রাইটনেস বাড়াও" / "ব্রাইটনেস কমাও" / "আলো কমাও".
    private fun parseBrightness(input: String): VoiceCommand? {
        if (!input.contains("ব্রাইটনেস") && !input.contains("brightness") &&
            !input.contains("আলো")
        ) {
            return null
        }
        if (input.contains("বাড়াও") || input.contains("বাড়ান") ||
            input.contains("বেশি") || input.contains("brightness up")
        ) {
            return VoiceCommand.Brightness(true)
        }
        if (input.contains("কমাও") || input.contains("কমান") ||
            input.contains("কম") || input.contains("brightness down")
        ) {
            return VoiceCommand.Brightness(false)
        }
        return null
    }

    // Screen: "স্ক্রিন বন্ধ করো" (lock) / "স্ক্রিনশট নাও" / "নোটিফিকেশন খোলো".
    private fun parseScreen(input: String): VoiceCommand? {
        if (input.contains("স্ক্রিনশট") || input.contains("screenshot")) {
            return VoiceCommand.TakeScreenshot
        }
        if (input.contains("স্ক্রিন বন্ধ") || input.contains("লক করো") ||
            input == "লক" || input.contains("lock screen") ||
            input.contains("স্ক্রিন লক")
        ) {
            return VoiceCommand.LockScreen
        }
        if (input.contains("নোটিফিকেশন") &&
            (input.contains("খোলো") || input.contains("খুলো") ||
                input.contains("দেখাও"))
        ) {
            return VoiceCommand.OpenNotifications
        }
        return null
    }

    // Mobile data / airplane mode: not toggleable by a normal app, so the
    // executor opens the settings page instead.
    private fun parseConnectivity(input: String): VoiceCommand? {
        if (input.contains("মোবাইল ডাটা") || input.contains("mobile data")) {
            return VoiceCommand.MobileData
        }
        if (input.contains("এয়ারপ্লেন") || input.contains("airplane") ||
            input.contains("ফ্লাইট মোড")
        ) {
            return VoiceCommand.AirplaneMode
        }
        return null
    }

    // ---------- message: "রিফাতকে মেসেজ দাও, আমি আসছি" ----------

    private val messageMarkers = listOf(
        "মেসেজ পাঠিয়ে দাও", "মেসেজ পাঠাও", "মেসেজ দাও",
        "মেসেজ করো", "মেসেজ লিখো", "মেসেজ"
    )

    private fun parseMessage(input: String, rawInput: String): VoiceCommand? {
        for (marker in messageMarkers) {
            val idx = input.indexOf(marker)
            // Name must come before the marker, e.g. "রিফাতকে মেসেজ দাও ..."
            if (idx > 0) {
                var before = input.substring(0, idx).trim().trimEnd(',', ':', '।')
                before = stripNameSuffix(before)
                var after = input.substring(idx + marker.length)
                    .trim().trimStart(':', ',', '।', ' ')
                // "মেসেজ খোলো" is an open-app command, not a message.
                if (after == "খোলো" || after == "খোল" || after == "চালু করো" ||
                    after == "ওপেন করো" || AppResolver.isKnownAppName(before)
                ) {
                    continue
                }
                // A name with no message text ("রিফাতকে মেসেজ দাও") is not
                // a message — and must not fall through to a shorter marker
                // that would swallow "দাও" as the message body.
                if (before.isNotEmpty() && after.isEmpty()) {
                    return VoiceCommand.Unknown(rawInput)
                }
                if (before.isNotEmpty() && after.isNotEmpty()) {
                    return VoiceCommand.Message(before, after.trimEnd('।'))
                }
            }
        }
        // English: "message rifat i am coming"
        if (input.startsWith("message ") || input.startsWith("text ")) {
            val rest = input.substringAfter(' ').trim()
            val parts = rest.split(" ", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return VoiceCommand.Message(parts[0], parts[1])
            }
        }
        return null
    }

    // ---------- call: "রিফাতকে কল করো" / "call rifat" ----------

    private val callMarkers = listOf("কল করো", "ফোন করো", "কল দাও")

    private fun parseCall(input: String): VoiceCommand.Call? {
        for (marker in callMarkers) {
            val idx = input.indexOf(marker)
            if (idx >= 0) {
                val rest = (input.substring(0, idx) + " " + input.substring(idx + marker.length))
                    .trim().trimEnd(',', '।')
                val name = stripNameSuffix(rest)
                if (name.isNotEmpty()) return VoiceCommand.Call(name)
            }
        }
        if (input.startsWith("call ")) {
            val name = input.removePrefix("call ").trim()
            if (name.isNotEmpty()) return VoiceCommand.Call(name)
        }
        return null
    }

    // ---------- open app ----------

    private val openSuffixes = listOf(
        "চালু করো", "ওপেন করো", "খোলো", "খুলো", "খোল", "ওপেন"
    )

    private fun parseOpen(input: String): VoiceCommand.OpenApp? {
        for (suffix in openSuffixes) {
            if (input.endsWith(suffix)) {
                val name = input.removeSuffix(suffix).trim().trimEnd(',', '।')
                if (name.isNotEmpty()) return VoiceCommand.OpenApp(name)
            }
        }
        if (input.startsWith("open ")) {
            val name = input.removePrefix("open ").trim()
            if (name.isNotEmpty()) return VoiceCommand.OpenApp(name)
        }
        return null
    }

    // ---------- tap: "সাবমিট চাপো" / "ক্লিক ক" / "tap ok" ----------

    private fun parseTap(input: String): VoiceCommand.Tap? {
        val prefixes = listOf("ক্লিক করো ", "ক্লিক ", "tap ", "চাপো ", "টিপো ")
        for (prefix in prefixes) {
            if (input.startsWith(prefix)) {
                val label = input.removePrefix(prefix).trim()
                if (label.isNotEmpty()) return VoiceCommand.Tap(label)
            }
        }
        val suffixes = listOf(" চাপো", " টিপো", " ক্লিক করো", " ক্লিক")
        for (suffix in suffixes) {
            if (input.endsWith(suffix)) {
                val label = input.removeSuffix(suffix).trim()
                if (label.isNotEmpty()) return VoiceCommand.Tap(label)
            }
        }
        return null
    }

    // ---------- type: "লিখো ..." / "type ..." ----------

    private fun parseType(input: String): VoiceCommand.TypeText? {
        val prefixes = listOf("লিখো ", "লেখো ", "টাইপ করো ", "type ")
        for (prefix in prefixes) {
            if (input.startsWith(prefix)) {
                val text = input.removePrefix(prefix).trim()
                if (text.isNotEmpty()) return VoiceCommand.TypeText(text)
            }
        }
        return null
    }

    /** Bengali digits (০-৯) to ASCII (0-9) so number regexes work. */
    private fun bnToEn(s: String): String {
        val map = mapOf(
            '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
            '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
        )
        return s.map { map[it] ?: it }.joinToString("")
    }

    /** "রিফাতকে" -> "রিফাত", "রিফাতরে" -> "রিফাত", "রিফাত ভাইকে" -> "রিফাত ভাই".
     *  Note: "কে"/"রে" are two code points each (ক/র + ে), so removeSuffix
     *  (string-based) is used — dropLast(1) would leave a stray ক/র behind. */
    private fun stripNameSuffix(name: String): String {
        var n = name.trim()
        if (n.endsWith("কে")) n = n.removeSuffix("কে")
        if (n.endsWith("রে")) n = n.removeSuffix("রে")
        return n.trim()
    }
}
