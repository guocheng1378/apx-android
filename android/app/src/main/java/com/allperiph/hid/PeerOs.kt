package com.allperiph.hid

import android.content.Context
import com.allperiph.R

/**
 * 被控机系统（issue#168 第 14 条「根据安卓、Linux、Windows 和 macOS 做 4 套适配」）。
 *
 * 手机端发送的是**标准 USB HID Keyboard Page (0x07) 组合键**，对端系统各按各的惯例解释：
 * 同一个「复制」动作，Windows/Linux/Android 吃 Ctrl+C，macOS 只认 Cmd+C（HID GUI 位，
 * 即 Mac 物理键盘的 ⌘ 键）；「刷新 F5」「Alt+F4」「Win+E」等 Windows 专属键在 macOS
 * 上要么无动作、要么语义错位。本枚举给出**发送侧的目标系统**，由 [adapt] 把内置
 * 快捷键行（[HidKeys.DEFAULTS]）与模板组合键（[HotkeyTemplates.ALL]）翻成该系统认的组合。
 *
 * 四套口径（成对系统约定相同就复用，不为凑数硬造差异——少改动规矩）：
 * - [WINDOWS]：基线，所有内置行/模板的原生定义口径，原样发送；
 * - [LINUX]：与 Windows 同款——Super 即 [HidKeys.MOD_WIN] 的同一 usage，F5/Alt+F4
 *   在主流桌面（GNOME/KDE）行为一致，不改写；
 * - [ANDROID]：被控端多为第三方桌面/TV，Ctrl 系组合键与 Windows 通用，不改写；
 * - [MACOS]：唯一需要换键的一套——单 Ctrl 系组合键 Ctrl→⌘（[HidKeys.MOD_WIN] 位），
 *   Windows 专属系统键按 macOS 惯例逐键特判（见 [MAC_SPECIALS]）。
 *
 * 选择入口在设置页「快捷键模板」分组的「被控机系统」下拉（[current] / [setCurrent]）。
 * 默认 [WINDOWS]（存量行为不变）：老用户不碰这个设置，快捷键一切照旧。
 */
enum class PeerOs {
    WINDOWS, MACOS, LINUX, ANDROID;

    companion object {
        private const val PREF = "apx_hid"
        private const val KEY_OS = "peer_os"

        /** 设备档案里的字段名（与 DeviceProfiles.FIELD_OS 同值，避免跨包可见性纠缠） */
        private const val FIELD_OS = "os"

        /** 设置页下拉的四套系统名（纯拉丁专名，不走中文文案棘轮） */
        fun labels(ctx: Context): List<String> =
            listOf("Windows", "macOS", "Linux", "Android")

        fun fromIndex(i: Int): PeerOs = entries[i.coerceIn(0, entries.size - 1)]

        fun indexOf(os: PeerOs): Int = entries.indexOf(os)

        /** 当前选中的被控机系统；**设备档案优先**（2026-11-14 成宇「对应切换」批）——
         *  连着哪台受控设备就用它自己的档案，没连设备 / 该设备没存过 = 全局键（存量行为不变）
         *
         *  2026-11-14 追加（成宇「软件有没有识别对端手机并映射键盘」）：信标类型为手机
         *  （ControlTarget.type == "phone"）且该设备没存过系统档案时，自动按 [ANDROID]
         *  适配 —— 手机被控端没有 Win/桌面/注册表那套语义，照 Windows 口径发只会发出
         *  无意义的组合；用户显式存过档案（intReadInt 命中）时尊重档案，不被覆盖。 */
        fun current(ctx: Context): PeerOs {
            com.allperiph.wireless.DeviceProfiles.readInt(ctx, FIELD_OS)?.let {
                return entries[it.coerceIn(0, entries.size - 1)]
            }
            if (com.allperiph.wireless.ControlTarget.type == "phone") return ANDROID
            return entries[ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(KEY_OS, 0).coerceIn(0, entries.size - 1)]
        }

        fun setCurrent(ctx: Context, os: PeerOs) {
            val idx = indexOf(os)
            // 连着受控设备 → 写进该设备的档案（连上谁自动换回谁的系统）；
            // 没连设备 → 写全局键（存量行为不变）。档案是叠加层，两种场景互不污染。
            if (com.allperiph.wireless.DeviceProfiles.activePeer() != null) {
                com.allperiph.wireless.DeviceProfiles.writeInt(ctx, FIELD_OS, idx)
            } else {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_OS, idx).apply()
            }
        }

