package com.allperiph.ui

import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * 键映射**用户覆盖层**：`HidKeyMap` 是"Android keyCode → USB HID usage"的静态真源，
 * 这里只存"用户把某颗键改成别的作用"的少量例外，查表时先看覆盖、再落回默认。
 *
 * 为什么要单独一层而不是改 `HidKeyMap`：
 * 1. `HidKeyMap` 是两条转发路径（`PhysKeyBridge` 窗口回调、`GlobalKeyForwarder` 无障碍）
 *    共用的唯一真源，往里塞可变量会让"默认行为"变得不可预期；
 * 2. 覆盖是**稀疏**的（绝大多数人一颗都不改），用 `keyCode=usage` 的扁平字符串存 pref
 *    最省事，也便于以后导出/同步。
 *
 * 线程：`load` 在 App 启动时调一次，之后只在主线程（设置页）与无障碍回调里读内存副本，
 * 因此用 `@Volatile` 数组 + 不可变快照替换，不加锁。
 */
internal object HidKeyOverrides {

    private const val PREF = "apx_key_overrides"
    private const val KEY_MAP = "overrides"

    /** keyCode → HID usage 的不可变快照；空数组表示"没有覆盖" */
    @Volatile
    private var snapshot: IntArray = IntArray(0)

    /** 本组件自己的偏好文件；界面经它取，不要手写 `"apx_key_overrides"` */
    fun prefs(ctx: android.content.Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)

    /** 从持久化读入（App.onCreate 调一次） */
    fun load(store: SharedPreferences) {
        val raw = store.getString(KEY_MAP, "").orEmpty()
        snapshot = decode(raw)
    }

    /** 当前覆盖（keyCode → usage），供设置页展示 */
    fun current(): Map<Int, Int> {
        val arr = snapshot
        val out = LinkedHashMap<Int, Int>()
        var i = 0
        while (i + 1 < arr.size) {
            out[arr[i]] = arr[i + 1]
            i += 2
        }
        return out
    }

    /** 写入/覆盖一条映射；usage<=0 等价于清除 */
    fun set(store: SharedPreferences, keyCode: Int, usage: Int) {
        val map = current().toMutableMap()
        if (usage <= 0) map.remove(keyCode) else map[keyCode] = usage
        snapshot = encode(map)
        store.edit().putString(KEY_MAP, encodeString(map)).apply()
    }

    /** 清除某颗键的覆盖，回到 `HidKeyMap` 默认 */
    fun clear(store: SharedPreferences, keyCode: Int) = set(store, keyCode, 0)

    /** 查表：先覆盖、后默认 */
    fun resolve(keyCode: Int, defaultUsage: Int): Int {
        val arr = snapshot
        var i = 0
        while (i + 1 < arr.size) {
            if (arr[i] == keyCode) return arr[i + 1]
            i += 2
        }
        return defaultUsage
    }

    private fun decode(raw: String): IntArray {
        if (raw.isBlank()) return IntArray(0)
        val parts = raw.split(',', ';').mapNotNull { it.trim().toIntOrNull() }
        return parts.toIntArray()
    }

    private fun encodeString(map: Map<Int, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun encode(map: Map<Int, Int>): IntArray {
        val out = IntArray(map.size * 2)
        var i = 0
        for ((k, v) in map) {
            out[i++] = k
            out[i++] = v
        }
        return out
    }

    /** 设置页候选：可以从这些 HID usage 里挑（够覆盖遥控/媒体/编辑键） */
    val USAGE_CHOICES: List<Pair<String, Int>> = listOf(
        "Enter" to 0x28,
        "Esc" to 0x29,
        "Backspace" to 0x2A,
        "Tab" to 0x2B,
        "Space" to 0x2C,
        "Home" to 0x4A,
        "End" to 0x4D,
        "PageUp" to 0x4B,
        "PageDown" to 0x4E,
        "Left" to 0x50,
        "Right" to 0x4F,
        "Up" to 0x52,
        "Down" to 0x51,
        "PlayPause" to 0xCD,
        "PrevTrack" to 0xB6,
        "NextTrack" to 0xB5,
        "VolumeUp" to 0xE9,
        "VolumeDown" to 0xEA,
        "Mute" to 0xE2,
        "F11" to 0x44,
    )

    /** 设置页候选：可被改写的 Android 按键（遥控器 + 常见键盘键） */
    val KEY_CHOICES: List<Pair<String, Int>> = listOf(
        "返回" to KeyEvent.KEYCODE_BACK,
        "主页" to KeyEvent.KEYCODE_HOME,
        "菜单" to KeyEvent.KEYCODE_MENU,
        "媒体播放/暂停" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "上一曲" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "下一曲" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "音量+" to KeyEvent.KEYCODE_VOLUME_UP,
        "音量-" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "静音" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Esc" to KeyEvent.KEYCODE_ESCAPE,
    )
}
