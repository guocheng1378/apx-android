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

    /** 手柄按钮上一帧状态（用于边沿检测） */
    private var lastGamepadButtons = 0

    fun init(c: Context) {
        ctx = c.applicationContext
        TvOverlay.init(c.applicationContext)
        refreshScreen()
    }

    fun onDestroy() {
        TvOverlay.destroy()
        ctx = null
    }

    fun systemReady(): Boolean = ApxAccessibilityService.isReady()

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
        if (systemReady()) ApxAccessibilityService.instance?.tap(x, y)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (systemReady()) ApxAccessibilityService.instance?.swipe(x1, y1, x2, y2, ms)
    }

    fun touchDown(fx: Float, fy: Float) {
        touchDownX = fx * screenW
        touchDownY = fy * screenH
    }

    fun touchUp(fx: Float, fy: Float) {
        val ux = fx * screenW
        val uy = fy * screenH
        if (touchDownX < 0f) {
            tapAt(ux, uy)
            return
        }
        val dist = kotlin.math.hypot((ux - touchDownX).toDouble(), (uy - touchDownY).toDouble())
        if (dist < 12) tapAt(ux, uy) else swipe(touchDownX, touchDownY, ux, uy, 180)
        touchDownX = -1f
        touchDownY = -1f
    }

    fun key(kc: Int, down: Boolean) {
        if (!down || !systemReady()) return
        when (kc) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(0f, -NUDGE)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0f, NUDGE)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> click()
            KeyEvent.KEYCODE_DEL -> ApxAccessibilityService.instance?.deleteChar()
            KeyEvent.KEYCODE_SPACE -> ApxAccessibilityService.instance?.typeText(" ")
            // ★ 遥控器套（HotkeyTemplates "tv"）的「返回 / 主页」：服务端已把 0x29(Esc)
            //   映射成 KEYCODE_BACK、0x4A(Home) 映射成 KEYCODE_HOME，原先这里没有分支 →
            //   被**静默丢弃**（被控手机点「返回/主页」毫无反应）。无障碍服务注入不了任意按键，
            //   但这三个全局动作是它明确支持的。
            KeyEvent.KEYCODE_BACK -> ApxAccessibilityService.instance?.back()
            KeyEvent.KEYCODE_HOME -> ApxAccessibilityService.instance?.home()
            KeyEvent.KEYCODE_APP_SWITCH -> ApxAccessibilityService.instance?.recents()
            KeyEvent.KEYCODE_ESCAPE -> ApxAccessibilityService.instance?.back()
            else -> {
                // 其余（含 Ctrl/Alt/Shift/Win 修饰键 113/59/57/117/114/60/58/118，由键盘帧的
                // mod 位图折出）：本机没有组合键注入语义，明确忽略；媒体键走 consumer 帧。
            }
        }
    }

    private fun nudge(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenW.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenH.toFloat())
        TvOverlay.move(cursorX, cursorY)
    }

    fun text(ch: Char) {
        if (systemReady() && ch != '\u0000') ApxAccessibilityService.instance?.typeText(ch.toString())
    }

    /** 多媒体位图（与 CONSUMER_MAP 同序）：音量/静音用 AudioManager，播放控制尽力而为 */
    fun consumer(bitmap: Int) {
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
        if (systemReady()) ApxAccessibilityService.instance?.typeText(text)
    }

    /** 手柄：buttons 16 位位图 + 双摇杆 4 轴（i8，约 -127..127）。
     *  走 InputManager.injectInputEvent（需 INJECT_EVENTS 权限）；AccessibilityService 无法注入手柄。 */
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
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
