package com.allperiph.controlled

import com.allperiph.core.Log

/**
 * **虚拟手柄**（uinput 内核级，原生实现见 `cpp/apx_uinput.c`，与 TV 端同一份 C）。
 *
 * 手柄摇杆只有这一条路能给出来：
 *  - **evdev 通道给不出摇杆** —— 它冒充的是已存在的输入设备，而本机键盘设备只有
 *    `EV_KEY/EV_REL`，**没有 ABS 轴**；
 *  - **root 的 `input` 命令没有摇杆语义**；
 *  - `InputManager.injectInputEvent` 需要 `INJECT_EVENTS`（系统签名级）。
 *
 * `/dev/uinput` 能**新建一个内核输入设备**：声明按键 + 双摇杆 ABS 轴后，系统就把它当真手柄。
 *
 * 手机上的注意点：`/dev/uinput` 通常是 `0660 root:input`，普通 App 打不开 ——
 * 所以**有 root 时先 `chmod 666 /dev/uinput` 再重试**（本模块 minSdk=34，root 场景常见）。
 * 没 root 且节点不可写时 [ready] 为 false，调用方退回「按钮走 evdev/root、摇杆不可用」。
 */
object UinputGamepad {

    private const val TAG = "UinputGamepad"

    private const val ABS_X = 0x00
    private const val ABS_Y = 0x01
    private const val ABS_RX = 0x03
    private const val ABS_RY = 0x04

    /** 手柄位 → Linux `BTN_*`，位序与 [TvInjector] 的 GAMEPAD_KEYCODES 严格一致 */
    private val BTN_OF_BIT = intArrayOf(
        0x130, // 0  BTN_A
        0x131, // 1  BTN_B
        0x133, // 2  BTN_X
        0x134, // 3  BTN_Y
        0x136, // 4  BTN_TL
        0x137, // 5  BTN_TR
        0x138, // 6  BTN_TL2
        0x139, // 7  BTN_TR2
        0x13A, // 8  BTN_SELECT
        0x13B, // 9  BTN_START
        0x132, // 10 BTN_C
        0x135, // 11 BTN_Z
        0x13C, // 12 BTN_MODE
        0x13D, // 13 BTN_THUMBL
        0x13E, // 14 BTN_THUMBR
        0x000, // 15 未使用
    )

    private val loaded: Boolean = try {
        System.loadLibrary("apx")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "加载 libapx.so 失败（${t.message}）→ 摇杆不可用，手柄按钮仍走 evdev / root")
        false
    }

    @Volatile
    var ready: Boolean = false
        private set

    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    private var lastAxes = IntArray(4) { Int.MIN_VALUE }

    /** 创建虚拟手柄。要在**后台线程**调用。幂等。 */
    fun tryStart(): Boolean {
        if (ready) return true
        if (!loaded) return false
        if (!starting.compareAndSet(false, true)) return ready
        try {
            if (openGamepad()) {
                ready = true
            } else if (RootInput.available && RootInput.run("chmod 666 /dev/uinput")) {
                ready = openGamepad()
            }
            if (ready) Log.i(TAG, "虚拟手柄已就绪：摇杆与手柄按钮均为内核级真实输入")
            return ready
        } catch (t: Throwable) {
            Log.w(TAG, "创建虚拟手柄异常：${t.message}")
            return false
        } finally {
            starting.set(false)
        }
    }

    fun button(bit: Int, down: Boolean) {
        if (!ready) return
        val btn = BTN_OF_BIT.getOrNull(bit) ?: return
        if (btn == 0) return
        runCatching { sendButton(btn, down) }
    }

    fun sticks(x: Int, y: Int, rx: Int, ry: Int) {
        if (!ready) return
        val want = intArrayOf(x, y, rx, ry)
        val axes = intArrayOf(ABS_X, ABS_Y, ABS_RX, ABS_RY)
        runCatching {
            for (i in 0..3) {
                if (want[i] == lastAxes[i]) continue
                sendAxis(axes[i], want[i].coerceIn(-128, 127))
                lastAxes[i] = want[i]
            }
        }
    }

    fun stop() {
        if (!loaded || !ready) return
        ready = false
        lastAxes = IntArray(4) { Int.MIN_VALUE }
        runCatching { closeGamepad() }
    }

    // ————————————————————————————— native —————————————————————————————
    private external fun openGamepad(): Boolean
    private external fun closeGamepad()
    private external fun sendButton(btn: Int, down: Boolean)
    private external fun sendAxis(axis: Int, value: Int)
}
