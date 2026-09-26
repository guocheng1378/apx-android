package com.allperiph.hid

import android.os.Handler
import android.os.Looper
import com.allperiph.core.Log
import com.allperiph.core.Uplink
import com.allperiph.ui.AgentController

/**
 * USB 键盘发送中枢（v1.13）：rid 21 HID Keyboard TLC。
 *
 * 协议与 ui/KeyboardPanels 一致：[0x15, mod, 0, k1..k6] 共 9 字节，
 * 修饰键位图 / usage code 均为 USB HID Keyboard Page (0x07) 标准值。
 * 出口：有线走 USB HID（rid 21）；选定受控设备（TV / PC）时走 9511 控制面（[com.allperiph.wireless.ControlTarget]）。
 *
 * sticky 修饰键：点 Ctrl/Alt/Shift/Win 点亮 → 下一次 tap 带上该修饰 → 发完自动清。
 */
object HidKeys {
    private const val TAG = "HidKeys"

    /** HID Keyboard TLC Report ID（shared/src/hid_descriptor.cpp buildTlcKeyboard） */
    private const val REPORT_ID: Byte = 0x15 // 21

    // 修饰键位（USB HID Keyboard modifier byte）
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_WIN = 0x08

    /** 预组合快捷键（label, mod, usage） */
    data class Combo(val label: String, val mod: Int, val usage: Int)

    /** 常驻第 1 排：编辑类 */
    val ROW1 = listOf(
        Combo("复制", MOD_CTRL, 0x06),               // Ctrl+C
        Combo("粘贴", MOD_CTRL, 0x19),               // Ctrl+V
        Combo("剪切", MOD_CTRL, 0x1B),               // Ctrl+X
        Combo("撤销", MOD_CTRL, 0x1D),               // Ctrl+Z
        Combo("保存", MOD_CTRL, 0x16),               // Ctrl+S
        Combo("全选", MOD_CTRL, 0x04),               // Ctrl+A
        Combo("查找", MOD_CTRL, 0x09),               // Ctrl+F
    )

    /** 常驻第 2 排：窗口/系统类 */
    val ROW2 = listOf(
        Combo("切窗", MOD_ALT, 0x2B),                // Alt+Tab
        Combo("桌面", MOD_WIN, 0x07),                // Win+D
        Combo("资源", MOD_WIN, 0x08),                // Win+E
        Combo("锁屏", MOD_WIN, 0x0F),                // Win+L
        Combo("任务", MOD_CTRL or MOD_SHIFT, 0x29),  // Ctrl+Shift+Esc
        Combo("刷新", 0, 0x3E),                      // F5
        Combo("关闭", MOD_ALT, 0x3D),                // Alt+F4
    )

