package com.allperiph.touchpad

import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 手机当触控板：相对鼠标语义（与绝对坐标的 Digitizer 不同，架构 §4）。
 * 上行路径按模式分流：
 *   - 无线模式：蓝牙 HID 免驱（首选）/ TCP bulk 兜底
 *   - 有线模式：HID Mouse TLC（复用 Report ID 2）/ bulk 兜底
 * 手势在手机端识别（GestureEngine），只上报增量以保证跟手。
 *
 * 注意：本文件为**评审级实现**（无真机）；上行出口在此处择路，真实发送待联调。
 */
class TouchpadModule : Module {
    override val id: String = ModuleId.TOUCHPAD
    @Volatile override var state: ModuleState = ModuleState.IDLE
    private val engineRef = java.util.concurrent.atomic.AtomicReference<GestureEngine?>(null)

    /** v1.7d：start 时持有的 ModuleContext（跨模块震动等用） */
    @Volatile private var ctxRef: ModuleContext? = null

    // ---------------- v1.8：Windows Precision Touchpad（PTP）模式 ----------------
    // ON：MotionEvent 原始多指数据直接打包 Report 16，手势全部由 Windows 系统合成。
    // OFF：走 GestureEngine 手搓手势 → HID 鼠标（兼容回退）。
    // 常量与 shared/include/apx/hid_layout.h 的 kPtp* 一一对应（Kotlin 侧无绑定）。
    @Volatile var ptpMode: Boolean = false
    @Volatile private var surfW: Int = 1260
    @Volatile private var surfH: Int = 2848

    // ---------------- v1.9：数位屏触控（Digitizer 绝对坐标，Report ID 3）----------------
    // ON：手机屏幕当作 PC 触摸屏——手指位置归一化 0..65535 绝对上报，
    //     PC 光标瞬移到对应位置（数位屏/触摸屏语义，架构 §4）。
    // OFF：鼠标模拟（GestureEngine 相对位移）。
    @Volatile var digitizerMode: Boolean = false

    /** MotionEvent → Digitizer 报告（RID 3，102B：header 12 + 10×contact 8 + pen 10） */
    private fun sendDigitizerReport(ctx: ModuleContext, tip: Boolean, x: Float, y: Float) {
        val b = ByteArray(102)
        b[0] = 3
        b[1] = (if (tip) 0x02 else 0).toByte()   // bit1=TipSwitch（InRange bit0 常置 1）
        b[2] = if (tip) 1 else 0                 // contactCount
        val ns = android.os.SystemClock.elapsedRealtimeNanos()
        for (i in 0..7) b[4 + i] = ((ns shr (8 * i)) and 0xFF).toByte()  // tsNs LE
        if (tip) {
            val w = surfW.toFloat(); val h = surfH.toFloat()
            val x16 = ((x / w) * 65535).toInt().coerceIn(0, 65535)
            val y16 = ((y / h) * 65535).toInt().coerceIn(0, 65535)
            val off = 12                          // contact[0]
            b[off + 2] = (x16 and 0xFF).toByte(); b[off + 3] = ((x16 shr 8) and 0xFF).toByte()
            b[off + 4] = (y16 and 0xFF).toByte(); b[off + 5] = ((y16 shr 8) and 0xFF).toByte()
            b[off + 6] = 0x7F; b[off + 7] = 0x7F  // pressure 0x7FFF（手指）
        }
        ctx.hid.sendInputReport(b)
    }

