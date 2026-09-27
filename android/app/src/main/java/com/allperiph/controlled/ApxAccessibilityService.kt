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
import com.allperiph.core.Log

/**
 * 系统级输入注入（无障碍服务）。
 * 由 [TvInjector] 调用，把另一台手机发来的光标/点击/文本落到被控 Android 系统。
 * 与 TV 模块 [com.allperiph.tv.core.ApxAccessibilityService] 逐字节同实现，仅包名不同。
 *
 * 本服务同时承担两个职责：
 * 1. **输入注入**（已有）：接收对端发来的文本/点击指令，注入到当前聚焦的输入框
 * 2. **焦点检测**（新增）：检测本机 EditText 获焦 → 发送 REQUEST_INPUT 到对端设备
 *
 * 安卓限制（无 root / 无 adb）：
 *  - 不能自由移动系统鼠标光标（需 INJECT_EVENTS），故光标仅用全局浮层可视化；
 *  - 点击 / 滑动用 GestureDescription.dispatchGesture（无障碍允许，无需 root）；
 *  - 文本用 ACTION_SET_TEXT 写入当前聚焦输入框；删除用截断后回写；
 *  - 返回/主页/多任务用 performGlobalAction（无障碍允许）。
 * 音量键用 AudioManager（见 [TvInjector]），无需本服务。
 */
class ApxAccessibilityService : AccessibilityService() {

    private var mainHandler: Handler? = null

    /**
     * 焦点检测回调（新增）。
     * 本机 EditText 获焦时触发，参数为 hint 文字。
     * 调用方应发送 REQUEST_INPUT 帧到对端设备。
     */
    var onFocusDetected: ((String) -> Unit)? = null

    /** 防环标记：当前处于远程输入模式，不回推焦点检测 */
    @Volatile
    var remoteInputMode = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mainHandler = Handler(Looper.getMainLooper())
        Log.i("APX 被控", "无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            // ———— 新增：输入框焦点检测 ————
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 防环：远程输入模式下不触发焦点检测
                if (remoteInputMode) return

                val node = event.source ?: return
                val className = node.className?.toString() ?: return

                // 检测 EditText 获焦
                if (className.contains("EditText") || className.contains("AutoCompleteTextView")) {
                    val hint = node.hint?.toString()
                        ?: node.text?.toString()
                        ?: ""
                    Log.i("APX 被控", "检测到输入框获焦: hint=$hint")
                    onFocusDetected?.invoke(hint)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun post(action: () -> Unit) {
        val h = mainHandler
        if (h != null && Looper.myLooper() != Looper.getMainLooper()) h.post(action) else action()
    }

    /** 在 (x,y) 处点击 */
    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x + 1f, y + 1f)
        val stroke = GestureDescription.StrokeDescription(p, 0, 12)
        val gb = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gb, null, null)
    }

    /** 从 (x1,y1) 滑到 (x2,y2)，耗时 durMs */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val stroke = GestureDescription.StrokeDescription(p, 0, durMs.coerceAtLeast(10))
        val gb = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gb, null, null)
    }

    /** 把文本写入当前聚焦的输入框（追加到末尾） */
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val cur = node.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    cur + text,
                )
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                .also { node.recycle() }
        } finally {
            root.recycle()
        }
    }

    /** 删除当前聚焦输入框最后一个字符 */
    fun deleteChar(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val cur = node.text?.toString() ?: ""
            if (cur.isEmpty()) return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    cur.dropLast(1),
                )
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                .also { node.recycle() }
        } finally {
            root.recycle()
        }
    }

    /**
     * 兜底输入：先把文本放进剪贴板，再对当前聚焦输入框执行 ACTION_PASTE。
     *
     * 为什么需要它：[typeText] 用 ACTION_SET_TEXT，而**部分 ROM（MIUI / HyperOS）会拒绝它**
     * （密码框、部分自绘输入框、或 rootInActiveWindow 拿不到节点时）—— 表现就是"打字没反应"。
     * 粘贴走的是另一条系统路径，成功率明显更高，所以 SET_TEXT 失败后必须退到这里。
     */
    fun paste(text: String): Boolean {
        if (text.isEmpty()) return false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("APX", text))
        val root = rootInActiveWindow ?: return false
        return try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            node.recycle()
            ok
        } finally {
            root.recycle()
        }
    }

    /** 长按：同一位置按住 600ms */
    fun longPress(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x + 1f, y + 1f)
        val stroke = GestureDescription.StrokeDescription(p, 0, 600)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 锁屏（无 root 时的电源键替代；API 28+） */
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