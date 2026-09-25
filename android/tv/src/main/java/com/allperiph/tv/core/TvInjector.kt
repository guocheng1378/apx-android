package com.allperiph.tv.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
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

    fun init(c: Context) {
        ctx = c.applicationContext
        TvOverlay.init(c.applicationContext)
        refreshScreen()
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
            else -> { /* 其他键（媒体等）由 consumer 处理 */ }
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
        if (systemReady()) ApxAccessibilityService.instance?.typeText(text)
    }
}