        /** 当前系统下的出厂预置快捷键（「恢复默认」与未自定义时的快捷键条共用） */
        fun defaults(ctx: Context): List<HidKeys.Combo> =
            current(ctx).adapt(HidKeys.DEFAULTS)
    }

    /**
     * 把一组组合键翻成本系统认的组合。
     * 非 macOS 四套里三套与 Windows 惯例一致（见类注释），原样返回——**不改写**是刻意的，
     * 无谓换键反而让 Linux/Android 用户拿到不认识的组合。
     */
    fun adapt(list: List<HidKeys.Combo>): List<HidKeys.Combo> =
        if (this == MACOS) list.map(::macCombo) else list

    /**
     * 单条组合键的 macOS 翻译。
     *
     * 通用规则（先特判、后通转，特判优先）：
     * 1. [MAC_SPECIALS] 按 (label, 原mod, 原usage) 精确命中 → 直接换键（Windows 系统键
     *    在 macOS 上无「同 usage 等价物」，必须按惯例重指——F5→⌘R、Win+E→聚焦、Alt+Tab→⌘Tab 等）；
     * 2. 通用规则兜底其余组合键（模板里大量 Ctrl+X 编辑键）：
     *    - 含 Win 位 → Win 换成 ⌘（macOS 的 Win 物理位置就是 ⌘，HID GUI 位同源）；
     *    - 否则含 Ctrl 位 → Ctrl 换成 ⌘（Ctrl+C→⌘C、Ctrl+Shift+T→⌘⇧T、Ctrl+R→⌘R）；
     *    - Alt/Shift 原样保留（Alt 在 mac 上是 Option，语义多数仍通）。
     * label 不动：中文动作名（复制/粘贴/刷新）跨系统含义一致，改的是**键**不是**名**。
     */
    private fun macCombo(c: HidKeys.Combo): HidKeys.Combo {
        MAC_SPECIALS["${c.label}|${c.mod}|${c.usage}"]?.let { (mod, usage) ->
            return c.copy(mod = mod, usage = usage)
        }
        var mod = c.mod
        if (mod and HidKeys.MOD_WIN != 0) {
            // Win 位即 GUI 位（macOS 的 Win 物理位置就是 ⌘，usage 0x07 同源）——原样发送即可
            return c.copy(mod = mod, usage = c.usage)
        }
        if (mod and HidKeys.MOD_CTRL != 0) {
            mod = (mod and HidKeys.MOD_CTRL.inv()) or HidKeys.MOD_WIN
        }
        return c.copy(mod = mod, usage = c.usage)
    }

    /**
     * macOS 特判表：key = "label|原mod|原usage"，value = (新mod, 新usage)。
     * 仅覆盖 Windows 专属系统键——在 macOS 上直接发原键要么无动作（Win+L）、
     * 要么是另一个系统的语义（F5 代码刷新/查找），逐键改指 macOS 惯例：
     */
    private val MAC_SPECIALS: Map<String, Pair<Int, Int>> = buildMap {
        // 切窗 Alt+Tab → ⌘Tab（macOS App 切换器）
        put("切窗|${HidKeys.MOD_ALT}|0x2B", HidKeys.MOD_WIN to 0x2B)
        // 关闭 Alt+F4 → ⌘W（关窗口）
        put("关闭|${HidKeys.MOD_ALT}|0x3D", HidKeys.MOD_WIN to 0x1A)
        // 桌面 Win+D → ⌘F3（显示桌面；F11=0x44）
        put("桌面|${HidKeys.MOD_WIN}|0x07", HidKeys.MOD_WIN to 0x44)
        // 资源 Win+E → ⌘⌥Space（聚焦搜索窗口，文件/应用直达）
        put("资源|${HidKeys.MOD_WIN}|0x08", (HidKeys.MOD_WIN or HidKeys.MOD_ALT) to 0x2C)
        // 锁屏 Win+L → ⌃⌘Q（macOS 锁屏标准组合；Q=0x14）
        put("锁屏|${HidKeys.MOD_WIN}|0x0F", (HidKeys.MOD_CTRL or HidKeys.MOD_WIN) to 0x14)
        // 任务 Ctrl+Shift+Esc（任务管理器）→ ⌘⌥Esc（强制退出窗口）
        put("任务|${HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT}|0x29", (HidKeys.MOD_WIN or HidKeys.MOD_ALT) to 0x29)
        // 刷新 F5 → ⌘R（浏览器刷新；R=0x15）
        put("刷新|0|0x3E", HidKeys.MOD_WIN to 0x15)
        // 任务视图 Win+Tab → ⌃↑（Mission Control；↑=0x52）
        put("任务视图|${HidKeys.MOD_WIN}|0x2B", HidKeys.MOD_CTRL to 0x52)
        // 截图 Win+Shift+S → ⌘⇧4（截选区；4=0x21）
        put("截图|${HidKeys.MOD_WIN or HidKeys.MOD_SHIFT}|0x16", (HidKeys.MOD_WIN or HidKeys.MOD_SHIFT) to 0x21)
        // 通知中心 Win+N → ⌘Space（聚焦；macOS 无对应快捷键，落一个高频动作不空转）
        put("通知中心|${HidKeys.MOD_WIN}|0x11", HidKeys.MOD_WIN to 0x2C)
        // 最小化 Win+M → ⌘M（M=0x10）——通用规则已覆盖，显式落表保文档性
        // （usage 笔误随 HidKeys.ROW3 一并修正：旧键 0x2D 在 Mac 上翻译成 ⌘S，会覆盖文档）
        put("最小化|${HidKeys.MOD_WIN}|0x10", HidKeys.MOD_WIN to 0x10)
    }
}
