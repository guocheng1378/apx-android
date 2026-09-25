package com.allperiph.tv.core

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection

/**
 * 被控端的**输入法通道**（IME）—— 文本注入里最稳的一条。
 *
 * 为什么需要它：无障碍只有 `ACTION_SET_TEXT`，而不少盒子 / 电视 ROM 会拒绝它
 * （密码框、自绘输入框、rootInActiveWindow 取不到节点）→ 表现就是"打字没反应"。
 * 输入法拿到的是 [InputConnection]，是**系统认定的正规输入通道**，成功率最高。
 *
 * 它"伪装"成一个输入法：有输入法的能力，但**不显示任何键盘**
 * （[onCreateInputView] 返回 0 高度的空视图），用户几乎感觉不到它的存在。
 *
 * 用法：用户在系统「输入法」设置里启用一次并切到它；之后控制端打过来的字符
 * 走 [commit] 直接进当前聚焦的输入框。没切到它时自动退回无障碍 / 粘贴（见 [TvInjector.text]）。
 */
class ApxImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** 关键：空视图 + 0 高度 —— 有输入法能力，但不占屏、不弹键盘 */
    override fun onCreateInputView(): View = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(0, 0)
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 不显示候选栏 */
    override fun onCreateCandidatesView(): View? = null

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: ApxImeService? = null

        /** 当前系统输入法是不是我们（是 → 文本可以走最稳的 InputConnection） */
        fun isActive(): Boolean = instance != null

        /** 往当前聚焦输入框提交文本；必须在主线程调用（被控服务端已切主线程） */
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

        /**
         * 尽力发送按键（回车 / 方向 / 退格）。能否生效取决于目标 App 是否处理
         * [InputConnection.sendKeyEvent] —— **不是全局按键**，只作用于当前输入框。
         */
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
