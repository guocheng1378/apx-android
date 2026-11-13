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

    /**
     * 内核光标锚定完成后的回调（由 [TvInjector] 在 init 时注册，避免依赖环）。
     *
     * 为什么要锚定：REL 鼠标的位置只能靠事件累积，Android 给虚拟鼠标分配的初始光标
     * 位置在**屏幕中心**，而逻辑坐标（TvInjector.cursorX/Y）预置在**右上角内缩一格** ——
     * 两套坐标天然错开半屏。此后所有「绝对定位」操作（tapAt / 长按 / 触摸帧的落点）
     * 都是把逻辑目标换算成 REL 差值发给内核，内核光标于是永远停在「目标 + 半屏偏差」
     * 上，点下去的位置和用户看到的位置对不上 —— 用户症状「手机控手机不能点击」。
     * 锚定：大负位移把内核光标顶到 (0,0)，回调把逻辑坐标同步归零，两端从此同基准。
     */
    @Volatile
    var onAnchored: (() -> Unit)? = null

    /** 创建虚拟鼠标。要在**后台线程**调用（有文件 IO）。幂等。 */
    fun tryStart(): Boolean {
        if (ready) return true
        if (!loaded) return false
        if (!starting.compareAndSet(false, true)) return ready
        try {
            if (openMouse()) {
                ready = true
                // 只有「真正新建」这一次会走到这里：ready=false 时内核 fd 必然是关的
                //（进程存活期间 openMouse 成功后 ready 一直为 true，不会重复锚定）
                anchorToOrigin()
            } else if (RootInput.available && RootInput.run("chmod 666 /dev/uinput")) {
                // 很多设备该节点是 0660 root:input；既然有 root，就别卡在权限上
                ready = openMouse()
                if (ready) anchorToOrigin()
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
     * 内核光标锚到左上角 (0,0)：分 64 步各发 -256 的 REL 位移（合计 -16384，任何屏幕
     * 都够）。不一次发完是防个别 ROM 对超大单步位移做截断/平滑；每步自带 SYN，几毫秒
     * 内完成。结束后回调 [onAnchored] 把逻辑坐标归零 —— 两套坐标自此同步。
     */
    private fun anchorToOrigin() {
        runCatching {
            // 稍等 InputReader 认领新设备：UI_DEV_CREATE 返回不代表内核指针服务已就位，
            // 发得太早锚定事件会被丢掉，光标仍从屏幕中心起步
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            repeat(64) {
                sendRel(REL_X, -256)
                sendRel(REL_Y, -256)
            }
            Log.i(TAG, "内核光标已锚定到左上角 (0,0)")
        }
        onAnchored?.invoke()
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
