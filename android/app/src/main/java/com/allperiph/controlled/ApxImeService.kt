package com.allperiph.controlled

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection

/**
 * 被控端的**输入法通道**（IME）—— 与 TV 端 `com.allperiph.tv.core.ApxImeService` 同实现。
 *
 * 为什么手机也要：root 的 `input text` 只支持 ASCII，**中文打不进去**；
 * 无障碍的 ACTION_SET_TEXT 又常被 MIUI / HyperOS 拒绝。输入法的 `InputConnection.commitText`
 * 是系统认定的正规输入通道，中英文都稳 —— 是被控端打字的最后一道、也是最稳的一道兜底。
 *
 * 它"伪装"成一个输入法：有输入法能力但**不显示任何键盘**（0 高度空视图）。
 * 用户在系统「输入法」设置里启用一次并切到它即可；没切时自动退回无障碍 / 粘贴。
 */
class ApxImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** 空视图 + 0 高度 —— 有输入法能力，但不占屏、不弹键盘 */
    override fun onCreateInputView(): View = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(0, 0)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCreateCandidatesView(): View? = null

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: ApxImeService? = null

        /** 当前系统输入法是不是我们 */
        fun isActive(): Boolean = instance != null

        /** 往当前聚焦输入框提交文本（中英文都行）；须在主线程调用 */
        fun commit(text: String): Boolean {
            if (text.isEmpty()) return false
            val s = instance ?: return false
            val ic: InputConnection = s.currentInputConnection ?: return false
            return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
        }

        /** 删掉光标前一个字符（退格） */
        fun delete(): Boolean {
            val s = instance ?: return false
            val ic: InputConnection = s.currentInputConnection ?: return false
            return runCatching { ic.deleteSurroundingText(1, 0) }.getOrDefault(false)
        }

        /** 尽力发送按键（只作用于当前输入框，不是全局按键） */
        fun key(keyCode: Int, down: Boolean): Boolean {
            val s = instance ?: return false
            val ic: InputConnection = s.currentInputConnection ?: return false
            val action = if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
            return runCatching {
                ic.sendKeyEvent(android.view.KeyEvent(action, keyCode))
            }.getOrDefault(false)
        }
    }
}
