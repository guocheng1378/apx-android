package com.allperiph.hid

import android.os.Handler
import android.os.Looper
import com.allperiph.shared.util.Log
import com.allperiph.core.Uplink
import com.allperiph.ui.AgentController
import com.allperiph.ui.HotkeyController

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

    /** 常驻第 3 排：Windows 11 新常用（重开标签 / 任务视图 / 截图等） */
    val ROW3 = listOf(
        Combo("重开标签", MOD_CTRL or MOD_SHIFT, 0x17),  // Ctrl+Shift+T
        Combo("任务视图", MOD_WIN, 0x2B),                // Win+Tab
        Combo("截图", MOD_WIN or MOD_SHIFT, 0x16),       // Win+Shift+S
        Combo("通知中心", MOD_WIN, 0x04),                // Win+N
        Combo("最小化", MOD_WIN, 0x2D),                  // Win+M
    )

    /** 出厂预置快捷键（快捷键页「恢复默认」与首次启动的初始值） */
    val DEFAULTS: List<Combo> get() = ROW1 + ROW2 + ROW3

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
            "`" to 0x35, "\\" to 0x31, ";" to 0x33, "'" to 0x34, "," to 0x36, "." to 0x37, "/" to 0x38,
            "Insert" to 0x49, "Home" to 0x4A, "PageUp" to 0x4B, "Delete" to 0x4C,
            "End" to 0x4D, "PageDown" to 0x4E,
            "PrSc" to 0x46, "ScrLk" to 0x47, "Pause" to 0x48,
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
            "符号" to KEY_CHOICES.filter { it.first in listOf("-", "=", "`", "[", "]", ";", "'", ",", ".", "/") },
            "编辑键" to KEY_CHOICES.filter {
                it.first in listOf(
                    "Enter", "Esc", "Backspace", "Tab", "Space",
                    "Insert", "Delete", "Home", "End", "PageUp", "PageDown",
                    "PrSc", "ScrLk", "Pause",
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

    /**
     * Consumer 页（媒体）键的显示名：媒体键以**真实 Consumer usage** 落库（见 [CONSUMER_ROUTES]），
     * 不补名字的话 comboText 会显示成 0xB2 这类十六进制。
     */
    private val CONSUMER_NAMES: Map<Int, String> = mapOf(
        0xB2 to "播放/暂停", 0xE9 to "音量+", 0xEA to "音量-", 0xE2 to "静音",
    )

    /** usage → 显示名；不在清单内时退化为十六进制，保证不返回空串 */
    fun keyLabel(usage: Int): String =
        KEY_NAMES[usage] ?: CONSUMER_NAMES[usage] ?: "0x%02X".format(usage)

    /**
     * 存量坏 usage 修复表（v223.24）：快捷键候选此前用 `usageOf("F11")` 这类**多字符标签**
     * 反查，而 [usageOf] 只认单字符 —— 落到首字母的 usage（全屏→f、返回→b、音量→v、上→u…），
     * 用户存进 HotkeyStore 的就是这些坏值（真机表现「全屏发成 f、音量发成 v」）。
     * 按 (键面文字, 坏 usage) 精确对修成正确值；媒体键修成 Consumer usage，由 [combo] 路由。
     * HotkeyStore.load 每次读取时套用，幂等。
     */
    val USAGE_REPAIRS: Map<String, Pair<Int, Int>> = mapOf(
        "全屏" to (0x09 to 0x44),          // F11
        "返回" to (0x05 to 0x2A),          // Backspace
        "上" to (0x18 to 0x52),
        "下" to (0x07 to 0x51),
        "左" to (0x0F to 0x50),
        "右" to (0x15 to 0x4F),
        "Home" to (0x0B to 0x4A),
        "播放/暂停" to (0x13 to 0xB2),
        "音量+" to (0x19 to 0xE9),
        "音量-" to (0x19 to 0xEA),
        "静音" to (0x10 to 0xE2),
    )

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

    /**
     * Consumer 页（媒体）键路由：HotkeyStore 只能存键盘 Page(0x07) usage，媒体键借
     * **真实 Consumer Page usage**（0xB2 播放/暂停、0xE9/0xEA 音量、0xE2 静音）落库；
     * [combo] 命中即改走 Consumer 位图链路（与操控面/遥控页媒体键同一条 [HotkeyController]）。
     * 不分流的话 0xB2 会进键盘报告，对端认不出 —— 这是「音量/播放键按了没反应」的另一半根因。
     */
    private val CONSUMER_ROUTES: Map<Int, Int> = mapOf(
        0xB2 to HotkeyController.BIT_PLAY_PAUSE,
        0xE9 to HotkeyController.BIT_VOLUME_UP,
        0xEA to HotkeyController.BIT_VOLUME_DOWN,
        0xE2 to HotkeyController.BIT_MUTE,
    )

    /** 预组合键：按下 60ms 后自动全释放；媒体键（[CONSUMER_ROUTES]）改走 Consumer 位图 */
    fun combo(c: Combo) {
        CONSUMER_ROUTES[c.usage]?.let { bit ->
            HotkeyController.press(bit)
            handler.postDelayed({ HotkeyController.release(bit) }, 60)
            return
        }
        handler.removeCallbacks(releaseTask)
        heldKeys = LinkedHashMap() // 普通发送取代「按住」状态（旧 heldCombo 单槽置 null 的逐键记账等价写法）
        send(c.mod, intArrayOf(c.usage))
        handler.postDelayed(releaseTask, 60)
    }

    // —————————————————————————— 按住不放（长按发送） ——————————————————————————

    /**
     * 当前按住不放的键（issue#168 第 7 条：usage → 该键自带的修饰位）。
     *
     * 旧版只记「最后一颗按住的键」（heldCombo 单槽），快速打字两键交叠时：
     * 按 a（按住）→ 按 b（报告顶掉 a）→ 抬 a（全释放，b 也被放掉）→ 抬 b（无事可做）
     * —— b 的按下边沿被吞，对端少收一个键；修饰位还可能滞留成「系统功能键」观感。
     * 改成按 usage 记账：每颗键的按下 / 抬起各管各的，报告始终是
     * 「当前还按着的键 + 它们的修饰并集」。
     */
    @Volatile
    private var heldKeys = LinkedHashMap<Int, Int>()

    /**
     * 长按：把组合键**按住不放**（keydown 之后不释放），直到 [releaseHold] / [releaseNow]。
     * 用于需要持续按住的场景：按住 Ctrl 滚轮缩放、按住 Shift 连续多选、游戏里的持续键。
     */
    fun hold(c: Combo) {
        handler.removeCallbacks(releaseTask)
        heldKeys[c.usage] = c.mod
        sendHeld()
    }

    /**
     * 松开长按（幂等）。
     *
     * @param usage 指定要松开的那颗键：只把它从按住集合里摘掉，其余还按着的键保持按住
     *   —— 快速打字两键交叠时，先抬的那颗不再连带放掉后按的（第 7 条按键冲突的根因）。
     *   传 -1 = 不带语义的全释放（ComboChip 长按整组抬起等旧调用点沿用）。
     */
    fun releaseHold(usage: Int = -1) {
        if (usage >= 0) {
            if (heldKeys.remove(usage) == null) return
            if (heldKeys.isEmpty()) {
                handler.removeCallbacks(releaseTask)
                send(0, IntArray(0))
            } else {
                sendHeld()
            }
            return
        }
        if (heldKeys.isEmpty()) return
        heldKeys = LinkedHashMap()
        handler.removeCallbacks(releaseTask)
        send(0, IntArray(0))
    }

    /** 按「当前还按着的键」发全量报告：修饰取并集，最多 6 颗（HID 键盘报告上限） */
    private fun sendHeld() {
        var mods = 0
        val usages = ArrayList<Int>(heldKeys.size)
        for ((u, m) in heldKeys) {
            mods = mods or m
            usages.add(u)
        }
        // 同时按住超过 6 颗是极端场景（手柄式玩法），截断保发送不崩
        while (usages.size > 6) usages.removeAt(0)
        send(mods, usages.toIntArray())
    }

    /**
     * 立刻全释放，不等 60ms 的自动释放定时器（幂等）。
     *
     * 用途：**连发**。连发的节奏（屏上键盘 55ms、实体键盘跟系统自动重复约 50ms）
     * 与 [combo] / [tap] 的自动释放窗口（60ms）几乎相等 —— 同一个键在窗口内被再次按下时，
     * 报告内容与上一次完全相同，对端看不到新的按下边沿，于是长按只出第一个字符。
     * （issue #52 第三条：「实体键盘长按只能键入 2 个」，就是首次按下 + 首个自动重复
     * 各出一次，之后被 60ms 窗口吞掉。）
     * 每轮连发前先清一次，保证每轮都是一次真实的 按下 → 抬起。
     */
    fun releaseNow() {
        heldKeys = LinkedHashMap()
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

    /** 上档符号 → (修饰位, 底键 usage)：US 布局 Shift+数字/-/= 的标准产出（b16） */
    private val SHIFTED: Map<String, Pair<Int, Int>> = mapOf(
        "!" to (MOD_SHIFT to 0x1E), "@" to (MOD_SHIFT to 0x1F), "#" to (MOD_SHIFT to 0x20),
        "$" to (MOD_SHIFT to 0x21), "%" to (MOD_SHIFT to 0x22), "^" to (MOD_SHIFT to 0x23),
        "&" to (MOD_SHIFT to 0x24), "*" to (MOD_SHIFT to 0x25), "(" to (MOD_SHIFT to 0x26),
        ")" to (MOD_SHIFT to 0x27), "_" to (MOD_SHIFT to 0x2D), "+" to (MOD_SHIFT to 0x2E),
    )

    /**
     * 键面 label 发送入口（b16）：上档符号按 Shift+底键组合发送，其余透传
     * [usageByLabel] → [tap]。行表里的符号键必须走本函数 —— usageOf 对上档符号
     * 返回 0，直接 tap 会被静默忽略（「加了符号键没反应」的坑）。时序与 [tap] 同款：
     * 60ms 自动全释放、发完清 sticky（sticky Shift 与本表 Shift 叠加无害）。
     */
    fun tapLabel(label: String): Int {
        SHIFTED[label]?.let { (mod, usage) ->
            handler.removeCallbacks(releaseTask)
            send(sticky or mod, intArrayOf(usage))
            handler.postDelayed(releaseTask, 60)
            val used = sticky
            sticky = 0
            return used
        }
        return tap(usageByLabel(label))
    }

    /** 键面标签 → Keyboard Page (0x07) usage code；未识别返回 0 */
    fun usageOf(label: String): Int = when (label) {
        "Esc" -> 0x29; "Tab" -> 0x2B; "Enter" -> 0x28; "Space" -> 0x2C
        "⌫" -> 0x2A; "Del" -> 0x4C
        "-" -> 0x2D; "=" -> 0x2E; "[" -> 0x2F; "]" -> 0x30
        "`" -> 0x35; "\\" -> 0x31; ";" -> 0x33; "'" -> 0x34; "," -> 0x36; "." -> 0x37; "/" -> 0x38
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
     * 键盘三条真实链路：无线受控目标（9511，手机把同一套 (mod, keys) 语义经 APX1 控制帧上行，
     * 对端翻成 SendInput / evdev）、USB HID（rid 21）与蓝牙 HID（rid 2）。
     *
     * 历史坑（2026-10-23 核对源码修复）：这里长期写死 `btConnected = false`，理由注释停留在
     * "蓝牙只实现了鼠标与多媒体"的旧状态 —— 但 [com.allperiph.bt.BtHidDevice.reportKeyboard]
     * 自 v184 就绪（8 字节 mod+reserved+6 键）。结果是电脑配了蓝牙、触控板/多媒体/手柄都通，
     * 偏偏按键与复制粘贴等组合键发不出去。现改取真实连接状态并补上 BLUETOOTH 分支，
     * 判据仍只有 [Uplink.resolve] 一处。
     *
     * rid 21：[id, mod, 0, k1..k6]；keys 为空 = 全释放
     */
    private fun send(mod: Int, keys: IntArray) {
        val rt = AgentController.runtime
        val target = com.allperiph.wireless.ControlTarget
        val bt = AgentController.module(com.allperiph.core.ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice
        val path = Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
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
            Uplink.BLUETOOTH -> {
                // rid 2：reportKeyboard(data) = [mod, reserved=0, k1..k6]（8 字节，
                // Report ID 0x02 由 sendReport 的第二参负责，不进 data）。
                // keys 为空 = 全释放（含修饰键）；与 USB 路径同语义，释放时序仍由
                // releaseTask(60ms) / releaseHold() 驱动，这里只发当前状态、不自行补 up。
                Uplink.set(path)
                val b = ByteArray(keys.size.coerceIn(0, 6))
                for (i in b.indices) b[i] = keys[i].toByte()
                bt?.reportKeyboard(mod, b)
            }
            else -> {
                // 无出口：原先只 Log.w，界面完全看不出"为什么按键没反应"
                val first = Uplink.current != Uplink.NONE
                Uplink.set(
                    Uplink.NONE,
                    if (target.isControlling()) "受控目标已断开"
                    else "USB HID 未就绪、蓝牙未连接（/dev/hidg0 未挂载或 USB 口被调试占用）",
                )
                if (first) Log.w(TAG, "键盘无可用出口：USB 未挂载、蓝牙未连接、未选受控设备")
            }
        }
    }
}
