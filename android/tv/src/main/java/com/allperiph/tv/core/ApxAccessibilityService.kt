package com.allperiph.tv.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.allperiph.tv.ui.MainActivity

/**
 * TV 端系统级输入注入（AccessibilityService）。
 *
 * **必须**由用户在「设置 → 无障碍 → 全能外设输入」里手动开启。
 * 需要 `QUERY_ALL_PACKAGES`（Manifest 已声明）才能拿到其它 App 的窗口节点。
 *
 * 本服务同时承担两个职责：
 * 1. **输入注入**（已有）：接收对端发来的文本/点击指令，注入到当前聚焦的输入框
 * 2. **焦点检测**（新增）：检测本机 EditText 获焦 → 发送 REQUEST_INPUT 到对端设备
 *
 * 注意：本服务运行在主线程，所有耗时操作需异步。
 */
class ApxAccessibilityService : AccessibilityService() {

    /**
     * 文本注入回调（由 MainActivity 注册）。
     * 接收对端发来的文本，注入到本机当前聚焦的输入框。
     */
    var onTextInject: ((String) -> Unit)? = null

    /**
     * 焦点检测回调（新增）。
     * 本机 EditText 获焦时触发，参数为 hint 文字。
     * 调用方应发送 REQUEST_INPUT 帧到对端设备。
     */
    var onFocusDetected: ((String) -> Unit)? = null

    /** 防环标记：当前处于远程输入模式，不回推焦点检测 */
    @Volatile
    var remoteInputMode = false

    /** 远程输入来源 ID（防环用） */
    @Volatile
    var remoteSourceId: Int = 0

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            // ———— 已有：点击注入 ————
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val text = node.text?.toString() ?: return
                // 检查是否是输入框的"提交"按钮（如搜索图标、确认键）
                if (isSubmitAction(node)) {
                    Log.i(TAG, "检测到提交动作，注入回车键")
                    onTextInject?.invoke("\n")
                }
            }

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
                    Log.i(TAG, "检测到输入框获焦: hint=$hint")
                    onFocusDetected?.invoke(hint)
                }
            }

            // ———— 已有：文本变化（用于检测输入框内容变化）———
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source ?: return
                val className = node.className?.toString() ?: return
                if (className.contains("EditText")) {
                    // 文本变化时，如果不在远程输入模式，可以触发焦点检测
                    if (!remoteInputMode && event.fromIndex == 0 && event.toIndex == 0) {
                        // 输入框被清空，可能是用户手动删除
                    }
                }
            }
        }
    }

    /** 判断节点是否是"提交"类操作（搜索图标、确认键等） */
    private fun isSubmitAction(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        return desc.contains("搜索") || desc.contains("search") ||
               desc.contains("确认") || desc.contains("ok") ||
               text.contains("搜索") || text.contains("search")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")
    }

    companion object {
        private const val TAG = "ApxAccessibility"

        /** 获取当前运行的服务实例（静态引用，供外部调用） */
        @Volatile
        var instance: ApxAccessibilityService? = null
            private set
    }

    init {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}