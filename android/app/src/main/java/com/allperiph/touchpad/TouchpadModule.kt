package com.allperiph.touchpad

import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 触控板模块：USB 有线 Precision Touchpad + 无线（蓝牙 HID TLC）触控板。
 *
 * ## 两条链路
 *
 * - **有线（USB）**：MotionEvent → TouchpadFrame → HID TLC → Windows Precision Touchpad。
 *   Windows 通过 PTP 5-finger 手势识别系统手势（三指截屏 / 四指任务视图）。
 * - **无线**：MotionEvent → GestureEngine → Mouse Report → Bluetooth HID Device。
 *   Windows 识别为标准 HID 鼠标，仅支持基本光标 + 按键。
 */
class TouchpadModule : Module {
    override val id: String = ModuleId.TOUCHPAD
    @Volatile override var state: ModuleState = ModuleState.IDLE
    private val engineRef = java.util.concurrent.atomic.AtomicReference<GestureEngine?>(null)

    /** v1.7d: start 能力热替换 */
    @Volatile private var ctxRef: ModuleContext? = null

    // ---- Windows Precision Touchpad ----
    @Volatile var ptpMode: Boolean = false
    @Volatile private var surfW: Int = 1260
    @Volatile private var surfH: Int = 2848

    // ---- v1.9: Digitizer mode ----
    @Volatile var digitizerMode: Boolean = false

    private class Contact(val cid: Int, @Volatile var x: Float, @Volatile var y: Float)

    private val workingContacts = ArrayList<Contact>()

    @Volatile private var snapshot: Array<Contact> = emptyArray()

    private var ptpThread: Thread? = null

    /** MotionEvent → contact snapshot (synchronized with Motion 8ms delta) */
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
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                workingContacts.removeAll { it.cid == (ev.getPointerId(ev.actionIndex) % 4) }
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

