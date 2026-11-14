package com.mywanul.voicecommander

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini AI (free tier) over plain HTTP — no new libraries, only
 * HttpURLConnection + org.json, both in the Android SDK.
 *
 * The user creates a FREE API key at Google AI Studio
 * (aistudio.google.com) and saves it in the app's settings. No key
 * is bundled in the app, no money is involved. Used for:
 *  - "এটা বুঝিয়ে দাও" — explain clipboard/screen text in simple Bengali
 *  - "কমেন্ট লিখে দাও" — draft a comment about the visible post
 *  - translate — translate screen text to simple Bengali, page by page
 *  - smart-command fallback — map an unknown spoken phrase to a known
 *    command (only when a key is saved)
 */
object AIHelper {

    private const val PREFS = "voice_commander_ai"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val TIMEOUT_MS = 20_000

    const val NO_KEY =
        "এআই কী দেওয়া নেই — অ্যাপের সেটিংসে ফ্রি কী বসান (aistudio.google.com থেকে)"

    data class AiResult(val ok: Boolean, val text: String)

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)?.trim()?.ifEmpty { null }

    fun hasApiKey(context: Context): Boolean = getApiKey(context) != null

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, key.trim()).apply()
    }

    /** One Gemini call. Returns AiResult(false, Bengali error) on failure. */
    fun ask(context: Context, prompt: String): AiResult {
        val key = getApiKey(context) ?: return AiResult(false, NO_KEY)
        return try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                doOutput = true
            }
            val body = JSONObject().apply {
                put(
                    "contents", JSONArray().put(
                        JSONObject().put(
                            "parts", JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                    )
                )
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                return AiResult(
                    false,
                    "এআই উত্তর দিতে পারেনি (কোড $code): ${extractError(raw)}"
                )
            }
            val text = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim()
            AiResult(true, text)
        } catch (e: Exception) {
            AiResult(false, "এআই-এর সাথে যোগাযোগ হয়নি: ${e.message}")
        }
    }

    private fun extractError(raw: String): String {
        return try {
            JSONObject(raw).getJSONObject("error").optString("message", raw)
                .take(200)
        } catch (_: Exception) {
            raw.take(200)
        }
    }

    /** "এটা বুঝিয়ে দাও": explain [text] in simple colloquial Bengali. */
    fun explain(context: Context, text: String): AiResult {
        val prompt = "তুমি একজন সহজ বাংলার শিক্ষক। নিচের লেখাটি সহজ চলিত " +
            "বাংলায়, ছোট ছোট বাক্যে বুঝিয়ে দাও। কঠিন শব্দ ব্যবহার করো না।\n\n" +
            "লেখা:\n${text.take(4000)}"
        return ask(context, prompt)
    }

    /** "কমেন্ট লিখে দাও": draft a short, natural Bengali comment. */
    fun draftComment(context: Context, postText: String): AiResult {
        val prompt = "নিচের পোস্টটি পড়ে তার উপর একটি ছোট, স্বাভাবিক বাংলায় " +
            "কমেন্ট লেখো (২-৩ লাইন)। কমেন্টটি যেন একজন সাধারণ পাঠকের মতো " +
            "শোনায়, অতিরঞ্জন নয়। শুধু কমেন্টটিই দাও, আর কিছু নয়।\n\n" +
            "পোস্ট:\n${postText.take(3000)}"
        return ask(context, prompt)
    }

    /** "এই লেখাটা অনুবাদ করতে থাকো": translate [text] to simple colloquial Bengali. */
    fun translate(context: Context, text: String): AiResult {
        val prompt = "নিচের লেখাটি সহজ চলিত বাংলায় অনুবাদ করো। অর্থ হুবহু " +
            "রাখো, কঠিন শব্দ ব্যবহার করো না, আর অতিরিক্ত ব্যাখ্যা দিও না — " +
            "শুধু অনুবাদটুকু দাও।\n\n" +
            "লেখা:\n${text.take(6000)}"
        return ask(context, prompt)
    }

    /**
     * Smart-command fallback: asks Gemini to map free speech to one of
     * the known commands. Returns the mapped VoiceCommand, or null when
     * it cannot be mapped (or there is no key / the call fails).
     */
    fun smartMap(context: Context, spoken: String): VoiceCommand? {
        val prompt = "তুমি একটি ভয়েস কমান্ডার অ্যাপের কমান্ড বোঝার সহকারী। " +
            "ব্যবহারকারী যা বলেছে তা থেকে নিচের তালিকার একটি কমান্ড বেছে নাও। " +
            "শুধু JSON দাও, আর কিছু নয়:\n" +
            "{\"command\": \"<type>\", \"arg\": \"<arg>\"}\n" +
            "তালিকা (type — অর্থ):\n" +
            "open_app — অ্যাপ খোলা (arg: অ্যাপের নাম)\n" +
            "close_app — অ্যাপ বন্ধ করা\n" +
            "go_home — হোমে যাওয়া\n" +
            "go_back — পেছনে যাওয়া\n" +
            "scroll_down — নিচে যাওয়া\n" +
            "scroll_up — উপরে যাওয়া\n" +
            "tap — বোতামে ক্লিক (arg: বোতামের লেখা)\n" +
            "type — লেখা টাইপ করা (arg: লেখা)\n" +
            "search — সার্চ করা (arg: সার্চের কথা)\n" +
            "open_link — লিংক খোলা (arg: লিংকের লেখা)\n" +
            "copy — কপি করা\n" +
            "copy_all — সব কপি করা\n" +
            "paste — পেস্ট করা\n" +
            "read_clipboard — কপি করা লেখা পড়ে শোনানো\n" +
            "comment_write — কমেন্ট লেখা (arg: কমেন্টের লেখা)\n" +
            "comment_post — কমেন্ট পোস্ট করা\n" +
            "drive_open — ড্রাইভ খোলা\n" +
            "drive_search — ড্রাইভে খোঁজা (arg: ফাইলের নাম)\n" +
            "read_page — পাতা পড়ে শোনানো\n" +
            "torch_on / torch_off — টর্চ জ্বালানো / নেভানো\n" +
            "wifi_on / wifi_off — ওয়াইফাই চালু / বন্ধ\n" +
            "bluetooth_on / bluetooth_off — ব্লুটুথ চালু / বন্ধ\n" +
            "volume_up / volume_down / volume_mute / volume_unmute\n" +
            "brightness_up / brightness_down\n" +
            "lock_screen — স্ক্রিন লক করা\n" +
            "screenshot — স্ক্রিনশট নেওয়া\n" +
            "open_notifications — নোটিফিকেশন খোলা\n" +
            "mobile_data / airplane_mode — সেটিংস খোলা\n" +
            "message — মেসেজ পাঠানো (arg: \"নাম|মেসেজ\")\n" +
            "call — কল করা (arg: নাম)\n" +
            "কোনো কমান্ড না মিললে {\"command\": \"unknown\"} দাও।\n" +
            "ব্যবহারকারী বলেছে: \"$spoken\""
        val res = ask(context, prompt)
        if (!res.ok) return null
        return try {
            val jsonText = res.text
                .replace("```json", "").replace("```", "").trim()
            val start = jsonText.indexOf('{')
            val end = jsonText.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(jsonText.substring(start, end + 1))
            val type = obj.optString("command", "").trim()
            val arg = obj.optString("arg", "").trim()
            mapToCommand(type, arg)
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToCommand(type: String, arg: String): VoiceCommand? {
        return when (type) {
            "open_app" -> if (arg.isNotEmpty()) VoiceCommand.OpenApp(arg) else null
            "close_app" -> VoiceCommand.CloseApp
            "go_home" -> VoiceCommand.GoHome
            "go_back" -> VoiceCommand.GoBack
            "scroll_down" -> VoiceCommand.Scroll(down = true)
            "scroll_up" -> VoiceCommand.Scroll(down = false)
            "tap" -> if (arg.isNotEmpty()) VoiceCommand.Tap(arg) else null
            "type" -> if (arg.isNotEmpty()) VoiceCommand.TypeText(arg) else null
            "search" -> if (arg.isNotEmpty()) VoiceCommand.Search(arg) else null
            "open_link" -> if (arg.isNotEmpty()) VoiceCommand.OpenLink(arg) else null
            "copy" -> VoiceCommand.CopyText
            "copy_all" -> VoiceCommand.CopyAllText
            "paste" -> VoiceCommand.PasteText
            "read_clipboard" -> VoiceCommand.ReadClipboardText
            "comment_write" ->
                if (arg.isNotEmpty()) VoiceCommand.CommentWrite(arg) else null
            "comment_post" -> VoiceCommand.CommentPost
            "drive_open" -> VoiceCommand.DriveOpen
            "drive_search" ->
                if (arg.isNotEmpty()) VoiceCommand.DriveSearch(arg) else null
            "read_page" -> VoiceCommand.ReadPage
            "torch_on" -> VoiceCommand.Torch(true)
            "torch_off" -> VoiceCommand.Torch(false)
            "wifi_on" -> VoiceCommand.WifiOn
            "wifi_off" -> VoiceCommand.WifiOff
            "bluetooth_on" -> VoiceCommand.Bluetooth(true)
            "bluetooth_off" -> VoiceCommand.Bluetooth(false)
            "volume_up" -> VoiceCommand.VolumeUp
            "volume_down" -> VoiceCommand.VolumeDown
            "volume_mute" -> VoiceCommand.VolumeMute
            "volume_unmute" -> VoiceCommand.VolumeUnmute
            "brightness_up" -> VoiceCommand.Brightness(true)
            "brightness_down" -> VoiceCommand.Brightness(false)
            "lock_screen" -> VoiceCommand.LockScreen
            "screenshot" -> VoiceCommand.TakeScreenshot
            "open_notifications" -> VoiceCommand.OpenNotifications
            "mobile_data" -> VoiceCommand.MobileData
            "airplane_mode" -> VoiceCommand.AirplaneMode
            "message" -> {
                val parts = arg.split("|", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() &&
                    parts[1].isNotBlank()
                ) {
                    VoiceCommand.Message(parts[0].trim(), parts[1].trim())
                } else {
                    null
                }
            }
            "call" -> if (arg.isNotEmpty()) VoiceCommand.Call(arg) else null
            else -> null // "unknown" and anything not on the list
        }
    }
}
