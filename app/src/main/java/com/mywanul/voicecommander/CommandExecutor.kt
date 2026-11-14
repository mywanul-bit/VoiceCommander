package com.mywanul.voicecommander

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
        VoiceCommand.StopListening -> "শোনা বন্ধ করছি"
        is VoiceCommand.Unknown -> "বুঝতে পারিনি: “${command.raw}”"
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
}