    /** v1.8: 8ms synchronized PTP report 0 (Report ID 16) */
    private fun startPtpStream() {
        if (ptpThread?.isAlive == true) return
        ptpThread = Thread {
            while (ptpMode && !Thread.currentThread().isInterrupted) {
                val ctx = ctxRef
                if (ctx != null && ctx.hid.isReady()) sendPtpReport(ctx)
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPtpStream() {
        ptpThread?.interrupt()
        ptpThread = null
    }

    /** PTP 50B Report 0 (Report ID 16) */
    private fun sendPtpReport(ctx: ModuleContext) {
        val bytes = ByteArray(PTP_REPORT_SIZE)
        bytes[0] = PTP_REPORT_ID.toByte()
        val snap = snapshot
        val n = snap.size.coerceAtMost(5)
        for (slot in 0 until n) {
            val c = snap[slot]
            val off = 1 + slot * PTP_FINGER_BYTES
            bytes[off] = 0x03.toByte()    // Confidence + TipSwitch
            bytes[off + 1] = c.cid.toByte()
            val x = ((c.x / surfW) * PTP_LOGICAL_MAX_X).toInt().coerceIn(0, PTP_LOGICAL_MAX_X)
            val y = ((c.y / surfH) * PTP_LOGICAL_MAX_Y).toInt().coerceIn(0, PTP_LOGICAL_MAX_Y)
            bytes[off + 2] = (x and 0xFF).toByte(); bytes[off + 3] = ((x shr 8) and 0xFF).toByte()
            bytes[off + 4] = (y and 0xFF).toByte(); bytes[off + 5] = ((y shr 8) and 0xFF).toByte()
        }
        val scan = ((android.os.SystemClock.elapsedRealtimeMicros()) / 10 and 0xFFFF).toInt()
        bytes[46] = (scan and 0xFF).toByte()
        bytes[47] = ((scan shr 8) and 0xFF).toByte()
        bytes[48] = n.toByte()                        // Contact Count
        bytes[49] = 0                                 // Buttons—Clickpad flag 0
        ctx.hid.sendInputReport(bytes)
    }

    fun setTouchSurface(w: Int, h: Int) { surfW = w; surfH = h }

    fun feed(ev: android.view.MotionEvent) {
        if (digitizerMode) {
            feedDigitizer(ev)
            return
        }
        if (ptpMode) {
            updatePtpContacts(ev)
            return
        }
        val eng = engineRef.get()
        if (eng == null) {
            if (System.currentTimeMillis() - lastNullLog > 2000) {
                Log.w(TAG, "feed 丢弃: state=$state, eng=null")
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
        ModuleState.RUNNING -> "触控板运行中"
        ModuleState.STARTING -> "触控板启动中"
        ModuleState.STOPPED, ModuleState.IDLE -> "触控板已停止"
        else -> state.name
    }

    override fun maskBits(): Long = if (state.isActive) (1L shl 33) else 0

    /** MotionEvent → Digitizer, Report ID 3, 102B header 12 + 1 Contact 8 + pen 10 */
    private fun sendDigitizerReport(ctx: ModuleContext, tip: Boolean, x: Float, y: Float) {
        val b = ByteArray(102)
        b[0] = 3
        b[1] = (if (tip) 0x02 else 0).toByte()    // bit1=TipSwitch
        b[2] = if (tip) 1 else 0                  // contactCount
        val ns = android.os.SystemClock.elapsedRealtimeNanos()
        for (i in 0..7) b[4 + i] = ((ns shr (8 * i)) and 0xFF).toByte()  // tsNs LE
        if (tip) {
            val w = surfW.toFloat(); val h = surfH.toFloat()
            val x16 = ((x / w) * 65535).toInt().coerceIn(0, 65535)
            val y16 = ((y / h) * 65535).toInt().coerceIn(0, 65535)
            val off = 12                              // contact[0]
            b[off + 2] = (x16 and 0xFF).toByte(); b[off + 3] = ((x16 shr 8) and 0xFF).toByte()
            b[off + 4] = (y16 and 0xFF).toByte(); b[off + 5] = ((y16 shr 8) and 0xFF).toByte()
            b[off + 6] = 0x7F; b[off + 7] = 0x7F  // pressure 0x7FFF
        }
        ctx.hid.sendInputReport(b)
    }

    /** Digitizer event feed */
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

    private var lastPath = ""

    /** Drag support */
    fun armDrag() {
        val eng = engineRef.get() ?: return
        val locked = eng.armDrag()
        val now = System.currentTimeMillis()
        if (!locked || now - lastBuzzAt < 900) return
        lastBuzzAt = now
        val app = ctxRef?.appContext ?: return
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
            val vm = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        vib?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
    }

    @Volatile private var lastBuzzAt = 0L

    /** Right-click drag */
    fun armRightDrag() {
        val eng = engineRef.get() ?: return
        eng.armRightDrag()
    }

    /**
     * HID Mouse TLC Report ID 2.
     * Consumer Key Report ID 4 (volume/page hotkeys).
     * Bulk TCP fallback.
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
                ctx.hid.sendInputReport(
                    byteArrayOf(0x04, f.consumer.toByte(), (f.consumer shr 8).toByte(), 0)
                )
            } else {
                ctx.hid.sendInputReport(
                    byteArrayOf(0x02, f.buttons.toByte(), f.dx.toByte(), f.dy.toByte(), f.wheel.toByte(), f.pan.toByte())
                )
            }
            else -> Log.v(TAG, "bulk mouse dx=${f.dx} dy=${f.dy} btns=${f.buttons}")
        }
    }

    companion object {
        private const val TAG = "TouchpadModule"
        private const val PTP_REPORT_ID = 16
        private const val PTP_FINGER_BYTES = 9
        private const val PTP_REPORT_SIZE = 50
        private const val PTP_LOGICAL_MAX_X = 20000
        private const val PTP_LOGICAL_MAX_Y = 12000
    }
}