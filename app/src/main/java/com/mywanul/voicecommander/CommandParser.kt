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
 *  - "সাবমিট চাপো" / "ক্লিক ক"
 */
object CommandParser {

    fun parse(rawInput: String): VoiceCommand {
        val input = rawInput.trim().lowercase().replace("\\s+".toRegex(), " ")
        if (input.isEmpty()) return VoiceCommand.Unknown(rawInput)

        if (input == "থামো" || input == "থেমে যাও" || input == "stop" ||
            input == "stop listening" || input == "শোনা বন্ধ করো"
        ) {
            return VoiceCommand.StopListening
        }

        parseMessage(input)?.let { return it }
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

    // ---------- message: "রিফাতকে মেসেজ দাও, আমি আসছি" ----------

    private val messageMarkers = listOf(
        "মেসেজ পাঠিয়ে দাও", "মেসেজ পাঠাও", "মেসেজ দাও",
        "মেসেজ করো", "মেসেজ লিখো", "মেসেজ"
    )

    private fun parseMessage(input: String): VoiceCommand.Message? {
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
