package com.allperiph.tv.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import com.allperiph.tv.ui.TvOverlay

/**
 * 控制帧的最终落点：把手机发来的光标/按键/文本/剪贴板转成对系统的真实操作。
 *  - 光标：始终用全局浮层可视化（[TvOverlay]）。
 *  - 系统注入：仅当 [ApxAccessibilityService] 已启用时生效；否则仅可视化（演示用）。
 */
object TvInjector {
    private var ctx: Context? = null
    private var screenW = 0
    private var screenH = 0

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
        //  ① evdev 内核注入：**免 root**，任意按键（方向键/组合键/手柄），和真遥控器同级
        //  ② root `input`：evdev 不可用时用
        //  ③ 无障碍：上面都没有时才用（只能点/滑/输入框打字）
        Thread({ runCatching { EvdevInjector.tryStart() } }, "apx-tv-evdev").start()
        //  ④ uinput 虚拟手柄：只有它能给出手柄**摇杆**（按键/摇杆都走它，见 gamepad()）
        Thread({ runCatching { UinputGamepad.tryStart() } }, "apx-tv-uinput").start()
        RootInput.tryStart()
    }

    fun systemReady(): Boolean = ApxAccessibilityService.isReady()

    /**
     * 当前可用的注入通道（人话）。**界面与通知共用同一份文案**，避免两边说法不一致。
     *
     * 三档能力差别极大，必须让用户一眼看到 —— "连上了但点不动 / 方向键只动光标"
     * 是被控端最常见的困惑，根因就是"走的是哪条通道"：
     *  · evdev / root：**任意按键**（方向键 = 真方向键会移动焦点、组合键、手柄按钮）+ 点击滑动
     *  · 仅无障碍：只能点击 / 滑动 / 输入框内打字，**按键全是空的**
     *  · 都没有：只剩光标可视化
     */
    fun channelText(): String = when {
        EvdevInjector.available -> "evdev 内核注入 · 免 root，全键可用"
        RootInput.available -> "root 注入 · 全键可用"
        systemReady() -> "无障碍注入 · 仅点击/滑动/输入框打字，按键不可用"
        else -> "未开启 · 手机只能看到光标，点不动"
    }

    /** 是否具备「任意按键」级别的能力（evdev / root） */
    fun fullKeyReady(): Boolean = EvdevInjector.available || RootInput.available

    /**
     * 控制能力自检清单：`(名称, 是否就绪, 没就绪时该怎么办)`。
     * 电视上没有状态栏提示、用户又看不见日志，"缺哪一项"必须直接列在界面上。
     */
    fun capabilities(): List<Triple<String, Boolean, String>> = listOf(
        Triple(
            "evdev 内核注入（免 root，全键）",
            EvdevInjector.available,
            "本机 /dev/input 不可写（换 root 或无障碍）",
        ),
        Triple("root 注入（全键）", RootInput.available, "未授予 root（可忽略，evdev 已够用）"),
        Triple(
            "无障碍注入（点击 / 滑动 / 打字）",
            systemReady(),
            "到「无障碍 / 辅助功能」里启用本应用",
        ),
        Triple(
            "悬浮窗（把手机光标画在电视上）",
            TvOverlay.isReady,
            "到「显示在其他应用上层」里允许本应用",
        ),
        // ★ 下面三项原先**完全没出现在界面上**，用户无从得知缺什么：
        //   - 输入法：中文打字最稳的一条通道，但要去系统设置里启用一次；
        //   - 虚拟手柄：手柄摇杆唯一的通道；
        //   - 通知权限：没有它，前台服务退化成普通后台服务、更容易被回收。
        Triple(
            "输入法（中文打字最稳的通道）",
            ApxImeService.isActive(),
            "到「输入法 / 键盘」设置里启用「全能外设输入」并切过去",
        ),
        Triple(
            "虚拟手柄（手柄摇杆唯一通道）",
            UinputGamepad.ready,
            "本机 /dev/uinput 不可写（有 root 会自动尝试 chmod）",
        ),
        Triple(
            "通知权限（前台服务常驻所需）",
            notificationsEnabled(),
            "到「应用 → 全能外设 → 通知」里允许",
        ),
    )

    /** 通知权限是否已允许（API 24+ 可查；更早的版本一律视为允许） */
    private fun notificationsEnabled(): Boolean {
        val c = ctx ?: return false
        if (android.os.Build.VERSION.SDK_INT < 24) return true
        return runCatching {
            c.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled()
        }.getOrNull() ?: true
    }

    /**
     * 在被控端屏幕上弹一句提示（电源动作 / 手柄这类"按了但看不见效果"的操作需要它）。
     * 用服务持有的 applicationContext，**不依赖 Activity** —— 界面不在前台时也能提示。
     */
    fun toast(msg: String) {
        val c = ctx ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        refreshScreen()
        if (on) TvOverlay.move(cursorX, cursorY) else TvOverlay.hide()
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

    /** 一笔的落点：不动且≥500ms=长按；不动=轻点；有位移=滑动（真实时长） */
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

    /** 滚轮 → 被控端滚动（一格 ≈ 屏高 1/10；正 = 内容上滚）。以前滚轮帧被直接忽略。 */
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
        // ① evdev 内核注入（**免 root**）：按下与松开都真的发出去 —— 这才像真遥控器，
        //   长按 / 连发 / 组合键 / 焦点移动全都对。方向键在这里第一次变成"真方向键"。
        if (EvdevInjector.available && EvdevInjector.sendKey(kc, down)) return
        if (!down) return
        // ② root 通道：任意按键（含字母 / Tab / F 区）都能真正注入；`input keyevent` 自带 down+up
        if (RootInput.available && RootInput.run("input keyevent $kc")) return
        if (!systemReady()) return
        when (kc) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(NUDGE, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(0f, -NUDGE)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0f, NUDGE)
            KeyEvent.KEYCODE_DPAD_CENTER -> click()
            // 回车：先试输入法（输入框内换行/提交），不行再退回"点一下光标处"
            KeyEvent.KEYCODE_ENTER ->
                if (!ApxImeService.key(KeyEvent.KEYCODE_ENTER, true)) click()
            // 退格：输入法通道成功率最高
            KeyEvent.KEYCODE_DEL ->
                if (!ApxImeService.delete()) ApxAccessibilityService.instance?.deleteChar()
            KeyEvent.KEYCODE_SPACE -> ApxAccessibilityService.instance?.typeText(" ")
            // ★ 遥控器套里最常用的两个键：返回 / 主页。服务端的 HID_MAP 已把
            //   0x29(Esc) 映射成 KEYCODE_BACK、0x4A(Home) 映射成 KEYCODE_HOME，
            //   但原先这里没有对应分支 → 落进 else 被**静默丢弃**（点「返回/主页」毫无反应）。
            //   无障碍服务不能注入任意按键，但 performGlobalAction 明确支持这三个全局动作，
            //   正是电视上真正有用的语义。
            // 电源键：root 下 input keyevent 26 真能锁屏/亮屏；无 root 用全局锁屏动作（API 28+）
            KeyEvent.KEYCODE_POWER -> {
                if (RootInput.available) RootInput.run("input keyevent 26")
                else ApxAccessibilityService.instance?.lockScreen()
            }
            KeyEvent.KEYCODE_BACK -> ApxAccessibilityService.instance?.back()
            KeyEvent.KEYCODE_HOME -> ApxAccessibilityService.instance?.home()
            KeyEvent.KEYCODE_APP_SWITCH -> ApxAccessibilityService.instance?.recents()
            KeyEvent.KEYCODE_ESCAPE -> ApxAccessibilityService.instance?.back()
            else -> {
                // 其余（含 Ctrl/Alt/Shift/Win 修饰键 113/59/57/117/114/60/58/118）：
                // 电视没有组合键语义，只能忽略 —— 与旧行为的区别是「修饰键不再被当成未知键
                // 干扰后续按键边沿」，按下/松开由服务端成对处理。
                // ★ 打日志：以前静默丢弃，用户只看到"按键没反应"，查不到丢了哪个键。
                android.util.Log.w("TvInjector", "TV 被控按键未支持（无障碍通道无法注入）：kc=$kc")
            }
        }
    }

    /**
     * 电源动作（控制帧 opcode `0x22`）：`0`=关机、`1`=重启、`2`=待机。
     *
     * **关机与重启必须有 root**（`reboot -p` / `reboot`）—— Android 的
     * `ACTION_REQUEST_SHUTDOWN` 是系统签名权限，第三方应用拿不到。没有 root 时
     * **如实退回「软电源键」**（待机 / 唤醒）而不是假装关掉了。
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
        // ① evdev：字母/数字/空格直接发**真按键**，不再依赖"当前有没有输入框"或输入法是否切过来
        if (EvdevInjector.available && EvdevInjector.sendChar(ch)) return
        // ② 输入法通道（最稳）：系统输入法是我们时，走 InputConnection —— 系统认定的正规输入
        if (ApxImeService.commit(s)) return
        // ② 无障碍 ACTION_SET_TEXT
        if (systemReady() && ApxAccessibilityService.instance?.typeText(s) == true) return
        // 兜底：ACTION_SET_TEXT 被拒（部分盒子/电视 ROM 常见）→ 剪贴板 + ACTION_PASTE
        if (ApxAccessibilityService.instance?.paste(s) == true) return
        android.util.Log.w("TvInjector", "TV 文本注入失败（当前没有聚焦的输入框？）：'$s'")
    }

    /** 多媒体位图（与 CONSUMER_MAP 同序）：音量/静音用 AudioManager，播放控制尽力而为 */
    fun consumer(bitmap: Int) {
        // ★ bit3 = 电源：**必须在这里注入**。
        //   TcpControlServer.onConsumer 里那圈 CONSUMER_MAP 只调了 TvInputDispatcher
        //   （驱动界面 Toast / 高亮），**没有调 TvInjector** —— 所以只往 CONSUMER_MAP 里加
        //   "3 to 26" 是不会真的发出电源键的（真机症状：点「电源」毫无反应）。
        //   电原本该由系统电源键处理：evdev / root 下就是一颗真电源键（待机/唤醒）。
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

    /** 剪贴板文本：写入被控端系统剪贴板，并尝试填入当前聚焦输入框 */
    fun clipboard(text: String) {
        val c = ctx ?: return
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("APX", text))
        // ① 输入法通道：**中文**只有这条最稳（root 的 input text 只支持 ASCII，
        //    无障碍 ACTION_SET_TEXT 在不少盒子 / 电视 ROM 上会被拒）
        if (ApxImeService.commit(text)) return
        if (systemReady() && ApxAccessibilityService.instance?.typeText(text) == true) return
        // 兜底：剪贴板已写好，退到 ACTION_PASTE
        ApxAccessibilityService.instance?.paste(text)
    }

    /** 手柄：buttons 16 位位图 + 双摇杆 4 轴（i8，约 -127..127）。
     *  优先 evdev（免 root），其次 root，最后 InputManager.injectInputEvent（需 INJECT_EVENTS）。 */
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
        val c = ctx ?: return
        // ① **uinput 虚拟手柄（首选）**：按钮与**摇杆**都是内核级真实输入 ——
        //   系统看到的就是一个真手柄（SOURCE_GAMEPAD + 双摇杆 ABS 轴）。
        //   上面那两条通道都给不出摇杆：evdev 冒充的键盘设备没有 ABS 轴，root 的 input 没有摇杆语义。
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
        // ② evdev：手柄按钮 → 真按键，**不需要 root / INJECT_EVENTS**。
        //   按下与松开分别发（手柄帧本来就是边沿），所以长按、连发都是对的。
        //   摇杆暂不注入（目标设备是键盘，没有 ABS 轴；要做得上 uinput 造虚拟手柄）。
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
        //   摇杆暂时不注入（input 命令没有摇杆语义，后续可走 uinput）。
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
        if (c.checkSelfPermission("android.permission.INJECT_EVENTS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("手柄注入需 INJECT_EVENTS 权限（adb shell appops set ${c.packageName} android:inject_events allow）")
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
