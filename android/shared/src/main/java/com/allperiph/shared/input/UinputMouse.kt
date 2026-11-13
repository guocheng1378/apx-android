package com.allperiph.shared.input

import com.allperiph.shared.util.Log

/**
 * **虚拟鼠标**（uinput 内核级，原生实现见 `cpp/apx_uinput.c`）。
 *
 * 「手机控手机」场景下，触控板的光标移动此前只能靠蓝点浮层模拟 —— 被控端屏幕上
 * 看不到系统原生光标。有了 uinput 虚拟鼠标，被控端会**新建一个内核鼠标设备**：
 * 写 REL_X/REL_Y 相对位移，系统原生光标就真的会动（与插一只 USB 鼠标同一条通路）。
 *
 * 与 [UinputGamepad] 同款约定：
 *  - 拿不到 `/dev/uinput`（权限）时 [ready] 为 false，调用方退回蓝点浮层，不会有任何异常；
 *    有 root 时会顺手 `chmod 666 /dev/uinput` 再重试一次；
 *  - 设备名 "APX Virtual Mouse" —— evdev 扫描类工具按名字排除含 apx/allperiph
 *    的设备，防止把自己当成真实鼠标产生自激；
 *  - 手机端原生库名为 `apx`，TV 端为 `apxtv` —— 加载时都试一遍。
 *
 * 混合方案：**移动走真鼠标**（REL），点击/长按/滚动仍走原有的无障碍优先注入通道
 * （长按→上下文菜单、滚动→swipe 是触控板既有体验，纯鼠标映射会丢长按语义）。
 */
object UinputMouse {

    private const val TAG = "UinputMouse"

    /** Linux 输入常量（与 linux/input.h 一致；这里不引 native 头，避免 Kotlin 侧依赖） */
    private const val REL_X = 0x00
    private const val REL_Y = 0x01
    private const val REL_WHEEL = 0x08
    private const val REL_HWHEEL = 0x09

    private const val BTN_LEFT = 0x110
    private const val BTN_RIGHT = 0x111
    private const val BTN_MIDDLE = 0x112

    private val loaded: Boolean = try {
        // 手机端原生库名为 apx，TV 端为 apxtv —— 都试一遍，谁加载成功用谁
        System.loadLibrary("apx")
        true
    } catch (t: Throwable) {
        try {
            System.loadLibrary("apxtv")
            true
        } catch (t2: Throwable) {
            Log.w(TAG, "加载 libapx.so / libapxtv.so 失败（${t.message}）→ 虚拟鼠标不可用，移动仍走蓝点浮层")
            false
        }
    }

    @Volatile
    var ready: Boolean = false
        private set

    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 创建虚拟鼠标。要在**后台线程**调用（有文件 IO）。幂等。 */
    fun tryStart(): Boolean {
        if (ready) return true
        if (!loaded) return false
        if (!starting.compareAndSet(false, true)) return ready
        try {
            if (openMouse()) {
                ready = true
            } else if (RootInput.available && RootInput.run("chmod 666 /dev/uinput")) {
                // 很多设备该节点是 0660 root:input；既然有 root，就别卡在权限上
                ready = openMouse()
            }
            if (ready) Log.i(TAG, "虚拟鼠标已就绪：被控端系统光标为内核级真实输入")
            return ready
        } catch (t: Throwable) {
            Log.w(TAG, "创建虚拟鼠标异常：${t.message}")
            return false
        } finally {
            starting.set(false)
        }
    }

    /**
     * 相对位移移动系统光标（REL_X/REL_Y）。dx/dy 为像素增量；只在非零时发，
     * 避免每帧都空发一次 SYN。
     */
    fun move(dx: Int, dy: Int) {
        if (!ready) return
        if (dx == 0 && dy == 0) return
        runCatching {
            if (dx != 0) sendRel(REL_X, dx)
            if (dy != 0) sendRel(REL_Y, dy)
        }
    }

    /** 滚轮：notch 正=向上、负=向下（一次可带多格；合并/节流由调用方负责） */
    fun wheel(notches: Int) {
        if (!ready || notches == 0) return
        runCatching { sendRel(if (notches > 0) REL_WHEEL else -REL_WHEEL, notches) }
    }

    /** 鼠标按键：0=左键 1=右键 2=中键，down=true 按下（TvInjector 点击/长按/拖拽现走此通道） */
    fun button(which: Int, down: Boolean) {
        if (!ready) return
        val btn = when (which) {
            0 -> BTN_LEFT
            1 -> BTN_RIGHT
            2 -> BTN_MIDDLE
            else -> return
        }
        runCatching { sendMouseButton(btn, down) }
    }

    // ————————————————————————————— native —————————————————————————————
    private external fun openMouse(): Boolean
    private external fun closeMouse()
    private external fun sendRel(code: Int, value: Int)
    private external fun sendMouseButton(btn: Int, down: Boolean)
}
