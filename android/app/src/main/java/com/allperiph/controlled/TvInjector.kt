package com.allperiph.controlled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import com.allperiph.core.Log

/**
 * 控制帧的最终落点：把对方手机发来的光标/按键/文本/剪贴板转成对系统的真实操作。
 *  - 光标：始终用全局浮层可视化（[TvOverlay]）。
 *  - 系统注入：仅当 [ApxAccessibilityService] 已启用时生效；否则仅可视化（演示用）。
 * 与 TV 模块 [com.allperiph.tv.core.TvInjector] 同实现，仅包名不同。
 */
object TvInjector {
    private var ctx: Context? = null
    private var screenW = 0
    private var screenH = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * View 操作只能在主线程做（叠层浮窗 [TvOverlay] 走 WindowManager）。
     * 被控服务端 [TvControlServer] 是在**握手线程 / 收流线程**里回调进来的：直接动 View 会抛
     * CalledFromWrongThreadException，而它又在握手 try 里 —— 会被当成「握手失败」把刚建立的
     * 连接当场关掉（真机症状：面板显示已连接却立刻掉线、点副屏报「媒体连接未建立」）。
     */
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    @Volatile
    var cursorX = 0f

    @Volatile
    var cursorY = 0f

    private const val NUDGE = 48f
    private var touchDownX = -1f
    private var touchDownY = -1f
    private var touchDownAt = 0L

    /** 手柄按钮上一帧状态（用于边沿检测） */
    private var lastGamepadButtons = 0

    fun init(c: Context) {
        ctx = c.applicationContext
        TvOverlay.init(c.applicationContext)
        refreshScreen()
        // 通道按"能拿到多少能力"排序，全部 fire-and-forget：
        //  ① evdev 内核注入（免 root，任意按键）—— 多数手机会因 /dev/input 权限不可用而自动跳过
        //  ② root `input`（全键鼠）
        //  ③ 无障碍（只能点/滑/输入框打字）
        Thread({ runCatching { EvdevInjector.tryStart() } }, "apx-evdev").start()
        //  ④ uinput 虚拟手柄：手柄**摇杆**只有它能注入（见 gamepad()）
        Thread({ runCatching { UinputGamepad.tryStart() } }, "apx-uinput").start()
        RootInput.tryStart()
    }

    fun onDestroy() {
        TvOverlay.destroy()
        ctx = null
    }

    fun systemReady(): Boolean = ApxAccessibilityService.isReady()

    /**
     * 当前可用的注入通道（人话）。
     *
     * 手机被控端原先**只有通知里一行字** —— 页面上完全看不到"到底缺哪一项"，
     * 而这恰恰是"连上了点不动"的唯一诊断入口。这里给界面提供与 TV 端同一份文案。
     */
    fun channelText(): String = when {
        UinputGamepad.ready -> "root 注入 + 虚拟手柄（全键 + 摇杆）"
        RootInput.available -> "root 注入 · 全键可用"
        EvdevInjector.available -> "evdev 内核注入 · 免 root，全键可用"
        systemReady() -> "无障碍注入 · 仅点击/滑动/输入框打字，按键不可用"
        else -> "未开启 · 只能看到光标，点不动"
    }

    /** 是否具备「任意按键」级别的能力 */
    fun fullKeyReady(): Boolean = RootInput.available || EvdevInjector.available

    /** 控制能力自检清单：`(名称, 是否就绪, 没就绪时该怎么办)` */
    fun capabilities(): List<Triple<String, Boolean, String>> = listOf(
        Triple(
            "root 注入（全键）",
            RootInput.available,
            "未授予 root —— 没有它就无法发任意按键（方向键 / 组合键 / 手柄按钮）",
        ),
        Triple(
            "evdev 内核注入（免 root）",
            EvdevInjector.available,
            "手机的 /dev/input/event* 通常是 0660 root:input，普通应用打不开（预期会跳过，不影响使用）",
        ),
        Triple(
            "无障碍注入（点击 / 滑动 / 打字）",
            systemReady(),
            "到「无障碍 / 辅助功能」里启用本应用",
        ),
        Triple("悬浮窗（把光标画在屏幕上）", TvOverlay.isReady, "到「显示在其他应用上层」里允许本应用"),
        Triple(
            "输入法（中文打字最稳）",
            ApxImeService.isActive(),
            "到「输入法 / 键盘」设置里启用「全能外设输入」并切过去",
        ),
        Triple("虚拟手柄（摇杆）", UinputGamepad.ready, "需要 root（会自动尝试 chmod /dev/uinput）"),
    )