    /** Digitizer 模式事件入口（tip 抬起发空帧让 PC 光标释放） */
    private fun feedDigitizer(ev: android.view.MotionEvent) {
        val ctx = ctxRef ?: return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 1)
                    sendDigitizerReport(ctx, true, ev.getX(0), ev.getY(0))
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL ->
                sendDigitizerReport(ctx, false, 0f, 0f)
        }
    }

    fun setTouchSurface(w: Int, h: Int) { surfW = w; surfH = h }

    private val activeIds = HashMap<Int, Int>()   // MotionEvent pointerId → contactId(0..3)
    private var nextContactId = 0

    /** PTP 触点快照（帧流线程消费） */
    private class Contact(val cid: Int, var x: Float, var y: Float)
    private val contacts = java.util.concurrent.ConcurrentHashMap<Int, Contact>()
    private var ptpThread: Thread? = null

    /** MotionEvent → 更新触点快照（帧流线程周期发送） */
    private fun updatePtpContacts(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex.coerceIn(0, ev.pointerCount - 1)
                val pid = ev.getPointerId(i)
                if (!contacts.containsKey(pid) && contacts.size < 5) {
                    contacts[pid] = Contact(nextContactId, ev.getX(i), ev.getY(i))
                    nextContactId = (nextContactId + 1) and 0x03
                }
            }
            android.view.MotionEvent.ACTION_UP -> contacts.clear()
            android.view.MotionEvent.ACTION_POINTER_UP -> contacts.remove(ev.getPointerId(ev.actionIndex))
        }
        for (i in 0 until ev.pointerCount) {
            contacts[ev.getPointerId(i)]?.let { c ->
                c.x = ev.getX(i); c.y = ev.getY(i)
            }
        }
    }

    /** v1.8：8ms 周期帧流（PTP 设备标准行为：持续上报，含无触摸的空帧） */
    fun startPtpStream() {
        if (ptpThread?.isAlive == true) return
        ptpThread = Thread {
            while (ptpMode && !Thread.currentThread().isInterrupted) {
                val ctx = ctxRef
                if (ctx != null && ctx.hid.isReady()) sendPtpReport(ctx)
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopPtpStream() {
        ptpThread?.interrupt()
        ptpThread = null
    }

    /** 把当前触点快照打包为 PTP 50B 输入报告（Report ID 16） */
    private fun sendPtpReport(ctx: ModuleContext) {
        val bytes = ByteArray(PTP_REPORT_SIZE)
        bytes[0] = PTP_REPORT_ID.toByte()
        val n = contacts.size.coerceAtMost(5)
        var slot = 0
        for ((_, c) in contacts) {
            if (slot >= 5) break
            val off = 1 + slot * PTP_FINGER_BYTES
            bytes[off] = 0x03.toByte()          // bit0=Confidence=1（0 会被当掌压忽略）、bit1=TipSwitch=1
            bytes[off + 1] = c.cid.toByte()
            val x = ((c.x / surfW) * PTP_LOGICAL_MAX_X).toInt().coerceIn(0, PTP_LOGICAL_MAX_X)
            val y = ((c.y / surfH) * PTP_LOGICAL_MAX_Y).toInt().coerceIn(0, PTP_LOGICAL_MAX_Y)
            bytes[off + 5] = (x and 0xFF).toByte()
            bytes[off + 6] = ((x shr 8) and 0xFF).toByte()
            bytes[off + 7] = (y and 0xFF).toByte()
            bytes[off + 8] = ((y shr 8) and 0xFF).toByte()
            slot++
        }
        // Scan Time 单位 100µs（描述符 exp(-4) Seconds）——毫秒/10
        val scan = ((android.os.SystemClock.uptimeMillis() / 10) and 0xFFFF).toInt()
        bytes[46] = (scan and 0xFF).toByte()
        bytes[47] = ((scan shr 8) and 0xFF).toByte()
        bytes[48] = slot.toByte()               // Contact Count
        bytes[49] = 0                           // Buttons（Clickpad 无实体键）
        ctx.hid.sendInputReport(bytes)
    }

    /** 上行出口路径（动态择路；日志只记录变化） */
    @Volatile private var lastPath = ""

    /** v1.7d：长按定时器到点，由 Activity 调用（代理到引擎；锁定成功才震动） */
    fun armDrag() {
        val eng = engineRef.get() ?: return
        val locked = eng.armDrag()
        // v1.7d：震动全局冷却——电容屏压力抖动会造成 UP/DOWN 重放连环震动。
        // v1.26：从 1.5s 收到 0.9s：连续长按测试时，1.5s 会把第二次长按的震动一起吃掉。
        val now = System.currentTimeMillis()
        if (!locked || now - lastBuzzAt < 900) return
        lastBuzzAt = now
        // v1.12：Vibe 模块已随收摊移除，改用系统 Vibrator 做本地触觉反馈
        val app = ctxRef?.appContext ?: return
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
            val vm = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            vib?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vib?.vibrate(20)
        }
    }

    @Volatile private var lastBuzzAt = 0L

    fun feed(ev: android.view.MotionEvent) {
        // v1.11：数位屏（Digitizer）路径移除——描述符侧 TLC 已删（蓝屏缺陷），
        // 只保留鼠标模拟路径
        // v1.8：PTP 模式——MotionEvent 只更新快照，帧流由 8ms 线程周期发送
        // （PTP 设备必须周期上报，含无触摸的空帧；事件驱动式发帧 Windows 驱动不消费）
        if (ptpMode) {
            updatePtpContacts(ev)
            return
        }
        val eng = engineRef.get()
        if (eng == null) {
            // v1.7 诊断：feed 被调但引擎为空 = 模块未启动或已被 stop
            if (System.currentTimeMillis() - lastNullLog > 2000) {
                com.allperiph.core.Log.w(TAG, "feed 时引擎为空（state=$state），事件被丢弃")
                lastNullLog = System.currentTimeMillis()
            }
            return
        }
        eng.onTouch(ev)
    }

    @Volatile private var lastNullLog = 0L

    override fun start(ctx: ModuleContext) {
        state = ModuleState.STARTING
        ctxRef = ctx
        engineRef.set(GestureEngine(object : TouchpadSink {
            override fun send(f: TouchpadFrame) { dispatch(ctx, f) }
        }))
        state = ModuleState.RUNNING
        Log.i(TAG, "触控板模块已启动")
    }

    override fun stop() {
        engineRef.set(null)
        state = ModuleState.STOPPED
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> "触控板运行中（相对鼠标）"
        ModuleState.STARTING -> "启动中…"
        ModuleState.STOPPED, ModuleState.IDLE -> "已停止"
        else -> state.name
    }

    override fun maskBits(): Long = if (state.isActive) (1L shl 33) else 0  // §2.9 触控板位

    /**
     * 动态择路上行：蓝牙 HID → HID Mouse TLC（有线，Report ID 2）→
     * TCP 控制面（无蓝牙机器，v1.7）→ 日志。
     *
     * **必须每次 send 时重新判定**（真机教训）：出口的可用性随链路状态变化
     * （副屏 TCP 后启动、蓝牙晚配对），启动时定死会把链路死锁在最初出口。
     */
    private fun dispatch(ctx: ModuleContext, f: TouchpadFrame) {
        val bt = ctx.module(ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice
        val path = when {
            bt != null && bt.isConnected -> "bluetooth-hid"
            ctx.hid.isReady() -> "hid-tlc"
            else -> "bulk"
        }
        if (path != lastPath) {
            Log.i(TAG, "触控板上行出口：$path")
            lastPath = path
        }
        when (path) {
            "bluetooth-hid" ->
                bt?.reportMouse(f.buttons, f.dx, f.dy, f.wheel, f.pan)
            "hid-tlc" -> if (f.consumer != 0) {
                // v1.7c：Consumer Report 4（§2.6 位图）—— 捏合缩放等非鼠标语义
                ctx.hid.sendInputReport(
                    byteArrayOf(0x04, f.consumer.toByte(), (f.consumer shr 8).toByte(), 0)
                )
            } else {
                // Mouse TLC 复用 Report ID 2（PROTOCOL §2，hid_layout.h MouseReport）
                ctx.hid.sendInputReport(
                    byteArrayOf(0x02, f.buttons.toByte(), f.dx.toByte(), f.dy.toByte(), f.wheel.toByte(), f.pan.toByte())
                )
            }
            // v1.11："tcp-ctrl" 无线承载分支随无线功能移除
            else -> Log.v(TAG, "bulk mouse dx=${f.dx} dy=${f.dy} btns=${f.buttons}")
        }
    }

    companion object {
        private const val TAG = "TouchpadModule"
        // v1.8 PTP 常量（与 shared/include/apx/hid_layout.h 的 kPtp* 对齐；
        // 坐标逻辑域 X=20000/Y=12000，Physical 13.0×8.5 cm）
        const val PTP_REPORT_ID = 16
        const val PTP_FINGER_BYTES = 9
        const val PTP_REPORT_SIZE = 50
        const val PTP_LOGICAL_MAX_X = 20000
        const val PTP_LOGICAL_MAX_Y = 12000
    }
}
