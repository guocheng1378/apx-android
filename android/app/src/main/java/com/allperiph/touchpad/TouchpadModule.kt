package com.allperiph.touchpad

import com.allperiph.shared.util.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.Uplink
import com.allperiph.hid.HidKeys

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

    /** PTP contact ID allocator (cycles 0-4) */
    @Volatile private var nextContactId: Int = 0

    /** MotionEvent → contact snapshot (synchronized with Motion 8ms delta) */
    private fun updatePtpContacts(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex.coerceIn(0, ev.pointerCount - 1)
                val pid = ev.getPointerId(i)
                // fix: pid % 5（支持 5 个 contact ID 0-4），原 pid % 4 导致第 5 根手指碰撞
                val exists = workingContacts.any { it.cid == (pid % 5) }
                if (!exists && workingContacts.size < 5) {
                    workingContacts.add(Contact(nextContactId, ev.getX(i), ev.getY(i)))
                    // fix: % 5（循环 0-4）。原 `and 0x04` 是按位与：只可能得 0 或 4，
                    // 从 0 出发 (0+1) and 4 == 0 恒成立 → nextContactId 永远为 0，
                    // 所有手指 cid 相同：第 2 根手指起坐标更新被丢弃、抬起时删错对象。
                    nextContactId = (nextContactId + 1) % 5
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                workingContacts.removeAll { it.cid == (ev.getPointerId(ev.actionIndex) % 5) }
            }
        }
        for (i in 0 until ev.pointerCount) {
            val pid = ev.getPointerId(i)
            val cid = pid % 5
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
        val scan = ((android.os.SystemClock.elapsedRealtime()) / 10L and 0xFFFFL).toInt()
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
        val eng = GestureEngine(object : TouchpadSink {
            override fun send(f: TouchpadFrame) { dispatch(ctx, f) }
        })
        eng.onFeedback = { kind -> onGestureFeedback(kind) }
        // 反馈#8：三指滑动 → Windows 手势组合键（鼠标报告链路看不见手指，只能端上转译）
        eng.onThreeSwipe = { dir ->
            val combo = when (dir) {
                0 -> HidKeys.Combo("3指上", HidKeys.MOD_ALT, 0x2B)                       // Alt+Tab 切换应用
                1 -> HidKeys.Combo("3指下", HidKeys.MOD_WIN, 0x07)                       // Win+D 显示桌面
                2 -> HidKeys.Combo("3指左", HidKeys.MOD_CTRL or HidKeys.MOD_WIN, 0x50)   // Ctrl+Win+← 切桌面
                else -> HidKeys.Combo("3指右", HidKeys.MOD_CTRL or HidKeys.MOD_WIN, 0x4F)
            }
            HidKeys.combo(combo)
        }
        engineRef.set(eng)
        state = ModuleState.RUNNING
        // issue #52 建议 6：外接触控板 root 直读（默认关；开启了才真正接管，
        // 且拿不到 root 时 RootPointerBridge 自己会如实降级回窗口 hover 通路）
        com.allperiph.touchpad.RootPointerBridge.start()
        Log.i(TAG, "触控板模块已启动")
    }

    override fun stop() {
        stopPtpStream()
        com.allperiph.touchpad.RootPointerBridge.stop()
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

    // ———— 反馈#8：物理触摸板指针直通（蓝牙触摸板 → 本机 → 电脑） ————
    private var ptrFracX = 0f
    private var ptrFracY = 0f
    private var ptrWheelAcc = 0f
    private var ptrPanAcc = 0f
    private var ptrButtons = 0

    // 批N：USB 背压合并——被拒帧的位移/滚轮/按钮/媒体键累积到下帧或 16ms 补发（pointer 幂等不丢位置）
    private var pdDx = 0
    private var pdDy = 0
    private var pdWheel = 0
    private var pdPan = 0
    private var pdConsumer = 0
    private var pdButtons = -1
    private var lastSentButtons = 0
    @Volatile private var retryScheduled = false
    @Volatile private var retryMiss = 0

    /**
     * 物理触摸板（BT 指针设备）的相对位移/按键/滚轮直通：由 PhysKeyBridge
     * 在 dispatchGenericMotionEvent 里喂入，直接走 [dispatch] 三出口（与手势同链路）。
     * 不经 GestureEngine —— 指针是设备已归一化好的，端上不再做手势识别（除滚轮轴累加）。
     */
    fun feedPointer(dx: Float, dy: Float, buttons: Int, wheel: Float, pan: Float, tsNs: Long) {
        val ctx = ctxRef ?: return
        ptrFracX += dx; ptrFracY += dy
        ptrWheelAcc += wheel; ptrPanAcc += pan
        val idx = ptrFracX.toInt().coerceIn(-127, 127); ptrFracX -= idx
        val idy = ptrFracY.toInt().coerceIn(-127, 127); ptrFracY -= idy
        val w = ptrWheelAcc.toInt().coerceIn(-127, 127); ptrWheelAcc -= w
        val p = ptrPanAcc.toInt().coerceIn(-127, 127); ptrPanAcc -= p
        if (idx == 0 && idy == 0 && w == 0 && p == 0 && buttons == ptrButtons) return
        ptrButtons = buttons
        dispatch(ctx, TouchpadFrame(idx, idy, buttons, wheel = w, pan = p, tsNs = tsNs))
    }

    /**
     * Drag support：长按 550ms 锁定拖拽。
     * @return 是否真的锁定（手指移动过多 / 多指时锁不上，调用方据此决定要不要给提示）
     */
    fun armDrag(): Boolean = engineRef.get()?.armDrag() ?: false

    @Volatile private var lastBuzzAt = 0L
    @Volatile private var lastBuzzKind: GestureFeedback? = null

    /**
     * 手势里程碑反馈。只在这些"状态切换点"震一下，滑动过程中的每一帧都不震。
     *
     * ★ 统一走 [com.allperiph.ui.Feedback]，不再自己拿 Vibrator：
     *   1. 此前用 `createPredefined(EFFECT_CLICK)`，各 ROM 对预置效果支持不一，
     *      不少机型直接没反应 —— 就是用户报的"长按没震动"；
     *   2. 那条路还绕开了 [com.allperiph.ui.ThemeSkin.hapticIndex] 档位，
     *      设置里选了"关"照样震。
     * 现在振幅/时长自己给，且尊重用户的档位设置。
     */
    private fun onGestureFeedback(kind: GestureFeedback) {
        val app = ctxRef?.appContext ?: return
        val now = System.currentTimeMillis()
        // 节流：捏合会连续跨阈值，同一里程碑 250ms 内只认一次
        if (now - lastBuzzAt < 250 && lastBuzzKind == kind) return
        lastBuzzAt = now
        lastBuzzKind = kind
        com.allperiph.ui.Feedback.haptic(app)
    }

    /** Right-click drag */
    fun armRightDrag() {
        val eng = engineRef.get() ?: return
        eng.armRightDrag()
    }

    /**
     * 上行出口择优：**判据只有一处** —— [Uplink.resolve]（无线目标 → USB HID → 蓝牙 HID → 无出口）。
     *
     * 无线受控目标（TV / PC，9511）优先级最高，会**覆盖**蓝牙与 USB：触控板相对位移经 APX1
     * 控制帧上行，对端用 SendInput / evdev 注入。没有任何出口时记为 [Uplink.NONE] 并在界面显示
     * 「出口：无（输入会被丢弃：…）」—— 不再像旧实现那样只打一行 `Log.v` 把输入丢进黑洞。
     */
    /** 批N：USB 背压补发——16ms 后携带积压重试；连续 miss>64（≈1s）放弃防空转 */
    private fun scheduleUsbRetry() {
        if (retryScheduled) return
        retryScheduled = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            retryScheduled = false
            val ctx = ctxRef ?: return@postDelayed
            val has = pdDx != 0 || pdDy != 0 || pdWheel != 0 || pdPan != 0 || pdConsumer != 0 || pdButtons >= 0
            if (!has) { retryMiss = 0; return@postDelayed }
            if (retryMiss > 64) {
                pdDx = 0; pdDy = 0; pdWheel = 0; pdPan = 0; pdConsumer = 0; pdButtons = -1
                retryMiss = 0
                Log.w(TAG, "USB 背压持续被拒，积压已放弃（host 长期未取报告）")
                return@postDelayed
            }
            retryMiss++
            val btns = if (pdButtons >= 0) pdButtons else lastSentButtons
            val ok = if (pdConsumer != 0) {
                ctx.hid.sendInputReport(byteArrayOf(0x04, pdConsumer.toByte(), (pdConsumer shr 8).toByte(), 0))
            } else {
                ctx.hid.sendInputReport(byteArrayOf(0x02, btns.toByte(), pdDx.toByte(), pdDy.toByte(), pdWheel.toByte(), pdPan.toByte()))
            }
            if (ok) {
                pdDx = 0; pdDy = 0; pdWheel = 0; pdPan = 0; pdConsumer = 0; pdButtons = -1
                retryMiss = 0
                lastSentButtons = btns
            } else scheduleUsbRetry()
        }, 16)
    }

    private fun dispatch(ctx: ModuleContext, f: TouchpadFrame) {
        val bt = ctx.module(ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice
        val wirelessReady = com.allperiph.wireless.ControlTarget.isControlling() &&
            com.allperiph.wireless.ControlTarget.controlClient != null
        // v184：出口级独立开关 —— 用户在设置页把某条出口关掉后，手势按剩余出口回落，
        // 而不是被 Uplink.resolve 直接选中后强行发出（也避免"开一个全开"的联动感知）
        val wifiOn = com.allperiph.ui.AgentController.isExitEnabled(ModuleId.TOUCHPAD, "wifi")
        val usbOn = com.allperiph.ui.AgentController.isExitEnabled(ModuleId.TOUCHPAD, "usb")
        var path = Uplink.resolve(ctx.hid, bt?.isConnected == true, wirelessReady && wifiOn)
        if (path == Uplink.USB && !usbOn) {
            path = if (bt?.isConnected == true) Uplink.BLUETOOTH else Uplink.NONE
            if (path == Uplink.NONE) {
                Uplink.set(path, "USB 触摸板已在设置中停用")
                return
            }
        }

        when (path) {
            Uplink.WIRELESS -> {
                val c = com.allperiph.wireless.ControlTarget.controlClient
                Uplink.set(path, com.allperiph.wireless.ControlTarget.label)
                if (c != null) {
                    if (f.consumer != 0) c.consumer(f.consumer)
                    // pan 一并下发（v223.1）：此前无线出口把横向滚动整个丢掉，
                    // 只有 USB / 蓝牙出口带 pan —— 物理触摸板双指横滑「只在插线时能用」。
                    else c.mouse(f.buttons, f.dx, f.dy, f.wheel, f.pan)
                }
            }
            Uplink.USB -> {
                // 批N：背压合并——失败帧位移/滚轮/按钮/媒体键并入下帧；溢出保留
                val rawX = f.dx + pdDx; val rawY = f.dy + pdDy
                val rawW = f.wheel + pdWheel; val rawP = f.pan + pdPan
                val dx = rawX.coerceIn(-127, 127); val dy = rawY.coerceIn(-127, 127)
                val wh = rawW.coerceIn(-127, 127); val pn = rawP.coerceIn(-127, 127)
                val cons = if (f.consumer != 0) f.consumer else pdConsumer
                val btns = if (pdButtons >= 0) pdButtons else f.buttons
                val ok = if (cons != 0) {
                    ctx.hid.sendInputReport(
                        byteArrayOf(0x04, cons.toByte(), (cons shr 8).toByte(), 0)
                    )
                } else {
                    ctx.hid.sendInputReport(
                        byteArrayOf(0x02, btns.toByte(), dx.toByte(), dy.toByte(), wh.toByte(), pn.toByte())
                    )
                }
                if (ok) {
                    pdDx = 0; pdDy = 0; pdWheel = 0; pdPan = 0; pdConsumer = 0; pdButtons = -1
                    retryMiss = 0
                    lastSentButtons = btns
                    Uplink.set(path)
                } else {
                    pdDx = rawX - dx; pdDy = rawY - dy
                    pdWheel = rawW - wh; pdPan = rawP - pn
                    if (cons != 0) pdConsumer = cons
                    pdButtons = btns
                    val why = "USB HID 写入被拒（主机未取走上一报告或节点异常）"
                    if (Uplink.reason != why) Log.w(TAG, why)
                    Uplink.set(path, why)
                    scheduleUsbRetry()
                }
            }
            Uplink.BLUETOOTH -> {
                Uplink.set(path)
                bt?.reportMouse(f.buttons, f.dx, f.dy, f.wheel, f.pan)
            }
            else -> {
                val first = Uplink.current != Uplink.NONE
                val why = buildString {
                    if (com.allperiph.wireless.ControlTarget.isControlling()) append("受控目标已断开；")
                    append("USB HID 未就绪（/dev/hidg0 未挂载或 USB 口被调试占用）、蓝牙未连接")
                }
                Uplink.set(Uplink.NONE, why)
                if (first) Log.w(TAG, "无可用上行出口，输入被丢弃：$why")
            }
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