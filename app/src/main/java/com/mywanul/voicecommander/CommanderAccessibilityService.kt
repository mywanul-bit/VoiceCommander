package com.mywanul.voicecommander

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands of the app: taps, types, swipes and global actions on the
 * screen the user is looking at. Must be enabled once by the user in
 * Settings -> Accessibility -> ভয়েস কমান্ডার.
 */
class CommanderAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CommanderAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window content is read on demand via rootInActiveWindow.
    }

    override fun onInterrupt() {}

    /** Package of the app currently on screen, or null. */
    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Waits (up to [timeoutMs]) for [pkg] to come to the foreground. */
    fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentPackage() == pkg) return true
            Thread.sleep(200)
        }
        return currentPackage() == pkg
    }

    /**
     * Breadth-first search for a node whose text or content-description
     * matches [text] (case-insensitive). Exact matches win; otherwise the
     * first node that merely contains the text is returned.
     */
    fun findByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val wanted = text.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var containsMatch: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (nodeText == wanted || desc == wanted) return node
            if (containsMatch == null && (nodeText.contains(wanted) || desc.contains(wanted))) {
                containsMatch = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return containsMatch
    }

    /** The focused editable field, else the first editable field on screen. */
    fun findEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                if (firstEditable == null) firstEditable = node
                if (node.isFocused) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return firstEditable
    }

    /** Clicks the node, or the nearest clickable ancestor. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun clickText(label: String): Boolean {
        val node = findByText(label) ?: return false
        return clickNode(node)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Types [text] into the focused field (or the first editable one). */
    fun setTextInFocused(text: String): Boolean {
        val node = findEditable() ?: return false
        return setText(node, text)
    }

    /**
     * Clicks a messaging-app Send button. WhatsApp's send button is an
     * ImageButton whose content-description is "Send".
     */
    fun clickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val isSend = desc == "send" || text == "send" || text == "পাঠান"
            if (isSend) {
                val className = node.className?.toString() ?: ""
                if (className.contains("ImageButton")) {
                    if (clickNode(node)) return true
                } else if (fallback == null) {
                    fallback = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (fallback != null) return clickNode(fallback)
        return clickText("Send")
    }

    /**
     * Swipe gesture. [down] = true scrolls the content down
     * (finger swipes upward); false scrolls up.
     */
    fun swipe(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        if (down) {
            path.moveTo(w * 0.5f, h * 0.75f)
            path.lineTo(w * 0.5f, h * 0.25f)
        } else {
            path.moveTo(w * 0.5f, h * 0.25f)
            path.lineTo(w * 0.5f, h * 0.75f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Kills the foreground app (never our own app). Returns false if there is nothing to kill. */
    fun killForegroundApp(): Boolean {
        val pkg = currentPackage() ?: return false
        if (pkg == packageName) return false
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        return true
    }
}
