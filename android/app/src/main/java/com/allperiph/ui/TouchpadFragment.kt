package com.allperiph.ui

import android.app.Fragment
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.hid.HotkeyTemplates
import com.allperiph.core.ModuleId
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.util.Log
import com.allperiph.wireless.ControlTarget

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

    /** 横屏分栏重排器：旋屏不重建 Activity（configChanges），在布局 pass 里换结构 */
    private var touchpadSplit: TouchpadSplit? = null

    /** 物理蓝牙键盘 → 电脑 转发桥（Window.Callback 包装，见 PhysKeyBridge） */
    private var physKeyBridge: PhysKeyBridge? = null

    private var frameCount = 0L
    private var lastShown = 0L

    private val dragTask = Runnable {
        val m = AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule
        // 锁定成功才提示：手指移动过多 / 多指时锁不上，这时候弹提示是误导
        if (m?.armDrag() == true) showDragHint()
    }

    /**
     * 长按锁定拖拽的**可见**提示 —— 光震动不够：手指此刻正压在屏幕上，液态光标被手挡住，
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

        /** 顶部「文件发送」选文件请求码（与 MainActivity 的 REQ_PICK_FILE=9002 区分） */
        const val REQ_PICK_FILE = 9011
    }

    override fun onAttach(activity: android.app.Activity?) {
        super.onAttach(activity)
        host = activity as? Host
            ?: throw IllegalStateException("${activity?.javaClass?.simpleName} must implement TouchpadFragment.Host")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 防御性入口：onCreateView 返回 null（寄生式）时平台根本不回调本方法
        // （FragmentManagerImpl 只在 mView!=null 时才调），真正的绑定入口是
        // 宿主挂载后显式调用的 ensureViews()。
        ensureViews()
    }

    /**
     * 幂等绑定宿主交来的视图 + 挂液态光标。此前绑定全压在 onViewCreated 里
     * 而它从不执行 → touchCursor 只在这里赋值，viewsReady 恒 false —— v196 起
     * 液态光标从未渲染、长按拖拽提示从没出过（手势核心经 host.feedGesture 转发
     * 一直活着，所以无人报障）。与 StatusFragment.ensureBooted 同款修法。
     */
    fun ensureViews() {
        if (::touchHint.isInitialized) return
        val h = host ?: return
        // 视图由宿主交过来（见 Host 注释：自己 findViewById 会因时序拿不到，lateinit 闪退）
        touchHint = h.hostTouchHint()
        chipRow = h.hostChipRow()
        touchArea = h.hostTouchArea()
        touchCursor = TouchCursorView(h.hostTouchArea().context)
        // 先摘掉自己那份再挂（万一被重复调用），避免光标叠加
        (touchArea as? FrameLayout)?.let { area ->
            area.removeView(touchCursor)
            area.addView(touchCursor, FrameLayout.LayoutParams(-1, -1))
        }
        // 顶部「文件发送 / 剪贴板」入口（v1.8：从设置页提到触控板顶部）。
        // 这两个按钮是 activity_main.xml 里的布局视图，随 setContentView 就绪，
        // 晚于 add Fragment（Host 注释：Activity 字段在 add 前已 findViewById 完毕），
        // 寄生 Fragment 无自身视图树，必须 activity.findViewById；拿不到就静默不绑。
        activity?.let { a ->
            a.findViewById<TextView>(R.id.btnFileSend)?.setOnClickListener { pickFileToSend() }
            a.findViewById<TextView>(R.id.btnClipSend)?.setOnClickListener { sendClipToPeer() }
            // 横屏分栏：旋屏不重建 Activity（configChanges 含 orientation，layout-land
            // 不重新加载），改在布局 pass 里幂等重排触控板页结构（见 TouchpadSplit）。
            touchpadSplit = TouchpadSplit(a).also { it.bind(a.window.decorView) }
            // 物理蓝牙键盘：包装 Window.Callback 抢先拦 KeyEvent → HidKeys 上行到电脑；
            // 聚焦输入框 / 无出口 / 认不出的键全部放行，手机本地用法不受影响。
            physKeyBridge = PhysKeyBridge(a).also { it.attach(a.window) }
        }
    }

    /** 视图是否已就绪 —— 未就绪时触摸事件直接忽略（绝不碰 lateinit 字段）。 */
    private val viewsReady: Boolean
        get() = ::touchHint.isInitialized && ::touchArea.isInitialized && ::touchCursor.isInitialized

    override fun onDetach() {
        physKeyBridge?.detach()
        physKeyBridge = null
        touchpadSplit?.unbind()
        touchpadSplit = null
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
        val now = SystemClock.uptimeMillis()
        if (now - lastShown > 250) { // 4Hz 刷新，避免 UI 抖动
            lastShown = now
            if (frameCount <= 3) touchHint.visibility = View.INVISIBLE // 手指落下后收起提示文字
        }
        return true
    }

    // ==================== Top actions：文件发送 / 剪贴板 ====================

    /** 顶部「文件发送」：与设置页 pickFileToSend 同口径（入口从设置页提上来） */
    private fun pickFileToSend() {
        if (ControlTarget.host.isEmpty() || !ControlTarget.isControlling()) {
            toast(R.string.ui_main_toast_device_not)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // MainActivity.onActivityResult 第一行就调 super，所以结果能派发到这里
        if (requestCode == REQ_PICK_FILE && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val hostIp = ControlTarget.host
            val a = activity ?: return
            Thread {
                val ok = runCatching { FileSender.send(a, hostIp, uri) }.getOrDefault(false)
                a.runOnUiThread {
                    toast(if (ok) R.string.ui_main_text_file_send_done else R.string.ui_main_text_device_send_failed_confirm)
                }
            }.start()
        }
    }

    /** 顶部「剪贴板」：读本机剪贴板发到对端（与 TouchpadActivity 的 clip chip 同口径） */
    private fun sendClipToPeer() {
        val c = ControlTarget.controlClient
        if (c == null || !ControlTarget.isControlling()) {
            toast(R.string.ui_main_toast_device_not)
            return
        }
        val cm = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.let {
            if (it.itemCount > 0) it.getItemAt(0).coerceToText(activity)?.toString() else null
        }
        if (text.isNullOrEmpty()) {
            toast(R.string.ui_touchpad_toast_clipboard_empty)
            return
        }
        val ok = c.sendClipboard(text)
        toast(if (ok) R.string.ui_touchpad_text_clipboard_send_done else R.string.ui_touchpad_text_send_failed)
    }

    private fun toast(res: Int) {
        val a = activity ?: return
        Toast.makeText(a, a.getString(res), Toast.LENGTH_SHORT).show()
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
            accent = ThemeSkin.primary(activity),
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