package com.allperiph.shared.inject

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.allperiph.shared.accessibility.ApxAccessibilityService
import com.allperiph.shared.ime.ApxImeService
import com.allperiph.shared.input.EvdevInjector
import com.allperiph.shared.input.RootInput
import com.allperiph.shared.input.UinputGamepad
import com.allperiph.shared.util.Log

/**
 * 控制帧的最终落点：把对端发来的光标 / 按键 / 文本 / 剪贴板转成对系统的真实操作。
 *
 *  - 光标：始终用全局浮层可视化（[InjectorPlatform.overlay]）。
 *  - 系统注入：仅当 [ApxAccessibilityService] 已启用时生效；否则仅可视化（演示用）。
 *
 * 手机端与 TV 端原先是两份几乎一致的拷贝，差异集中在：
 *  - 光标浮层所在包不同 → 由 [InjectorPlatform.overlay] 注入；
 *  - tapAt / swipe / longPress / scroll 的通道优先级不同（TV 端 SELinux Enforcing，
 *    root 的 `input` 命令被静默丢弃，故无障碍优先）→ 由 [InjectorPlatform.accessibilityFirst] 控制；
 *  - 线程名前缀、主线程切换、toast、额外能力自检项、额外按键分支、文案 → 见 [InjectorPlatform]。
 *
 * 各端在被控服务初始化时创建自己的 [InjectorPlatform] 实现并调用 [init]。
 *
 * 注：[platform] 为 nullable —— 手机端的自检入口（MainActivity.showControlledCaps）可在
 * 被控服务尚未启动（即 [init] 未调用）时被用户点开，此时各通道自然全部 not-ready，
 * [channelText] 退回"未开启"档、[capabilities] 返回空表，不抛 lateinit 异常。
 */
object TvInjector {
    private const val TAG = "TvInjector"

    private var ctx: Context? = null
    private var screenW = 0
    private var screenH = 0

    private var platform: InjectorPlatform? = null

    /** 应用上下文（[init] 后可用），供平台的 toast() / 通知权限自检等需要 Context 的扩展使用 */
    val appContext: Context? get() = ctx

    /** 浮层（由平台注入；[init] 前为 null，相关操作退化为 no-op） */
    private val overlay: CursorOverlay? get() = platform?.overlay

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

    /** [init] 是否已跑过（配合 [currentPlatform] 做幂等） */
    private var initialized = false

    fun init(c: Context, platform: InjectorPlatform) {
        val ac = c.applicationContext
        ctx = ac
        // ⚠️ 幂等：同一个 platform 重复 init 会**再起两条后台线程**并重复探测 root。
        // TV 端服务 onCreate 与 MainActivity onCreate 各调一次，而用户每次回到主界面
        // 又会走一次 MainActivity.onCreate —— 反复进出界面会让 apx-tv-evdev /
        // apx-tv-uinput 线程不断累积（没有句柄，既不能复用也无法停止）。
        if (initialized && this.platform === platform) return
        this.platform = platform
        platform.overlay.init(ac)
        refreshScreen(force = true)
        // 通道按"能拿到多少能力"排序，全部 fire-and-forget：
        //  ① evdev 内核注入（免 root，任意按键）—— 多数手机会因 /dev/input 权限不可用而自动跳过
        //  ② root `input`（全键鼠）
        //  ③ 无障碍（只能点/滑/输入框打字）
        Thread({ runCatching { EvdevInjector.tryStart() } }, "${platform.threadPrefix}-evdev").start()
        //  ④ uinput 虚拟手柄：手柄**摇杆**只有它能注入（见 gamepad()）
        Thread({ runCatching { UinputGamepad.tryStart() } }, "${platform.threadPrefix}-uinput").start()
        RootInput.tryStart()
        initialized = true
    }

    fun onDestroy() {
        platform?.overlay?.destroy()
        ctx = null
        initialized = false
    }

    fun systemReady(): Boolean = ApxAccessibilityService.isReady()

    /**
     * 当前可用的注入通道（人话）。文案与档位顺序由 [InjectorPlatform] 决定，
     * 状态判断（root / evdev / 无障碍 / uinput 是否就绪）留在这份共享逻辑里。
     * [init] 前调用退回手机端"未开启"档（与原手机端 object 在服务未启动时的行为一致）。
     */
    fun channelText(): String {
        val p = platform ?: return "未开启 · 只能看到光标，点不动"
        p.channelTextUinputGamepad()?.let { txt -> if (UinputGamepad.ready) return txt }
        if (RootInput.available) return p.channelTextFullKey()
        if (EvdevInjector.available) return p.channelTextEvdev()
        if (systemReady()) return p.channelTextAccessibility()
        return p.channelTextNone()
    }

    /** 是否具备「任意按键」级别的能力 */
    fun fullKeyReady(): Boolean = RootInput.available || EvdevInjector.available

