package com.allperiph.controlled

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.allperiph.shared.util.Log

class ApxAccessibilityService : AccessibilityService() {
    private var mainHandler: Handler? = null
    var onFocusDetected: ((String) -> Unit)? = null
    @Volatile var remoteInputMode = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mainHandler = Handler(Looper.getMainLooper())
        Log.i("APX", "被控无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (remoteInputMode) return
                val node = event.source ?: return
                val className = node.className?.toString() ?: return
                if (className.contains("EditText") || className.contains("AutoCompleteTextView")) {
                    val hint = event.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    Log.i("APX", "检测到输入框获焦: $hint")
                    onFocusDetected?.invoke(hint)
                }
            }
        }
    }

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path(); p.moveTo(x, y); p.lineTo(x + 1f, y + 1f)
        val gb = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 12)).build()
        return dispatchGesture(gb, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path(); p.moveTo(x1, y1); p.lineTo(x2, y2)
        val gb = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durMs.coerceAtLeast(10))).build()
        return dispatchGesture(gb, null, null)
    }

    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val cur = node.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { node.recycle() }
        } finally { root.recycle() }
    }

    fun deleteChar(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val cur = node.text?.toString() ?: ""
            if (cur.isEmpty()) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.dropLast(1))
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { node.recycle() }
        } finally { root.recycle() }
    }

    fun paste(text: String): Boolean {
        if (text.isEmpty()) return false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("APX", text))
        val root = rootInActiveWindow ?: return false
        return try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            node.recycle(); ok
        } finally { root.recycle() }
    }

    fun longPress(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path(); p.moveTo(x, y); p.lineTo(x + 1f, y + 1f)
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 600)).build(), null, null)
    }

    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        @Volatile
        var instance: ApxAccessibilityService? = null
        fun isReady(): Boolean = instance != null
    }
}