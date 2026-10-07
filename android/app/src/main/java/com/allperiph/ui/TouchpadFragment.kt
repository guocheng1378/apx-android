package com.allperiph.ui

import android.app.Fragment
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.R
import com.allperiph.hid.HotkeyTemplates
import com.allperiph.core.ModuleId
import com.allperiph.shared.util.Log

/**
 * 触控板页 Fragment：手势面 + 液态光标 + 快捷键网格。
 *
 * 从 [MainActivity] 拆出，通过 [Host] 接口与宿主通信。
 */
class TouchpadFragment : Fragment() {

    /** 宿主 Activity 必须实现此接口 */
    interface Host {
        fun getTouchpadHandler(): Handler
        fun feedGesture(ev: MotionEvent)
        fun isTouchpadPage(): Boolean
        fun onLongPressArmDrag()
        fun getHotkeyBoard(): HotkeyBoard
        fun renderChips()
        fun syncSortSwitch()
        fun templateColor(): Int
        fun softTint(color: Int): Int
        fun dp(v: Int): Int
    }

    private var host: Host? = null
    private val handler by lazy { host?.getTouchpadHandler() ?: Handler(Looper.getMainLooper()) }

    // Touchpad page views (in activity_main.xml pageTouchpad)
    private lateinit var touchHint: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var touchArea: FrameLayout
    private lateinit var touchCursor: TouchCursorView

    private var frameCount = 0L
    private var lastShown = 0L

    private val dragTask = Runnable {
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.armDrag()
    }

    companion object {
        const val TAG = "TouchpadFragment"
    }

    override fun onAttach(activity: android.app.Activity?) {
        super.onAttach(activity)
        host = activity as? Host
            ?: throw IllegalStateException("${activity?.javaClass?.simpleName} must implement TouchpadFragment.Host")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return
        val act = activity ?: return

        touchHint = act.findViewById(R.id.tvTouchHint)
        chipRow = act.findViewById(R.id.chipRow)
        touchArea = act.findViewById(R.id.touchArea)
        touchCursor = TouchCursorView(act)
        touchArea.addView(touchCursor, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onDetach() {
        handler.removeCallbacks(dragTask)
        host = null
        super.onDetach()
    }

    // ==================== Touch gesture ====================

    /** 宿主把 Activity.onTouchEvent 转给本方法（平台 Fragment 收不到 Activity 的触摸事件）。
     *  @return true 表示已消费（触控板页手势一律消费）。 */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        val h = host ?: return false
        if (!h.isTouchpadPage()) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handler.postDelayed(dragTask, 550)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(dragTask)
        }
        h.feedGesture(ev)

        if (::touchCursor.isInitialized && ::touchArea.isInitialized) {
            val loc = IntArray(2)
            touchArea.getLocationInWindow(loc)
            touchCursor.onGesture(ev, loc[0].toFloat(), loc[1].toFloat())
        }
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastShown > 250) { // 4Hz 刷新，避免 UI 抖动
            lastShown = now
            if (frameCount <= 3) touchHint.visibility = View.INVISIBLE // 手指落下后收起提示文字
        }
        return true
    }

    // ==================== Hotkey grid ====================

    fun boardStyle(): HotkeyBoard.Style {
        val h = host ?: return HotkeyBoard.Style()
        val tint = if (ThemeSkin.picked(activity, ThemeSkin.HOTKEY).isNotBlank()) {
            ThemeSkin.current(activity, ThemeSkin.HOTKEY).accent
        } else {
            HotkeyTemplates.tint(activity, h.templateColor())
        }
        return HotkeyBoard.Style(
            chipText = tint,
            chipBg = h.softTint(tint),
            chipStroke = (tint and 0x00FFFFFF) or (0x40 shl 24),
            chipRadiusDp = 14,
            padVDp = 11,
            perRow = 4,
            fixedRows = 0,
            stretchRows = false,
            accent = resources.getColor(R.color.md_primary),
            textPrimary = resources.getColor(R.color.md_on_surface),
            textSecondary = resources.getColor(R.color.md_on_surface_variant),
        )
    }

    fun renderChips() {
        host?.getHotkeyBoard()?.render()
    }

    /** 恢复提示文字（切到触控板页时调用） */
    fun showHint() {
        if (::touchHint.isInitialized) touchHint.visibility = View.VISIBLE
    }
}