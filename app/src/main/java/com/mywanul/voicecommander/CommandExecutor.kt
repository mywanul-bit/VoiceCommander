package com.mywanul.voicecommander

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs parsed voice commands on a background thread (so the waits for
 * a chat to open never block the UI or the accessibility service).
 * Every command returns a short Bengali result shown in the app log.
 */
class CommandExecutor(context: Context) {

    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val A11Y_OFF =
            "Accessibility সেবা চালু নেই — অ্যাপ থেকে সেটিংসে গিয়ে ভয়েস কমান্ডার চালু করুন"
    }

    fun execute(command: VoiceCommand, onResult: (String) -> Unit) {
        worker.execute {
            val result = try {
                run(command)
            } catch (e: Exception) {
                "ভুল হয়েছে: ${e.message ?: e.toString()}"
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun a11y(): CommanderAccessibilityService? =
        CommanderAccessibilityService.instance

    private fun run(command: VoiceCommand): String = when (command) {
        is VoiceCommand.OpenApp -> openApp(command.appName)
        VoiceCommand.CloseApp -> closeApp()
        VoiceCommand.GoHome -> {
            a11y()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "হোমে গেলাম"
        }
        VoiceCommand.GoBack -> {
            a11y()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "পেছনে গেলাম"
        }
        is VoiceCommand.Tap -> tap(command.label)
        is VoiceCommand.Scroll -> scroll(command.down)
        is VoiceCommand.TypeText -> typeText(command.text)
        is VoiceCommand.Message -> sendMessage(command.name, command.body)
        is VoiceCommand.Call -> call(command.name)
        VoiceCommand.StopListening -> {
            TtsHelper.stop(appContext)
            "শোনা বন্ধ করছি"
        }

        // ---- browsing / copy-paste / comments / Drive / read-aloud / AI ----
        is VoiceCommand.Search -> search(command.query)
        is VoiceCommand.OpenLink -> openLink(command.label)
        VoiceCommand.Bookmark -> bookmark()
        VoiceCommand.CopyText -> copy()
        VoiceCommand.CopyAllText -> copyAll()
        VoiceCommand.PasteText -> paste()
        VoiceCommand.ReadClipboardText -> InteractionHelper.readClipboardAloud(appContext)
        is VoiceCommand.CommentWrite -> commentWrite(command.text)
        VoiceCommand.CommentPost -> commentPost()
        VoiceCommand.DriveOpen -> InteractionHelper.openDrive(appContext)
        is VoiceCommand.DriveSearch -> driveSearch(command.name)
        VoiceCommand.ReadPage -> readPage()
        VoiceCommand.StopSpeech -> {
            TtsHelper.stop(appContext)
            "পড়া থামালাম"
        }
        VoiceCommand.Explain -> explain()
        VoiceCommand.DraftComment -> draftComment()

        // ---- device control ----
        is VoiceCommand.AlarmSet ->
            AlarmHelper.schedule(appContext, command.triggerAtMs, command.label)
        VoiceCommand.AlarmDismiss -> AlarmHelper.dismiss(appContext)
        is VoiceCommand.Torch -> TorchHelper.setTorch(appContext, command.on)
        VoiceCommand.WifiOn -> wifiToggle(true)
        VoiceCommand.WifiOff -> wifiToggle(false)
        is VoiceCommand.WifiConnect -> wifiConnect(command.ssid, command.password)
        is VoiceCommand.Bluetooth -> bluetooth(command.on)
        VoiceCommand.VolumeUp -> VolumeHelper.adjust(appContext, AudioManager.ADJUST_RAISE)
        VoiceCommand.VolumeDown ->
            VolumeHelper.adjust(appContext, AudioManager.ADJUST_LOWER)
        VoiceCommand.VolumeMute -> VolumeHelper.adjust(appContext, AudioManager.ADJUST_MUTE)
        VoiceCommand.VolumeUnmute ->
            VolumeHelper.adjust(appContext, AudioManager.ADJUST_UNMUTE)
        is VoiceCommand.Brightness -> BrightnessHelper.adjust(appContext, command.up)
        VoiceCommand.LockScreen -> screenAction(
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "স্ক্রিন লক করলাম"
        )
        VoiceCommand.TakeScreenshot -> screenAction(
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "স্ক্রিনশট নিলাম"
        )
        VoiceCommand.OpenNotifications -> screenAction(
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "নোটিফিকেশন খুললাম"
        )
        VoiceCommand.MobileData -> openSettingsPage(
            Settings.ACTION_DATA_USAGE_SETTINGS, "মোবাইল ডাটা"
        )
        VoiceCommand.AirplaneMode -> openSettingsPage(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS, "এয়ারপ্লেন মোড"
        )

        is VoiceCommand.Unknown -> smartFallback(command.raw)
    }

    // ---------- browsing / copy-paste / comments / Drive / read-aloud / AI ----------

    private fun search(query: String): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.search(service, query)
    }

    private fun openLink(label: String): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.openLink(service, label)
    }

    private fun bookmark(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.bookmark(service)
    }

    private fun copy(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.copy(service, appContext)
    }

    private fun copyAll(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.copyAll(service, appContext)
    }

    private fun paste(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.paste(service, appContext)
    }

    private fun commentWrite(text: String): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.writeComment(service, text)
    }

    private fun commentPost(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.postComment(service)
    }

    private fun driveSearch(name: String): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.searchDrive(appContext, service, name)
    }

    private fun readPage(): String {
        val service = a11y() ?: return A11Y_OFF
        return InteractionHelper.readPageAloud(service, appContext)
    }

    /**
     * "এটা বুঝিয়ে দাও": explains the clipboard text (or, when the
     * clipboard is empty, the visible screen text) in simple Bengali
     * and reads the answer aloud. Needs the free Gemini key.
     */
    private fun explain(): String {
        val text = ClipboardHelper.read(appContext)?.takeIf { it.isNotBlank() }
            ?: a11y()?.collectVisibleText()?.takeIf { it.isNotBlank() }
            ?: return "বোঝানোর মতো কোনো লেখা পেলাম না — আগে কিছু কপি করুন বা লেখা আছে এমন পাতা খুলুন"
        val res = AIHelper.explain(appContext, text)
        if (!res.ok) return res.text
        TtsHelper.speak(appContext, res.text)
        return "বুঝিয়ে দিচ্ছি: ${res.text.take(300)}" +
            if (res.text.length > 300) "…" else ""
    }

    /**
     * "কমেন্ট লিখে দাও": Gemini drafts a short Bengali comment about
     * the visible post and types it into the comment box. It is NOT
     * posted — the user posts with "পোস্ট করো". Needs the free key.
     */
    private fun draftComment(): String {
        val service = a11y() ?: return A11Y_OFF
        val postText = service.collectVisibleText().takeIf { it.isNotBlank() }
            ?: return "পোস্টের লেখা পেলাম না — আগে পোস্টটি খুলুন"
        val res = AIHelper.draftComment(appContext, postText)
        if (!res.ok) return res.text
        val written = InteractionHelper.writeComment(service, res.text)
        return if (written.startsWith("কমেন্ট লিখলাম")) {
            "কমেন্ট লিখে দিলাম (পোস্ট করিনি): ${res.text}"
        } else {
            "কমেন্টের খসড়া তৈরি করলাম, কিন্তু লেখা যায়নি: $written\nখসড়া: ${res.text}"
        }
    }

    /**
     * Smart-command fallback: when the local parser matches nothing and
     * a Gemini key is saved, Gemini maps the spoken phrase to a known
     * command and it is run (one level only — smartMap never returns
     * Unknown, so there is no loop). Without a key, the old
     * "বুঝতে পারিনি" behaviour is kept.
     */
    private fun smartFallback(raw: String): String {
        if (!AIHelper.hasApiKey(appContext)) {
            return "বুঝতে পারিনি: “$raw”"
        }
        val mapped = AIHelper.smartMap(appContext, raw)
            ?: return "বুঝতে পারিনি: “$raw”"
        if (mapped is VoiceCommand.Unknown) return "বুঝতে পারিনি: “$raw”"
        return run(mapped)
    }

    // ---------- apps ----------

    private fun openApp(name: String): String {
        if (AppResolver.isBarta(name)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppResolver.BARTA_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return "বার্তা খুললাম"
        }
        val pkg = AppResolver.resolveApp(appContext, name)
            ?: return "অ্যাপটি পাওয়া যায়নি: $name"
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "অ্যাপটি খোলা যায়নি: $name"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return "$name খুললাম"
    }

    private fun closeApp(): String {
        val service = a11y() ?: return A11Y_OFF
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(400)
        val pkg = service.currentPackage()
        if (pkg != null && pkg != appContext.packageName && !isLauncher(pkg)) {
            service.killForegroundApp()
        }
        return "অ্যাপ বন্ধ করলাম"
    }

    private fun isLauncher(pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = appContext.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName == pkg
    }

    // ---------- screen ----------

    private fun tap(label: String): String {
        val service = a11y() ?: return A11Y_OFF
        return if (service.clickText(label)) {
            "“$label”-তে ক্লিক করলাম"
        } else {
            "“$label” খুঁজে পেলাম না"
        }
    }

    private fun scroll(down: Boolean): String {
        val service = a11y() ?: return A11Y_OFF
        service.swipe(down)
        return if (down) "নিচে গেলাম" else "উপরে গেলাম"
    }

    /** Types into the focused text field. Keyboard keys are tapped with the Tap command. */
    private fun typeText(text: String): String {
        val service = a11y() ?: return A11Y_OFF
        return if (service.setTextInFocused(text)) {
            "লিখলাম: $text"
        } else {
            "লেখার জায়গা পেলাম না — আগে কোনো লেখার ঘরে ক্লিক করুন"
        }
    }

    // ---------- messaging ----------

    /**
     * "রিফাতকে মেসেজ দাও, আমি আসছি":
     * contacts -> phone number -> WhatsApp chat (wa.me) -> type -> tap Send.
     * Without WhatsApp, opens the SMS composer with the number and body filled.
     */
    private fun sendMessage(name: String, body: String): String {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "কন্টাক্টের অনুমতি নেই — অ্যাপ থেকে অনুমতি দিন"
        }
        val match = ContactHelper.findPhone(appContext, name)
            ?: return "কন্টাক্টে “$name” নামে কাউকে পেলাম না"
        val number = ContactHelper.normalizePhone(match.number)
        val pm = appContext.packageManager
        val waPkg = when {
            AppResolver.isInstalled(appContext, "com.whatsapp") -> "com.whatsapp"
            AppResolver.isInstalled(appContext, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
            else -> null
        }
        if (waPkg == null) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+$number"))
                .putExtra("sms_body", body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return "হোয়াটসঅ্যাপ নেই — SMS খুললাম (${match.displayName})"
        }
        return sendViaWhatsApp(waPkg, number, body, match.displayName)
    }

    private fun sendViaWhatsApp(
        waPkg: String,
        number: String,
        body: String,
        displayName: String
    ): String {
        val service = a11y() ?: return A11Y_OFF
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        service.waitForPackage(waPkg, 7000)
        Thread.sleep(1500)
        if (!service.setTextInFocused(body)) {
            return "হোয়াটসঅ্যাপ চ্যাট খুললাম, কিন্তু লেখার জায়গা পেলাম না"
        }
        Thread.sleep(500)
        return if (service.clickSend()) {
            "$displayName-কে মেসেজ পাঠালাম"
        } else {
            "মেসেজ লিখেছি, কিন্তু সেন্ড বোতাম পেলাম না"
        }
    }

    // ---------- calling ----------

    private fun call(name: String): String {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "কন্টাক্টের অনুমতি নেই — অ্যাপ থেকে অনুমতি দিন"
        }
        val match = ContactHelper.findPhone(appContext, name)
            ?: return "কন্টাক্টে “$name” নামে কাউকে পেলাম না"
        val number = ContactHelper.normalizePhone(match.number)
        val hasCallPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPermission) {
            Intent.ACTION_CALL
        } else {
            Intent.ACTION_DIAL
        }
        val intent = Intent(action, Uri.parse("tel:+$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return "${match.displayName}-কে কল করছি"
    }

    // ---------- device control ----------

    /**
     * WiFi toggle (direct toggling is blocked for normal apps on
     * Android 10+, so the system WiFi panel is opened and the toggle
     * switch is tapped with the AccessibilityService).
     */
    private fun wifiToggle(wantOn: Boolean): String {
        val service = a11y() ?: return A11Y_OFF
        appContext.startActivity(WifiHelper.panelIntent(appContext))
        Thread.sleep(1500)
        val state = toggleSwitch(service, listOf("Wi-Fi", "ওয়াইফাই"), wantOn)
        return when (state) {
            true -> if (wantOn) "ওয়াইফাই চালু করলাম" else "ওয়াইফাই বন্ধ করলাম"
            false -> "ওয়াইফাইয়ের টগল চাপা যায়নি"
            null -> "ওয়াইফাই প্যানেল খুললাম — টগলটি নিজে চাপুন"
        }
    }

    private fun bluetooth(on: Boolean): String {
        val direct = BluetoothHelper.setBluetooth(appContext, on)
        if (direct == true) {
            return if (on) "ব্লুটুথ চালু করলাম" else "ব্লুটুথ বন্ধ করলাম"
        }
        if (direct == false) {
            return "ব্লুটুথ ${if (on) "চালু" else "বন্ধ"} করা যায়নি"
        }
        // Direct toggle is not possible — fall back to the panel.
        val service = a11y() ?: return A11Y_OFF
        appContext.startActivity(BluetoothHelper.panelIntent(appContext))
        Thread.sleep(1500)
        val state = toggleSwitch(service, listOf("Bluetooth", "ব্লুটুথ"), on)
        return when (state) {
            true -> if (on) "ব্লুটুথ চালু করলাম" else "ব্লুটুথ বন্ধ করলাম"
            false -> "ব্লুটুথের টগল চাপা যায়নি"
            null -> "ব্লুটুথ প্যানেল খুললাম — টগলটি নিজে চাপুন"
        }
    }

    /**
     * Finds a switch by its label and taps it. Returns true when the
     * switch ends up in the wanted state, false when the tap failed,
     * and null when no switch with that label is on screen.
     */
    private fun toggleSwitch(
        service: CommanderAccessibilityService,
        labels: List<String>,
        wantOn: Boolean
    ): Boolean? {
        for (label in labels) {
            val node = service.findByText(label) ?: continue
            if (node.isCheckable && node.isChecked == wantOn) return true
            if (!service.clickNode(node)) continue
            Thread.sleep(700)
            val after = service.findByText(label)
            return if (after != null && after.isCheckable) {
                after.isChecked == wantOn
            } else {
                true
            }
        }
        return null
    }

    /** "ওয়াইফাই কানেক্ট করো <নাম> [পাসওয়ার্ড <পাসওয়ার্ড>]" */
    private fun wifiConnect(ssid: String, password: String?): String {
        val service = a11y() ?: return A11Y_OFF
        appContext.startActivity(WifiHelper.settingsIntent(appContext))
        Thread.sleep(1500)
        if (!service.clickText(ssid)) {
            return "ওয়াইফাই তালিকায় “$ssid” পেলাম না"
        }
        Thread.sleep(1200)
        val editable = service.findEditable()
        if (editable != null) {
            if (password.isNullOrEmpty()) {
                return "“$ssid”-তে পাসওয়ার্ড লাগবে — পাসওয়ার্ড সহ আবার বলুন"
            }
            service.setText(editable, password)
            Thread.sleep(400)
            if (service.clickText("সংযোগ করুন") ||
                service.clickText("Connect") ||
                service.clickText("সংযোগ")
            ) {
                return "“$ssid”-তে কানেক্ট করছি"
            }
            return "পাসওয়ার্ড লিখেছি, কিন্তু সংযোগ বোতাম পেলাম না"
        }
        return "“$ssid”-তে কানেক্ট করছি"
    }

    /** Accessibility global actions (lock screen / screenshot / notifications). */
    private fun screenAction(action: Int, doneMessage: String): String {
        val service = a11y() ?: return A11Y_OFF
        if ((action == AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN ||
                action == AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        ) {
            return "এই ফোনের অ্যান্ড্রয়েড সংস্করণে এই কাজটি করা যায় না"
        }
        return if (service.performGlobalAction(action)) {
            doneMessage
        } else {
            "কাজটি করা যায়নি"
        }
    }

    /**
     * Mobile data / airplane mode cannot be switched by a normal app,
     * so the matching settings page is opened and the user is told
     * the toggle is there.
     */
    private fun openSettingsPage(action: String, name: String): String {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            "$name সেটিংস খুললাম — টগলটি সেখানে চাপুন (অ্যাপ থেকে সরাসরি বদলানো যায় না)"
        } catch (e: Exception) {
            "$name সেটিংস খোলা যায়নি: ${e.message}"
        }
    }
}
