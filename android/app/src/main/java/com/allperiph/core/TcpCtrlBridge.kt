package com.allperiph.core

/**
 * Wi‑Fi 控制面出口（streamId=3）：**无蓝牙机器**上触控板 / 键盘 / 多媒体的输入承载。
 *
 * 分工与蓝牙/USB 两条线一致 —— 上层只认出口，不认底层是谁：
 * - 谁持有可用的 TCP 通道，谁就向本对象 [attach]（见 `wireless/TcpControlChannel`）；
 * - 链路断开时 [detach]，此后各 send 返回 false，调用方**如实降级**（只记日志），
 *   绝不伪装已送达（项目硬性原则）。
 *
 * 子命令编号与 `pc/host/src/wireless/wireless_link.cpp` 严格对应，改一边必须改另一边：
 * ```
 * 0x01 鼠标   [1]=buttons  [2]=dx(i8)  [3]=dy(i8)  [4]=wheel(i8)
 * 0x02 多媒体 [1..2]=u16 位图（LE）；按下/释放均发全量，PC 按位边沿注入
 * 0x03 键盘   [1]=mod     [2]=0        [3..8]=k1..k6（HID usage，页 0x07）
 * ```
 * 键盘字段与 USB 的 rid 21 报告同语义（修饰位图 1B + reserved 1B + 6 键），
 * 这样 PC 端无论从哪条链路收到，注入逻辑完全一致。
 */
object TcpCtrlBridge {

    /** 控制面承载：由 TCP 通道实现（组装 APX 帧并写出） */
    interface Sink {
        val ready: Boolean

        /** 发一条控制面本体（不含帧头/CRC），@return 是否成功写出 */
        fun sendControl(body: ByteArray): Boolean
    }

    private const val TAG = "TcpCtrlBridge"

    private const val CMD_MOUSE = 0x01
    private const val CMD_CONSUMER = 0x02
    private const val CMD_KEYBOARD = 0x03
    private const val CMD_TOUCH = 0x04

    @Volatile
    private var sink: Sink? = null

    fun attach(s: Sink) {
        sink = s
    }

    fun detach() {
        sink = null
    }

    fun ready(): Boolean = sink?.ready == true

    /** 发任意控制面本体（不含帧头/CRC）。供音频状态上报等扩展帧使用。 */
    fun sendControl(body: ByteArray): Boolean = emit(body)

    /** 鼠标相对位移：[0x01, buttons, dx, dy, wheel] */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): Boolean = emit(
        byteArrayOf(
            CMD_MOUSE.toByte(),
            buttons.toByte(),
            dx.toByte(), dy.toByte(), wheel.toByte(),
        )
    )

    /** 多媒体位图：[0x02, lo, hi]（边沿语义由 PC 端维护） */
    fun consumer(bitmap: Int): Boolean = emit(
        byteArrayOf(
            CMD_CONSUMER.toByte(),
            (bitmap and 0xFF).toByte(),
            ((bitmap ushr 8) and 0xFF).toByte(),
        )
    )

    /** 键盘：[0x03, mod, 0, k1..k6]；keys 为空 = 全释放 */
    fun keyboard(mod: Int, keys: IntArray): Boolean {
        val p = ByteArray(9)
        p[0] = CMD_KEYBOARD.toByte()
        p[1] = mod.toByte()
        for (i in 0 until minOf(keys.size, 6)) p[3 + i] = keys[i].toByte()
        return emit(p)
    }

    /**
     * 副屏触摸（绝对坐标）：[0x04, action, buttons, x_lo, x_hi, y_lo, y_hi, pointer, 0]。
     * 坐标按手机画面区域归一化 0..65535；action：0=down 1=up 2=move 3=cancel。
     * 触摸是 60 帧/秒的小帧，走**常连的 9500 控制通道**（9502 媒体通道仅在
     * 推流/音箱时才建立，不能作为触摸的承载 —— 实测踩过）。
     */
    fun touch(action: Int, buttons: Int, x: Int, y: Int, pointerId: Int = 0): Boolean {
        val p = ByteArray(9)
        p[0] = CMD_TOUCH.toByte()
        p[1] = action.toByte()
        p[2] = buttons.toByte()
        p[3] = (x and 0xFF).toByte()
        p[4] = ((x shr 8) and 0xFF).toByte()
        p[5] = (y and 0xFF).toByte()
        p[6] = ((y shr 8) and 0xFF).toByte()
        p[7] = (pointerId and 0xFF).toByte()
        p[8] = 0
        return emit(p)
    }

    private fun emit(body: ByteArray): Boolean {
        val s = sink
        if (s == null || !s.ready) {
            warn("无可用 TCP 控制通道", body)
            return false
        }
        val ok = s.sendControl(body)
        if (!ok) warn("控制帧写出失败", body)
        return ok
    }

    /** 降频告警：高频手势下不能每帧打日志，但也不能静默丢（项目硬性原则：如实降级） */
    @Volatile
    private var lastWarnAt = 0L

    private fun warn(what: String, body: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastWarnAt < 1000) return
        lastWarnAt = now
        Log.w(TAG, "$what：cmd=0x%02X sink=${sink != null} ready=${sink?.ready}".format(body[0]))
    }
}