    private fun refreshScreen() {
        val wm = ctx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.let { d ->
            val m = android.util.DisplayMetrics()
            d.getMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = screenW / 2f
            cursorY = screenH / 2f
        }
    }

    fun setConnected(on: Boolean) {
        // 关键：本函数被握手/收流线程调用，浮窗操作必须切回主线程（见 onMain 注释）
        onMain {
            refreshScreen()
            if (on) TvOverlay.move(cursorX, cursorY) else TvOverlay.hide()
        }
    }

    fun cursorMove(x: Float, y: Float, absolute: Boolean) {
        refreshScreen()
        if (absolute) {
            cursorX = (x * screenW).coerceIn(0f, screenW.toFloat())
            cursorY = (y * screenH).coerceIn(0f, screenH.toFloat())
        } else {
            cursorX = (cursorX + x).coerceIn(0f, screenW.toFloat())
            cursorY = (cursorY + y).coerceIn(0f, screenH.toFloat())
        }
        TvOverlay.move(cursorX, cursorY)
    }

    fun click() = tapAt(cursorX, cursorY)

    fun tapAt(x: Float, y: Float) {
        if (RootInput.available && RootInput.run("input tap ${x.toInt()} ${y.toInt()}")) return
        if (systemReady()) ApxAccessibilityService.instance?.tap(x, y)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (RootInput.available && RootInput.run(
                "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $ms"
            )
        ) return
        if (systemReady()) ApxAccessibilityService.instance?.swipe(x1, y1, x2, y2, ms)
    }

    fun touchDown(fx: Float, fy: Float) {
        touchDownX = fx * screenW
        touchDownY = fy * screenH
        touchDownAt = SystemClock.uptimeMillis()
    }

    fun touchUp(fx: Float, fy: Float) {
        val ux = fx * screenW
        val uy = fy * screenH
        if (touchDownX < 0f) {
            tapAt(ux, uy)
            return
        }
        val dur = SystemClock.uptimeMillis() - touchDownAt
        finishStroke(touchDownX, touchDownY, ux, uy, dur)
        touchDownX = -1f
        touchDownY = -1f
    }

    /** 在当前光标处"按下"（配 [pressUp] 构成 点击/长按/拖拽） */
    fun pressDown() {
        touchDownX = cursorX
        touchDownY = cursorY
        touchDownAt = SystemClock.uptimeMillis()
    }

    /** 在当前光标处"抬起"：不动=轻点或长按（≥500ms），移动=拖拽（时长取实际值） */
    fun pressUp() {
        if (touchDownX < 0f) return
        val dur = SystemClock.uptimeMillis() - touchDownAt
        finishStroke(touchDownX, touchDownY, cursorX, cursorY, dur)
        touchDownX = -1f
        touchDownY = -1f
    }

    /**
     * 一笔的落点：
     *  - 位移 < 12px 且 ≥500ms → **长按**（以前不管按多久都是单击，长按菜单/拖拽全废）；
     *  - 位移 < 12px → 轻点；
     *  - 有位移 → 滑动/拖拽，**时长用真实的按压时长**（以前固定 180ms，拖动总是太急）。
     */
    private fun finishStroke(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val dist = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        if (dist < 12) {
            if (durMs >= 500) longPress(x2, y2) else tapAt(x2, y2)
        } else {
            swipe(x1, y1, x2, y2, durMs.coerceIn(120, 2000))
        }
    }

    private fun longPress(x: Float, y: Float) {
        if (RootInput.available && RootInput.run("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 600")) return
        if (systemReady()) ApxAccessibilityService.instance?.longPress(x, y)
    }

    /** 滚轮 → 被控端滚动（一格 ≈ 屏高 1/10；正 = 内容上滚 = 手指上滑）。以前滚轮帧被直接忽略。 */
    fun scroll(notches: Int) {
        if (notches == 0) return
        val step = screenH / 10f * notches
        if (RootInput.available && RootInput.run(
                "input swipe ${cursorX.toInt()} ${cursorY.toInt()} ${cursorX.toInt()} ${(cursorY - step).toInt()} 220"
            )
        ) return
        if (systemReady()) ApxAccessibilityService.instance?.swipe(cursorX, cursorY, cursorX, cursorY - step, 220)
    }

