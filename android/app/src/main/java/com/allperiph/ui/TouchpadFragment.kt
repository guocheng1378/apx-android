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

        // 三个视图由宿主直接交过来（v196 第 2 步实测踩坑）：
        // Fragment 自己 findViewById 时，onViewCreated 的执行时机与 Activity 的
        // setupViews() 顺序耦合，一旦它提前返回，lateinit 字段就没赋值，
        // 用户点触控板立刻 UninitializedPropertyAccessException 闪退（真机 21:03 复现）。
        // Activity 侧这几个字段在 add Fragment 之前就已findViewById 完毕，直接复用最稳。
        fun hostTouchHint(): TextView
        fun hostChipRow(): LinearLayout
        fun hostTouchArea(): FrameLayout
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
        val m = AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule
        // 锁定成功才提示：手指移动过多 / 多指时锁不上，这时候弹提示是误导
        if (m?.armDrag() == true) showDragHint()
    }

    /**
     * 长按锁定拖拽的**可见**提示 —— 光震动不够：手指此刻正压在屏幕上、液态光标被手挡住，
     * 而设置页的震动档位是可以关掉的（关掉后长按就完全没有反馈了）。
     * 所以补一行文字，1.6s 后自行收起，不占用常态布局。
     */
    private fun showDragHint() {
        if (!::touchHint.isInitialized) return
        touchHint.setText(R.string.ui_touchpad_text_drag_locked)
        touchHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintTask)
        handler.postDelayed(hideHintTask, 1600)
    }

    private val hideHintTask = Runnable {
        if (::touchHint.isInitialized) touchHint.visibility = View.INVISIBLE
    }

    /** 双指长按 → 右键拖拽（与左键拖拽对称；armRightDrag 此前全仓库零调用） */
    private val rightDragTask = Runnable {
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.armRightDrag()
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
        // 视图由宿主交过来（见 Host 注释：自己 findViewById 会因时序拿不到， lateinit 闪退）
        touchHint = h.hostTouchHint()
        chipRow = h.hostChipRow()
        touchArea = h.hostTouchArea()
        touchCursor = TouchCursorView(h.hostTouchArea().context)
        // 幂等：宿主视图可能已挂过光标（横竖屏切换或重建），先摘掉自己那份再挂，避免叠加
        (touchArea as? FrameLayout)?.let { area ->
            area.removeView(touchCursor)
            area.addView(touchCursor, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** 视图是否已就绪 —— 未就绪时触摸事件直接忽略（绝不碰 lateinit 字段）。 */
    private val viewsReady: Boolean
        get() = ::touchHint.isInitialized && ::touchArea.isInitialized && ::touchCursor.isInitialized

    override fun onDetach() {
        handler.removeCallbacks(dragTask)
        handler.removeCallbacks(rightDragTask)
        handler.removeCallbacks(hideHintTask)
        host = null
        super.onDetach()
    }

    // ==================== Touch gesture ====================

    /** 宿主把 Activity.onTouchEvent 转给本方法（平台 Fragment 收不到 Activity 的触摸事件）。
     *  @return true 表示已消费（触控板页手势一律消费）。 */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        val h = host ?: return false
        if (!h.isTouchpadPage()) return false
        // 视图未就绪就只把手势喂给模块，不碰光标/提示文字 —— 修复「点触控板闪退」：
        // UninitializedPropertyAccessException: lateinit property touchHint
        if (!viewsReady) {
            h.feedGesture(ev)
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handler.postDelayed(dragTask, 550)
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) handler.postDelayed(rightDragTask, 550)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(dragTask)
                handler.removeCallbacks(rightDragTask)
            }
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