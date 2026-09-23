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
    @Volatile var digitizerMode: Boolean = false

    /** MotionEvent → Digitizer 报告（RID 3，102B：header 12 + 10×contact 8 + pen 10） */
    private fun sendDigitizerReport(ctx: ModuleContext, tip: Boolean, x: Float, y: Float) {
        val b = ByteArray(102)
        b[0] = 3
        b[1] = (if (tip) 0x02 else 0).toByte()   // bit1=TipSwitch
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

    /** Digitizer 模式事件入口 */
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

    // =====================================================================
    // § 优化 #1：PTP 触点双缓冲（无锁快照替换）
    // =====================================================================
    private class Contact(val cid: Int, @Volatile var x: Float, @Volatile var y: Float)

    private val workingContacts = ArrayList<Contact>()

    @Volatile private var snapshot: Array<Contact> = emptyArray()

    private var ptpThread: Thread? = null

    private fun updatePtpContacts(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex.coerceIn(0, ev.pointerCount - 1)
                val pid = ev.getPointerId(i)
                val exists = workingContacts.any { it.cid == (pid % 4) }
                if (!exists && workingContacts.size < 5) {
                    workingContacts.add(Contact(nextContactId, ev.getX(i), ev.getY(i)))
                    nextContactId = (nextContactId + 1) and 0x03
                }
            }
            android.view.MotionEvent.ACTION_UP -> workingContacts.clear()
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                workingContacts.removeAll { it.cid == (pid % 4) }
            }
        }
        for (i in 0 until ev.pointerCount) {
            val pid = ev.getPointerId(i)
            val cid = pid % 4
            workingContacts.firstOrNull { it.cid == cid }?.let { c ->
                c.x = ev.getX(i); c.y = ev.getY(i)
            }
        }
        snapshot = workingContacts.toTypedArray()
    }

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

    private fun sendPtpReport(ctx: ModuleContext) {
        val bytes = ByteArray(PTP_REPORT_SIZE)
        bytes[0] = PTP_REPORT_ID.toByte()
        val snap = snapshot
        val n = snap.size.coerceAtMost(5)
        for (slot in 0 until n) {
            val c = snap[slot]
            val off = 1 + slot * PTP_FINGER_BYTES
            bytes[off] = 0x03.toByte()
            bytes[off + 1] = c.cid.toByte()
            val x = ((c.x / surfW) * PTP_LOGICAL_MAX_X).toInt().coerceIn(0, PTP_LOGICAL_MAX_X)
            val y = ((c.y / surfH) * PTP_LOGICAL_MAX_Y).toInt().coerceIn(0, PTP_LOGICAL_MAX_Y)
            bytes[off + 5] = (x and 0xFF).toByte()
            bytes[off + 6] = ((x shr 8) and 0xFF).toByte()
            bytes[off + 7] = (y and 0xFF).toByte()
            bytes[off + 8] = ((y shr 8) and 0xFF).toByte()
        }
        val scan = ((android.os.SystemClock.uptimeMillis() / 10) and 0xFFFF).toInt()
        bytes[46] = (scan and 0xFF).toByte()
        bytes[47] = ((scan shr 8) and 0xFF).toByte()
        bytes[48] = n.toByte()
        bytes[49] = 0
        ctx.hid.sendInputReport(bytes)
    }

    @Volatile private var lastPath = ""

    fun armDrag() {
        val eng = engineRef.get() ?: return
        val locked = eng.armDrag()
        val now = System.currentTimeMillis()
        if (!locked || now - lastBuzzAt < 900) return
        lastBuzzAt = now
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
        if (ptpMode) {
            updatePtpContacts(ev)
            return
        }
        val eng = engineRef.get()
        if (eng == null) {
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
        stopPtpStream()
        engineRef.set(null)
        state = ModuleState.STOPPED
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> "触控板运行中（相对鼠标）"
        ModuleState.STARTING -> "启动中…"
        ModuleState.STOPPED, ModuleState.IDLE -> "已停止"
        else -> state.name
    }

    override fun maskBits(): Long = if (state.isActive) (1L shl 33) else 0

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
                ctx.hid.sendInputReport(
                    byteArrayOf(0x04, f.consumer.toByte(), (f.consumer shr 8).toByte(), 0)
                )
            } else {
                // Mouse report (buttons + deltas) — works for both press and release
                ctx.hid.sendInputReport(
                    byteArrayOf(0x02, f.buttons.toByte(), f.dx.toByte(), f.dy.toByte(), f.wheel.toByte(), f.pan.toByte())
                )
            }
            else -> Log.v(TAG, "bulk mouse dx=${f.dx} dy=${f.dy} btns=${f.buttons}")
        }
    }

    companion object {
        private const val TAG = "TouchpadModule"
        const val PTP_REPORT_ID = 16
        const val PTP_FINGER_BYTES = 9
        const val PTP_REPORT_SIZE = 50
        const val PTP_LOGICAL_MAX_X = 20000
        const val PTP_LOGICAL_MAX_Y = 12000
    }
}