    /**
     * 控制能力自检清单：`(名称, 是否就绪, 没就绪时该怎么办)`。
     * 共用项的文案与顺序由 [InjectorPlatform.capabilitySpecs] 给出，状态由本处统一填；
     * 各端独有的项（如 TV 的通知权限）走 [InjectorPlatform.extraCapabilities]。
     * [init] 前调用返回空表（服务未启动时各通道本来就全是 not-ready）。
     */
    fun capabilities(): List<Triple<String, Boolean, String>> {
        val p = platform ?: return emptyList()
        val out = ArrayList<Triple<String, Boolean, String>>(p.capabilitySpecs().size + 4)
        for ((key, name, how) in p.capabilitySpecs()) {
            val ready = when (key) {
                "root" -> RootInput.available
                "evdev" -> EvdevInjector.available
                "a11y" -> systemReady()
                "overlay" -> overlay?.isReady ?: false
                "ime" -> ApxImeService.isActive()
                "uinput" -> UinputGamepad.ready
                else -> false
            }
            out.add(Triple(name, ready, how))
        }
        out.addAll(p.extraCapabilities())
        return out
    }

    /** 在被控端屏幕上弹提示（委托给平台；TV 端有实现）。[init] 前为 no-op。 */
    fun toast(msg: String) { platform?.toast(msg) }

    /** 屏幕尺寸刷新间隔：鼠标帧每秒几十次，没必要每帧都查一遍（含 getSystemService + new DisplayMetrics） */
    private const val SCREEN_REFRESH_MS = 1_000L
    private var lastScreenRefreshMs = 0L

