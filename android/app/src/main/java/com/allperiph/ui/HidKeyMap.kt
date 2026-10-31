package com.allperiph.ui

import android.view.KeyEvent
import com.allperiph.hid.HidKeys

/**
 * Android keyCode → USB HID 的**单一真源**键映射表。
 *
 * 此前这一段只长在 [PhysKeyBridge] 里（Activity 窗口回调那条路径）。
 * issue #52 建议 6 要加第二条路径（无障碍 `onKeyEvent`，无界面也能转发），
 * 两处各写一份码表必然分叉 —— issue #52 问题 3「标点键无反应」就是这么来的
 * （`input_injector_mac` 那份码表漏了整行标点，落 default 被静默丢弃）。
 * 所以搬运到这里，两条路径共用。
 *
 * [metaMod] 把 Android 的 metaState 位转成 HID 修饰位图（协议里 mod 必须跟按键走）。
 */
internal object HidKeyMap {

    /** Android metaState → HID 修饰位图（Ctrl/Shift/Alt/Win） */
    fun metaMod(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or HidKeys.MOD_CTRL
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or HidKeys.MOD_SHIFT
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or HidKeys.MOD_ALT
        if (meta and KeyEvent.META_META_ON != 0) m = m or HidKeys.MOD_WIN
        return m
    }

    /** Android keyCode → USB HID Keyboard Page (0x07) usage；认不出返回 0（放行本地） */
    fun usageOf(kc: Int): Int = when (kc) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (kc - KeyEvent.KEYCODE_A)
        KeyEvent.KEYCODE_0 -> 0x27
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (kc - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28
        KeyEvent.KEYCODE_ESCAPE -> 0x29
        KeyEvent.KEYCODE_DEL -> 0x2A                        // 退格
        KeyEvent.KEYCODE_TAB -> 0x2B
        KeyEvent.KEYCODE_SPACE -> 0x2C
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (kc - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SYSRQ -> 0x46                      // PrtSc
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
        KeyEvent.KEYCODE_BREAK -> 0x48
        KeyEvent.KEYCODE_INSERT -> 0x49
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C                // Del
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        KeyEvent.KEYCODE_DPAD_UP -> 0x52
        KeyEvent.KEYCODE_DPAD_CENTER -> 0x28                // 方向确认 = Enter
        KeyEvent.KEYCODE_NUM_LOCK -> 0x53
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
        in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> 0x59 + (kc - KeyEvent.KEYCODE_NUMPAD_1)
        KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
        KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
        else -> 0
    }
}