    /** QWERTY 布局（label → usage 由 [usageOf] 负责） */
    val QWERTY: List<List<String>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="),
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'"),
        listOf("Z", "X", "C", "V", "B", "N", "M", ",", ".", "/"),
        listOf("Esc", "Tab", "Space", "Enter", "⌫", "Del"),
    )

    /** 修饰键排（标签 → 修饰位） */
    val MOD_LABELS: List<Pair<String, Int>> = listOf(
        "Ctrl" to MOD_CTRL, "Alt" to MOD_ALT, "Shift" to MOD_SHIFT, "Win" to MOD_WIN,
    )

    /** 出厂预置快捷键（快捷键页「恢复默认」与首次启动的初始值） */
    val DEFAULTS: List<Combo> get() = ROW1 + ROW2

    /**
     * 可配置按键清单（快捷键编辑弹窗的下拉项）：显示名 → Keyboard Page (0x07) usage。
     * 覆盖常用字符 / 编辑键 / F1–F12 / 方向键，足够覆盖日常组合键；未列出的键
     * 仍可用 [usageOf] 发送，只是不能在 UI 里选。
     */
    val KEY_CHOICES: List<Pair<String, Int>> = buildList {
        ('A'..'Z').forEachIndexed { i, c -> add(c.toString() to 0x04 + i) }
        ('1'..'9').forEachIndexed { i, c -> add(c.toString() to 0x1E + i) }
        add("0" to 0x27)
        listOf(
            "Enter" to 0x28, "Esc" to 0x29, "Backspace" to 0x2A, "Tab" to 0x2B, "Space" to 0x2C,
            "-" to 0x2D, "=" to 0x2E, "[" to 0x2F, "]" to 0x30,
            ";" to 0x33, "'" to 0x34, "," to 0x36, "." to 0x37, "/" to 0x38,
            "Insert" to 0x49, "Home" to 0x4A, "PageUp" to 0x4B, "Delete" to 0x4C,
            "End" to 0x4D, "PageDown" to 0x4E,
            "→" to 0x4F, "←" to 0x50, "↓" to 0x51, "↑" to 0x52,
        ).forEach { add(it) }
        (1..12).forEach { add("F$it" to 0x39 + it) }
    }

    /** usage → 显示名（反查表，惰性构建一次） */
    private val KEY_NAMES: Map<Int, String> by lazy { KEY_CHOICES.associate { it.second to it.first } }

    /** 显示名 → usage（游戏键盘等键位表用） */
    private val KEY_USAGE: Map<String, Int> by lazy { KEY_CHOICES.associate { it.first to it.second } }

    /**
     * 按键候选**分组**（v1.31）：编辑弹窗改为"先选组、再选键"。
     * [KEY_CHOICES] 平铺 79 项时，想在 Spinner 里找 F12 得滑很久。
     */
    val KEY_GROUPS: List<Pair<String, List<Pair<String, Int>>>> by lazy {
        listOf(
            "字母" to KEY_CHOICES.filter { it.first.length == 1 && it.first[0] in 'A'..'Z' },
            "数字" to KEY_CHOICES.filter { it.first.length == 1 && it.first[0] in '0'..'9' },
            "符号" to KEY_CHOICES.filter { it.first in listOf("-", "=", "[", "]", ";", "'", ",", ".", "/") },
            "编辑键" to KEY_CHOICES.filter {
                it.first in listOf(
                    "Enter", "Esc", "Backspace", "Tab", "Space",
                    "Insert", "Delete", "Home", "End", "PageUp", "PageDown",
                )
            },
            "方向键" to KEY_CHOICES.filter { it.first in listOf("→", "←", "↓", "↑") },
            "功能键" to KEY_CHOICES.filter { it.first.startsWith("F") && it.first.drop(1).toIntOrNull() != null },
        )
    }

    /**
     * 键面标签 → usage：先查 [KEY_CHOICES]（覆盖方向键 / Insert 等 [usageOf] 不含的键），
     * 再退回 [usageOf]；都认不出返回 0。
     */
    fun usageByLabel(label: String): Int = KEY_USAGE[label] ?: usageOf(label)

    /** usage → 显示名；不在清单内时退化为十六进制，保证不返回空串 */
    fun keyLabel(usage: Int): String = KEY_NAMES[usage] ?: "0x%02X".format(usage)

    /** 修饰位图 → "Ctrl + Shift"；无修饰返回空串 */
    fun modLabel(mod: Int): String =
        MOD_LABELS.filter { mod and it.second != 0 }.joinToString(" + ") { it.first }

    /** 组合键可读描述，如 "Ctrl + C" / "Win + D" / "F5"（快捷键页副行文字） */
    fun comboText(c: Combo): String =
        listOf(modLabel(c.mod), keyLabel(c.usage)).filter { it.isNotEmpty() }.joinToString(" + ")

    // —————————————————————————— sticky 修饰键 ——————————————————————————

    @Volatile
    private var sticky = 0

    fun isSticky(mod: Int): Boolean = sticky and mod != 0
    fun toggleSticky(mod: Int) { sticky = sticky xor mod }

    // —————————————————————————— 发送 ——————————————————————————

    private val handler = Handler(Looper.getMainLooper())
    private val releaseTask = Runnable { send(0, IntArray(0)) }

    /** 预组合键：按下 60ms 后自动全释放 */
    fun combo(c: Combo) {
        handler.removeCallbacks(releaseTask)
        heldCombo = null // 普通发送取代"按住"状态
        send(c.mod, intArrayOf(c.usage))
        handler.postDelayed(releaseTask, 60)
    }

    // —————————————————————————— 按住不放（长按发送） ——————————————————————————

    /** 当前被"按住"的组合键（null = 没按住） */
    @Volatile
    private var heldCombo: Combo? = null

    /**
     * 长按：把组合键**按住不放**（keydown 之后不释放），直到 [releaseHold]。
     * 用于需要持续按住的场景：按住 Ctrl 滚轮缩放、按住 Shift 连续多选、游戏里的持续键。
     */
    fun hold(c: Combo) {
        handler.removeCallbacks(releaseTask)
        send(c.mod, intArrayOf(c.usage))
        heldCombo = c
    }

    /** 松开长按：释放全部按键（幂等，未按住时什么都不做） */
    fun releaseHold() {
        if (heldCombo == null) return
        heldCombo = null
        handler.removeCallbacks(releaseTask)
        send(0, IntArray(0))
    }



    /**
     * 普通字符键：带上 sticky 修饰发送，60ms 后释放，发完清 sticky。
     * @return 本次实际使用的修饰位图（供 UI 日志/提示）
     */
    fun tap(usage: Int): Int {
        if (usage <= 0) return sticky
        handler.removeCallbacks(releaseTask)
        send(sticky, intArrayOf(usage))
        handler.postDelayed(releaseTask, 60)
        val used = sticky
        sticky = 0
        return used
    }

    /** 键面标签 → Keyboard Page (0x07) usage code；未识别返回 0 */
    fun usageOf(label: String): Int = when (label) {
        "Esc" -> 0x29; "Tab" -> 0x2B; "Enter" -> 0x28; "Space" -> 0x2C
        "⌫" -> 0x2A; "Del" -> 0x4C
        "-" -> 0x2D; "=" -> 0x2E; "[" -> 0x2F; "]" -> 0x30
        ";" -> 0x33; "'" -> 0x34; "," -> 0x36; "." -> 0x37; "/" -> 0x38
        else -> when (val c = label.firstOrNull() ?: return 0) {
            in 'A'..'Z' -> 0x04 + (c - 'A')
            in '1'..'9' -> 0x1E + (c - '1')
            '0' -> 0x27
            else -> 0
        }
    }

    /**
     * 出口择优：**判据统一在 [Uplink.resolve]**（自写一套顺序 → 各界面口径不一致的教训见那里）。
     *
     * 键盘只有两条真实链路：无线受控目标（9511，手机把同一套 (mod, keys) 语义经 APX1 控制帧上行，
     * 对端翻成 SendInput / evdev）与 USB HID（rid 21）。**蓝牙 HID 只实现了鼠标与多媒体**，
     * 所以这里蓝牙一律按"不可用"传入 —— 否则界面会显示"出口：蓝牙 HID"而按键其实发不出去。
     *
     * rid 21：[id, mod, 0, k1..k6]；keys 为空 = 全释放
     */
    private fun send(mod: Int, keys: IntArray) {
        val rt = AgentController.runtime
        val target = com.allperiph.wireless.ControlTarget
        val path = Uplink.resolve(
            rt?.hid,
            btConnected = false,
            wireless = target.isControlling() && target.controlClient != null,
        )
        when (path) {
            Uplink.WIRELESS -> {
                // **mod 必须一起发**：原先这里只逐键发 usage、把 mod 丢掉，于是「复制/粘贴/撤销/
                // 保存/全选/查找（Ctrl+X）」「切窗(Alt+Tab)」「桌面(Win+D)」「资源(Win+E)」「锁屏」
                // 到对端全退化成裸字母或没反应 —— 正是用户报的「快捷键不能用了」。
                // 释放沿用本机路径的时序：轻点/字符键由 releaseTask（60ms 后）发全释放帧，
                // 长按由 releaseHold() 发 —— 所以这里只负责「按下」，不要自己补 up。
                Uplink.set(path, target.label)
                val c = target.controlClient
                if (c != null) {
                    if (keys.isEmpty()) {
                        c.keyboard(0, false)                        // 全释放（含修饰键）
                    } else {
                        for (k in keys) if (k != 0) c.keyboard(k, true, mod)
                    }
                }
            }
            Uplink.USB -> {
                val rep = ByteArray(9)
                rep[0] = REPORT_ID
                rep[1] = mod.toByte()
                for (i in 0 until minOf(keys.size, 6)) rep[3 + i] = keys[i].toByte()
                if (rt?.hid?.sendInputReport(rep) == true) {
                    Uplink.set(path)
                } else {
                    // 写入失败就**不能**还显示"USB"（界面会骗人）；如实记成无出口
                    Uplink.set(Uplink.NONE, "USB HID 写入失败")
                    Log.w(TAG, "键盘 HID 写入失败：USB 出口不可用")
                }
            }
            else -> {
                // 无出口：原先只 Log.w，界面完全看不出"为什么按键没反应"
                val first = Uplink.current != Uplink.NONE
                Uplink.set(
                    Uplink.NONE,
                    if (target.isControlling()) "受控目标已断开"
                    else "USB HID 未就绪（/dev/hidg0 未挂载或 USB 口被调试占用）",
                )
                if (first) Log.w(TAG, "键盘无可用出口：USB 未挂载且未选受控设备")
            }
        }
    }
}
