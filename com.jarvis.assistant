package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccess : AccessibilityService() {
    companion object { var inst: JarvisAccess? = null }
    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { inst = null; super.onDestroy() }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString() ?: ""

    fun screenText(): String =
        nodes().map { label(it) }.filter { it.isNotBlank() }.distinct().joinToString(". ").take(700)
            .ifEmpty { "The screen has no readable text." }

    fun click(t: String): Boolean {
        val n = nodes().firstOrNull { label(it).contains(t, true) } ?: return false
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        return (x ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun type(t: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodes().firstOrNull { it.isEditable } ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun scroll(down: Boolean): Boolean {
        val n = nodes().firstOrNull { it.isScrollable } ?: return false
        return n.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun global(n: String): Boolean = performGlobalAction(
        when (n) {
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> GLOBAL_ACTION_HOME
        }
    )
}

class JarvisNotifs : NotificationListenerService() {
    companion object { val recent = ArrayList<String>() }
    override fun onNotificationPosted(s: StatusBarNotification) {
        val e = s.notification.extras
        val t = s.packageName.substringAfterLast('.') + ": " +
            (e.getCharSequence("android.title") ?: "") + " " + (e.getCharSequence("android.text") ?: "")
        synchronized(recent) { recent.add(0, t); if (recent.size > 10) recent.removeAt(10) }
    }
}