    fun key(kc: Int, down: Boolean) {
        // ① evdev 内核注入（免 root）：按下与松开都真的发出去，长按/连发/组合键才对
        if (EvdevInjector.available && EvdevInjector.sendKey(kc, down)) return
        if (!down) return
        // ② root 通道：**任意按键**都能注入（走的是与 OTG 鼠标 / 蓝牙手柄同一条系统输入通道）。
        //   无障碍做不到这一点，所以只有 root 通道可用时按键才真正可用。
        //   `input keyevent` 自带 down+up，因此只在 down 时发一次。
        if (RootInput.available && RootInput.run("input keyevent $kc")) return
        if (!systemReady()) return
        when (kc) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(0f, -NUDGE)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0f, NUDGE)
            KeyEvent.KEYCODE_DPAD_CENTER -> click()
            // 回车：先试输入法（输入框内提交/换行），不行再退回"点一下光标处"
            KeyEvent.KEYCODE_ENTER ->
                if (!ApxImeService.key(KeyEvent.KEYCODE_ENTER, true)) click()
            // 退格：输入法通道成功率最高
            KeyEvent.KEYCODE_DEL ->
                if (!ApxImeService.delete()) ApxAccessibilityService.instance?.deleteChar()
            KeyEvent.KEYCODE_SPACE -> ApxAccessibilityService.instance?.typeText(" ")
            // 手机上很常用的几个键：Tab 走文本（多数输入框会跳焦点/缩进）；PageUp/Down 与
            // Home/End 没有通用系统语义，退化为"浮层光标大幅移动" —— 至少让用户看到反馈，
            // 而不是像以前那样什么都不发生。
            KeyEvent.KEYCODE_TAB -> ApxAccessibilityService.instance?.typeText("\t")
            KeyEvent.KEYCODE_PAGE_UP -> nudge(0f, -NUDGE * 8)
            KeyEvent.KEYCODE_PAGE_DOWN -> nudge(0f, NUDGE * 8)
            KeyEvent.KEYCODE_MOVE_HOME -> nudge(-screenW.toFloat(), 0f)
            KeyEvent.KEYCODE_MOVE_END -> nudge(screenW.toFloat(), 0f)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            KeyEvent.KEYCODE_MEDIA_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            // ★ 遥控器套（HotkeyTemplates "tv"）的「返回 / 主页」：服务端已把 0x29(Esc)
            //   映射成 KEYCODE_BACK、0x4A(Home) 映射成 KEYCODE_HOME，原先这里没有分支 →
            //   被**静默丢弃**（被控手机点「返回/主页」毫无反应）。无障碍服务注入不了任意按键，
            //   但这三个全局动作是它明确支持的。
            // 电源键：root 下 `input keyevent 26` 真能锁屏/亮屏；无 root 用全局锁屏动作（API 28+）。
            // 以前白名单里没有它 → 用户按了没反应（真机反馈"还缺电源键"）。
            KeyEvent.KEYCODE_POWER -> {
                if (RootInput.available) RootInput.run("input keyevent 26")
                else ApxAccessibilityService.instance?.lockScreen()
            }
            KeyEvent.KEYCODE_BACK -> ApxAccessibilityService.instance?.back()
            KeyEvent.KEYCODE_HOME -> ApxAccessibilityService.instance?.home()
            KeyEvent.KEYCODE_APP_SWITCH -> ApxAccessibilityService.instance?.recents()
            KeyEvent.KEYCODE_ESCAPE -> ApxAccessibilityService.instance?.back()
            else -> {
                // 其余（含 Ctrl/Alt/Shift/Win 修饰键 113/59/57/117/114/60/58/118，由键盘帧的
                // mod 位图折出）：本机没有组合键注入语义，只能忽略。
                // ★ 但**必须打日志**：以前静默丢弃，用户只看到"键位不对/按了没反应"，
                //   谁都查不到到底丢了哪个键。
                android.util.Log.w("TvInjector", "被控按键未支持（无障碍通道无法注入该键）：kc=$kc")
            }
        }
    }

    /**
     * 电源动作（控制帧 opcode `0x22`）：`0`=关机、`1`=重启、`2`=待机。
     *
     * **关机与重启必须有 root**（`reboot -p` / `reboot`）—— Android 的
     * `ACTION_REQUEST_SHUTDOWN` 是系统签名权限，第三方应用拿不到。没有 root 时
     * **如实退回「软电源键」**（待机 / 唤醒），而不是假装关掉了。
     *
     * @return 是否真的执行了"关机/重启"
     */
    fun powerAction(action: Int): Boolean {
        if (action == 2) {
            key(KeyEvent.KEYCODE_POWER, true)
            key(KeyEvent.KEYCODE_POWER, false)
            return true
        }
        val cmd = if (action == 0) "reboot -p" else "reboot"
        if (RootInput.available && RootInput.run(cmd)) {
            android.util.Log.i("TvInjector", "电源动作：已通过 root 执行 `$cmd`")
            return true
        }
        android.util.Log.w(
            "TvInjector",
            "电源动作：没有 root，无法真" + (if (action == 0) "关机" else "重启") + " → 退回软电源键（待机）",
        )
        key(KeyEvent.KEYCODE_POWER, true)
        key(KeyEvent.KEYCODE_POWER, false)
        return false
    }

    private fun nudge(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenW.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenH.toFloat())
        TvOverlay.move(cursorX, cursorY)
    }

    fun text(ch: Char) {
        if (ch == '\u0000') return
        val s = ch.toString()
        // ① evdev：字母/数字/空格直接发真按键（不依赖"当前有没有输入框"、也不依赖输入法切没切过来）
        if (EvdevInjector.available && EvdevInjector.sendChar(ch)) return
        // ② 输入法通道（最稳，中英文都行）：系统输入法是我们时走 InputConnection
        if (ApxImeService.commit(s)) return
        // ② 无障碍 ACTION_SET_TEXT
        if (systemReady() && ApxAccessibilityService.instance?.typeText(s) == true) return
        // 兜底：ACTION_SET_TEXT 被拒（MIUI / HyperOS 上常见：密码框、自绘输入框、
        // rootInActiveWindow 取不到节点）→ 改走"剪贴板 + ACTION_PASTE"。
        // 以前没有这层兜底，于是用户看到的就是"打字没反应"。
        if (ApxAccessibilityService.instance?.paste(s) == true) return
        android.util.Log.w("TvInjector", "文本注入失败（当前没有聚焦的输入框？）：'$s'")
    }

    /** 多媒体位图（与 CONSUMER_MAP 同序）：音量/静音用 AudioManager，播放控制尽力而为 */
    fun consumer(bitmap: Int) {
        // ★ bit3 = 电源：**必须在这里注入**。TcpControlServer.onConsumer 里那圈 CONSUMER_MAP
        //   只调了 TvInputDispatcher（驱动界面），**没有调 TvInjector** ——
        //   所以只往 CONSUMER_MAP 里加 "3 to 26" 是不会真的发出电源键的。
        if (bitmap and (1 shl 3) != 0) {
            key(KeyEvent.KEYCODE_POWER, true)
            key(KeyEvent.KEYCODE_POWER, false)
        }
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (bitmap and (1 shl 0) != 0) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        if (bitmap and (1 shl 1) != 0) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        if (bitmap and (1 shl 2) != 0) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
        if (bitmap and (1 shl 4) != 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (bitmap and (1 shl 5) != 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (bitmap and (1 shl 6) != 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    private fun sendMediaKey(code: Int) {
        val c = ctx ?: return
        val down = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
            .putExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        val up = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
            .putExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, code))
        runCatching { c.sendOrderedBroadcast(down, null) }
        runCatching { c.sendOrderedBroadcast(up, null) }
    }

    /** 剪贴板文本：写入被控端系统剪贴板，并尝试填入当前聚焦输入框 */
    fun clipboard(text: String) {
        val c = ctx ?: return
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("APX", text))
        // ① 输入法通道：**中文**只有这条最稳 —— root 的 `input text` 只支持 ASCII，
        //    无障碍 ACTION_SET_TEXT 在 MIUI / 自绘输入框上又常被拒。
        if (ApxImeService.commit(text)) return
        if (systemReady() && ApxAccessibilityService.instance?.typeText(text) == true) return
        // 兜底：剪贴板已经写进去了，退到 ACTION_PASTE
        ApxAccessibilityService.instance?.paste(text)
    }

    /** 手柄：buttons 16 位位图 + 双摇杆 4 轴（i8，约 -127..127）。
     *  走 InputManager.injectInputEvent（需 INJECT_EVENTS 权限）；AccessibilityService 无法注入手柄。 */
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
        // ① **uinput 虚拟手柄（首选）**：按钮与**摇杆**都是内核级真实输入。
        if (UinputGamepad.ready) {
            for (bit in 0..15) {
                val now = (buttons ushr bit) and 1 == 1
                val was = (lastGamepadButtons ushr bit) and 1 == 1
                if (now == was) continue
                UinputGamepad.button(bit, now)
            }
            lastGamepadButtons = buttons
            UinputGamepad.sticks(x, y, rx, ry)
            return
        }
        // ② evdev：手柄按钮 → 真按键（不需要 root / INJECT_EVENTS）。
        //   摇杆仍不在这里注入 —— 目标设备是键盘，没有 ABS 轴；要真摇杆得上 uinput 造虚拟手柄。
        if (EvdevInjector.available) {
            for (bit in 0..15) {
                val now = (buttons ushr bit) and 1 == 1
                val was = (lastGamepadButtons ushr bit) and 1 == 1
                if (now == was) continue
                val kc = GAMEPAD_KEYCODES[bit] ?: continue
                EvdevInjector.sendKey(kc, now)
            }
            lastGamepadButtons = buttons
            return
        }
        // ② root 通道：手柄按钮 → 系统按键（KEYCODE_BUTTON_*），不需要 INJECT_EVENTS；
        //   摇杆暂时不注入（input 命令没有摇杆语义，后续可走 uinput 虚拟手柄）。
        if (RootInput.available) {
            for (bit in 0..15) {
                val now = (buttons ushr bit) and 1 == 1
                val was = (lastGamepadButtons ushr bit) and 1 == 1
                if (now == was) continue
                val kc = GAMEPAD_KEYCODES[bit] ?: continue
                RootInput.run("input keyevent $kc")
            }
            lastGamepadButtons = buttons
            return
        }
        val svc = ApxAccessibilityService.instance ?: return
        if (svc.checkSelfPermission("android.permission.INJECT_EVENTS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("TvInjector", "手柄注入需 INJECT_EVENTS 权限（adb shell appops set ${svc.packageName} android:inject_events allow）")
            return
        }
        val im = svc.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        for (bit in 0..15) {
            val now = (buttons ushr bit) and 1 == 1
            val was = (lastGamepadButtons ushr bit) and 1 == 1
            if (now == was) continue
            val kc = GAMEPAD_KEYCODES[bit] ?: continue
            injectGamepadKey(im, now, kc)
        }
        lastGamepadButtons = buttons
        val t = SystemClock.uptimeMillis()
        val evt = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, x / 127f)
                setAxisValue(MotionEvent.AXIS_Y, y / 127f)
                setAxisValue(MotionEvent.AXIS_Z, rx / 127f)
                setAxisValue(MotionEvent.AXIS_RZ, ry / 127f)
            }),
            0, 0, 0f, 0f, 0, 0, InputDevice.SOURCE_GAMEPAD, 0
        )
        injectGamepadEvent(im, evt)
        evt.recycle()
    }

    private fun injectGamepadKey(im: InputManager, down: Boolean, keyCode: Int) {
        val t = SystemClock.uptimeMillis()
        val ev = KeyEvent(t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
        ev.source = InputDevice.SOURCE_GAMEPAD
        injectGamepadEvent(im, ev)
    }

    private val injectInputEventMethod by lazy {
        try {
            InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (_: Throwable) { null }
    }
    private fun injectGamepadEvent(im: InputManager, ev: InputEvent) {
        // INJECT_INPUT_EVENT_MODE_ASYNC == 0；反射规避部分 SDK stub 未暴露该隐藏 API
        injectInputEventMethod?.invoke(im, ev, 0)
    }

    private val GAMEPAD_KEYCODES = arrayOf<Int?>(
        KeyEvent.KEYCODE_BUTTON_A,      // 0
        KeyEvent.KEYCODE_BUTTON_B,      // 1
        KeyEvent.KEYCODE_BUTTON_X,      // 2
        KeyEvent.KEYCODE_BUTTON_Y,      // 3
        KeyEvent.KEYCODE_BUTTON_L1,     // 4
        KeyEvent.KEYCODE_BUTTON_R1,     // 5
        KeyEvent.KEYCODE_BUTTON_L2,     // 6
        KeyEvent.KEYCODE_BUTTON_R2,     // 7
        KeyEvent.KEYCODE_BUTTON_SELECT, // 8
        KeyEvent.KEYCODE_BUTTON_START,  // 9
        KeyEvent.KEYCODE_BUTTON_C,      // 10
        KeyEvent.KEYCODE_BUTTON_Z,      // 11
        KeyEvent.KEYCODE_BUTTON_MODE,   // 12
        KeyEvent.KEYCODE_BUTTON_THUMBL, // 13
        KeyEvent.KEYCODE_BUTTON_THUMBR, // 14
        null                            // 15
    )
}
