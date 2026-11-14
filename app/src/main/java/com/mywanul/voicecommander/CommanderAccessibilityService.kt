package com.mywanul.voicecommander

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
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

    /** Pastes the clipboard into [node] (ACTION_PASTE, API 18+). */
    fun pasteInto(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    /** Select-all + copy from [node] (API 18+). */
    fun copyFrom(node: AccessibilityNodeInfo): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
        Thread.sleep(200)
        return node.performAction(AccessibilityNodeInfo.ACTION_COPY)
    }

    /**
     * Submits a search box with the keyboard Enter key.
     * ACTION_IME_ENTER exists only on Android 13+ — false below that.
     */
    fun performImeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
    }

    /**
     * All visible text on the current screen, deduplicated, joined with
     * newlines (used for read-aloud, copy-all and AI explain/draft).
     */
    fun collectVisibleText(maxChars: Int = 8000): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()
        val seen = HashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.length < maxChars) {
            val node = queue.removeFirst()
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && node.isVisibleToUser && seen.add(t)) {
                if (out.isNotEmpty()) out.append("\n")
                out.append(t)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out.toString()
    }

    /**
     * The editable field whose hint / text / description contains one of
     * [keywords] (e.g. a comment box or a search box), or null.
     */
    fun findEditableWithHint(keywords: List<String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val wanted = keywords.map { it.lowercase() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                val haystack = listOf(
                    node.hintText?.toString(),
                    node.text?.toString(),
                    node.contentDescription?.toString()
                ).joinToString(" ").lowercase()
                if (wanted.any { haystack.contains(it) }) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** The focused editable field, or null when nothing is focused. */
    fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * First non-editable node whose text/desc contains [text] — e.g. a
     * search suggestion or a Drive result (the search box itself, which
     * is editable, is skipped).
     */
    fun findNonEditableByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val wanted = text.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var containsMatch: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!node.isEditable) {
                val nodeText = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                if (nodeText == wanted || desc == wanted) return node
                if (containsMatch == null &&
                    (nodeText.contains(wanted) || desc.contains(wanted))
                ) {
                    containsMatch = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return containsMatch
    }

    /**
     * The search box on the current screen: an editable field whose hint
     * mentions searching, else a "Search" button (tapped to reveal the
     * box), else null.
     */
    fun findSearchBox(): AccessibilityNodeInfo? {
        val byHint = findEditableWithHint(
            listOf("search", "সার্চ", "খুঁজুন", "খোঁজ", "অনুসন্ধান")
        )
        if (byHint != null) return byHint
        val button = findByText("Search") ?: findByText("সার্চ")
            ?: findByText("খুঁজুন")
        if (button != null) {
            if (button.isEditable) return button
            if (clickNode(button)) {
                Thread.sleep(700)
                return findEditable()
            }
        }
        return null
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