    private fun refreshScreen(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && screenW > 0 && screenH > 0 && now - lastScreenRefreshMs < SCREEN_REFRESH_MS) return
        lastScreenRefreshMs = now
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
        // 关键：本函数被握手/收流线程调用，手机端浮窗操作必须切回主线程（见各端 platform.runOnMain）；
        // TV 端不需要切线程（needsMainThreadSwitch=false）。
        val p = platform ?: return
        val block: () -> Unit = {
            // 刚连上：立刻取一次真实尺寸（不能沿用节流里的旧值，横竖屏切换后它们会过期）
            refreshScreen(force = true)
            if (on) overlay?.move(cursorX, cursorY) else overlay?.hide()
        }
        if (p.needsMainThreadSwitch) p.runOnMain(block) else block()
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
        overlay?.move(cursorX, cursorY)
    }

    fun click() = tapAt(cursorX, cursorY)

    fun tapAt(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (platform?.accessibilityFirst == true) {
            // TV：无障碍手势优先，root 兜底（SELinux Enforcing 下 root 的 input 会被静默丢弃）
            if (systemReady() && ApxAccessibilityService.instance?.tap(x, y) == true) {
                Log.i(TAG, "tapAt($xi, $yi) 走无障碍手势成功"); return
            }
            if (RootInput.available && RootInput.run("input tap $xi $yi")) return
            Log.w(TAG, "tapAt($xi, $yi) 失败：无障碍与 root 通道均未生效")
        } else {
            // 手机：root 优先，无障碍兜底
            if (RootInput.available && RootInput.run("input tap $xi $yi")) return
            if (systemReady()) ApxAccessibilityService.instance?.tap(x, y)
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val s = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $ms"
        if (platform?.accessibilityFirst == true) {
            if (systemReady() && ApxAccessibilityService.instance?.swipe(x1, y1, x2, y2, ms) == true) return
            if (RootInput.available && RootInput.run(s)) return
        } else {
            if (RootInput.available && RootInput.run(s)) return
            if (systemReady()) ApxAccessibilityService.instance?.swipe(x1, y1, x2, y2, ms)
        }
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
        val xi = x.toInt()
        val yi = y.toInt()
        val s = "input swipe $xi $yi $xi $yi 600"
        if (platform?.accessibilityFirst == true) {
            if (systemReady() && ApxAccessibilityService.instance?.longPress(x, y) == true) return
            if (RootInput.available && RootInput.run(s)) return
        } else {
            if (RootInput.available && RootInput.run(s)) return
            if (systemReady()) ApxAccessibilityService.instance?.longPress(x, y)
        }
    }

    /** 滚轮 → 被控端滚动（一格 ≈ 屏高 1/10；正 = 内容上滚 = 手指上滑）。以前滚轮帧被直接忽略。 */
    fun scroll(notches: Int) {
        if (notches == 0) return
        val step = screenH / 10f * notches
        val s = "input swipe ${cursorX.toInt()} ${cursorY.toInt()} ${cursorX.toInt()} ${(cursorY - step).toInt()} 220"
        if (platform?.accessibilityFirst == true) {
            if (systemReady() && ApxAccessibilityService.instance?.swipe(cursorX, cursorY, cursorX, cursorY - step, 220) == true) return
            if (RootInput.available && RootInput.run(s)) return
        } else {
            if (RootInput.available && RootInput.run(s)) return
            if (systemReady()) ApxAccessibilityService.instance?.swipe(cursorX, cursorY, cursorX, cursorY - step, 220)
        }
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
        // ③ 平台特定的额外按键分支（手机的 Tab / PageUp/Down / MoveHome/End 等）
        if (platform?.handleExtraKey(kc, down, this) == true) return
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
            // 多媒体键：走系统 ACTION_MEDIA_BUTTON 广播（音量/静音另走 AudioManager，见 consumer()）
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            KeyEvent.KEYCODE_MEDIA_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            // ★ 遥控器套（HotkeyTemplates "tv"）的「返回 / 主页」：服务端已把 0x29(Esc)
            //   映射成 KEYCODE_BACK、0x4A(Home) 映射成 KEYCODE_HOME，原先这里没有分支 →
            //   被**静默丢弃**（被控端点「返回/主页」毫无反应）。无障碍服务注入不了任意按键，
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
                Log.w(TAG, "被控按键未支持（无障碍通道无法注入该键）：kc=$kc")
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
            Log.i(TAG, "电源动作：已通过 root 执行 `$cmd`")
            return true
        }
        Log.w(
            TAG,
            "电源动作：没有 root，无法真" + (if (action == 0) "关机" else "重启") + " → 退回软电源键（待机）",
        )
        key(KeyEvent.KEYCODE_POWER, true)
        key(KeyEvent.KEYCODE_POWER, false)
        return false
    }

    /** 在当前光标处做相对位移（也供平台的 handleExtraKey 使用，如 PageUp/Down / MoveHome/End） */
    fun nudge(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenW.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenH.toFloat())
        overlay?.move(cursorX, cursorY)
    }

    /** 当前屏幕宽（像素），供平台的 handleExtraKey（如 MoveHome/End 走全屏位移）使用 */
    val screenWidth: Int get() = screenW

    /** 当前屏幕高（像素） */
    val screenHeight: Int get() = screenH

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
        Log.w(TAG, "文本注入失败（当前没有聚焦的输入框？）：'$s'")
    }

    /** 多媒体位图（与 CONSUMER_MAP 同序）：音量/静音用 AudioManager，播放控制尽力而为 */
    fun consumer(bitmap: Int) {
        // ★ bit3 = 电源：**必须在这里注入**。控制面 onConsumer 里那圈 CONSUMER_MAP
        //   只调了界面分发，**没有调 TvInjector** —— 所以只往 CONSUMER_MAP 里加
        //   "3 to 26" 是不会真的发出电源键的。
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
        val down = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        val up = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, code))
        runCatching { c.sendOrderedBroadcast(down, null) }
        runCatching { c.sendOrderedBroadcast(up, null) }
    }

    /** v184：最近一次程序性剪贴板写入时间（uptimeMs）—— ControlledService 回传抑制用 */
    @Volatile
    var lastProgrammaticClipWriteMs: Long = 0L

    /** 剪贴板文本：写入被控端系统剪贴板，并尝试填入当前聚焦输入框 */
    fun clipboard(text: String) {
        val c = ctx ?: return
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // Android 10+：无焦点的 App setPrimaryClip 会被静默丢弃，这里记下结果用于诊断
        runCatching { cm?.setPrimaryClip(ClipData.newPlainText("APX", text)) }
            .onSuccess {
                lastProgrammaticClipWriteMs = SystemClock.uptimeMillis()
                Log.i(TAG, "远程剪贴板：setPrimaryClip 已调用（后台 App 可能被系统丢弃，看 IME 通道）")
            }
            .onFailure { Log.e(TAG, "远程剪贴板：setPrimaryClip 异常 ${it.message}") }
        // ① 输入法通道：**中文**只有这条最稳 —— root 的 `input text` 只支持 ASCII，
        //    无障碍 ACTION_SET_TEXT 在 MIUI / 自绘输入框上又常被拒。
        if (ApxImeService.commit(text)) { Log.i(TAG, "远程剪贴板：走 IME 上屏成功"); return }
        if (systemReady() && ApxAccessibilityService.instance?.typeText(text) == true) { Log.i(TAG, "远程剪贴板：走无障碍 SET_TEXT 成功"); return }
        // 兜底：剪贴板已经写进去了，退到 ACTION_PASTE
        val pasted = ApxAccessibilityService.instance?.paste(text) == true
        Log.i(TAG, if (pasted) "远程剪贴板：走无障碍 PASTE 成功" else "远程剪贴板：三条通道均未生效（检查全能外设输入法是否设为当前输入法）")
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
        val c = ctx ?: return
        if (c.checkSelfPermission("android.permission.INJECT_EVENTS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "手柄注入需 INJECT_EVENTS 权限（adb shell appops set ${c.packageName} android:inject_events allow）")
            return
        }
        val im = c.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
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
