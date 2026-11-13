package com.allperiph.shared.input

import com.allperiph.shared.util.Log

/**
 * **虚拟键盘**（uinput 内核级，原生实现见 `cpp/apx_uinput.c`，与 [UinputMouse]/[UinputGamepad] 同族）。
 *
 * 组合键的唯一正解：root 的 `input keyevent` 自带 down+up（修饰状态留不住）、无障碍注不了
 * 任意按键、evdev 又常因 `/dev/input/event*` 权限不可用 —— uinput 直接新建一只内核键盘设备：
 * 修饰键（Ctrl/Shift/Alt/Meta）与 F1-F12 以独立 down/up 事件进出，内核据此合成 META_* 状态，
 * Ctrl+C / Shift+Tab 这类组合键从此有真实语义（这正是此前「组合键无解」的根子）。
 *
 * 范围刻意收窄成**最小集**：修饰键 + F 键 + 字符（[sendChar]）。
 *  - 表外键（返回/主页/方向键/电源等）[sendKey] 返回 false，调用方落回原通道 —— 已验证的行为一个不碰；
 *  - 裸打字仍走输入法文本通道（中英文最稳、无焦点也能上屏），只有**修饰键按住时**的字符才由
 *    调用方改经 [sendChar] 下发真键盘 —— Ctrl+V / Shift+A 这类组合键才有意义。
 *
 * 与 [UinputMouse] 同款约定：拿不到 `/dev/uinput`（权限）时 [ready] 为 false，调用方退回原通道；
 * 有 root 时会顺手 `chmod 666 /dev/uinput` 再重试一次；设备名含 apx/allperiph 供 evdev 扫描类
 * 工具排除，防止自激；手机端原生库名为 `apx`，TV 端为 `apxtv` —— 加载时都试一遍。
 */
object UinputKeyboard {

    private const val TAG = "UinputKeyboard"

    /**
     * 安卓键码 → Linux 键码。只收组合键需要的最小集（修饰键 + F1-F12）；
     * 键码与 `ControlServer.MOD_KEYCODE`（HID mod 位折出的八个）一一对应。
     */
    private val ANDROID_TO_LINUX: Map<Int, Int> = buildMap {
        // 修饰键：独立 down/up 是组合键语义的根基
        put(113, 29)    // CTRL_LEFT   → KEY_LEFTCTRL
        put(59, 42)     // SHIFT_LEFT  → KEY_LEFTSHIFT
        put(57, 56)     // ALT_LEFT    → KEY_LEFTALT
        put(117, 125)   // META_LEFT   → KEY_LEFTMETA
        put(114, 97)    // CTRL_RIGHT  → KEY_RIGHTCTRL
        put(60, 54)     // SHIFT_RIGHT → KEY_RIGHTSHIFT
        put(58, 100)    // ALT_RIGHT   → KEY_RIGHTALT
        put(118, 126)   // META_RIGHT  → KEY_RIGHTMETA
        // F1-F12：Linux 侧 F1-F10 连号（59-68），F11/F12 在 87/88
        for (i in 0 until 10) put(131 + i, 59 + i)
        put(141, 87)    // F11 → KEY_F11
        put(142, 88)    // F12 → KEY_F12
    }

    private val loaded: Boolean = try {
        // 手机端原生库名为 apx，TV 端为 apxtv —— 都试一遍，谁加载成功用谁
        System.loadLibrary("apx")
        true
    } catch (t: Throwable) {
        try {
            System.loadLibrary("apxtv")
            true
        } catch (t2: Throwable) {
            Log.w(TAG, "加载 libapx.so / libapxtv.so 失败（${t.message}）→ 虚拟键盘不可用，按键走原通道")
            false
        }
    }

    @Volatile
    var ready: Boolean = false
        private set

    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本设备当前按住的 Linux 键码（供 [releaseAll] 断连时收尾，防止修饰键/字母卡住） */
    private val pressed = HashSet<Int>()

    /** 创建虚拟键盘。要在**后台线程**调用（有文件 IO）。幂等。 */
    fun tryStart(): Boolean {
        if (ready) return true
        if (!loaded) return false
        if (!starting.compareAndSet(false, true)) return ready
        try {
            if (openKeyboard()) {
                ready = true
            } else if (RootInput.available && RootInput.run("chmod 666 /dev/uinput")) {
                // 很多设备该节点是 0660 root:input；既然有 root，就别卡在权限上
                ready = openKeyboard()
            }
            if (ready) Log.i(TAG, "虚拟键盘已就绪：修饰键/F 键为内核级真实输入，组合键可用")
            return ready
        } catch (t: Throwable) {
            Log.w(TAG, "创建虚拟键盘异常：${t.message}")
            return false
        } finally {
            starting.set(false)
        }
    }

    /**
     * 注入按键的按下/松开（安卓键码）。表外键返回 false，调用方走原通道 ——
     * 收窄范围正是为了不改变任何已验证按键的既有路径。
     */
    fun sendKey(androidKeyCode: Int, down: Boolean): Boolean {
        if (!ready) return false
        val code = ANDROID_TO_LINUX[androidKeyCode] ?: return false
        return emit(code, down)
    }

    /**
     * 字符 → 真按键（字母/数字/空格），供「修饰键按住」时由调用方改走真键盘。
     * Shift 状态由真实修饰键（KEY_LEFTSHIFT）持有，大小写与 Shift 组合自然正确。
     */
    fun sendChar(ch: Char, down: Boolean): Boolean {
        if (!ready) return false
        val code = when {
            ch in 'a'..'z' -> 30 + (ch - 'a')     // KEY_A
            ch in 'A'..'Z' -> 30 + (ch - 'A')
            ch in '1'..'9' -> 2 + (ch - '1')      // KEY_1..KEY_9
            ch == '0' -> 11                        // KEY_0
            ch == ' ' -> 57                        // KEY_SPACE
            else -> return false
        }
        return emit(code, down)
    }

    /**
     * 抬起本设备按住的所有键（断连/停用时调用）。
     * 断连时对端未必按序发抬起帧，这里兜底收尾，防止修饰键/字母在内核侧卡住。
     */
    @Synchronized
    fun releaseAll() {
        if (!ready || pressed.isEmpty()) return
        for (code in pressed) {
            runCatching { sendKeyboardKey(code, false) }
        }
        pressed.clear()
    }

    /** 底层发一次 EV_KEY（含按下/抬起状态登记）；@Synchronized 防多线程交错 down/up */
    @Synchronized
    private fun emit(code: Int, down: Boolean): Boolean = runCatching {
        sendKeyboardKey(code, down)
        if (down) pressed.add(code) else pressed.remove(code)
        true
    }.getOrDefault(false)

    // ————————————————————————————— native —————————————————————————————
    private external fun openKeyboard(): Boolean
    private external fun closeKeyboard()
    private external fun sendKeyboardKey(key: Int, down: Boolean)
}
