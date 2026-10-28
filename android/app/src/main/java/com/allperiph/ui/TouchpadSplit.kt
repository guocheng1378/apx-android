package com.allperiph.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.allperiph.R
import com.allperiph.shared.util.Log

/**
 * 横屏触控板页左右分栏 —— 运行时重排（旋屏不重建 Activity）。
 *
 * 旧 MainActivity 挂了 configChanges=orientation：旋屏不走重建，res/layout-land/ 不会
 * 重新加载（applyOrientationLayout 注释已注明这条路不通），所以分栏只能在现有视图
 * 树上做 removeView/addView 重挂载：
 *
 *  横屏：左 2/3（顶部操作行「文件 剪贴板 屏幕 手柄 遥控」+ 手势面），
 *        右 1/3（快捷键：标题 + 编辑 + 网格），中缝 1dp 分隔线；
 *  竖屏：还原旧 activity_main.xml 的原始竖排（4 子：操作行/手势面/快捷键条/网格）。
 *
 * 视图实例全程不变 —— findViewById 缓存（touchHint/chipRow/touchArea）、
 * 旧 TouchpadFragment.ensureViews 绑定、按钮 clickListener、HotkeyBoard 持有的
 * chipRow、手势面里的液态光标全部继续有效；只换父容器与 LayoutParams。
 * 网格单元格是等分权重（HotkeyBoard.render），挪进窄栏自动收缩，无需重渲染。
 *
 * 触发：[bind] 挂 OnGlobalLayoutListener（旋屏必有布局 pass）+ bind 时主动 sync 一次
 * （覆盖冷启动即横屏）；幂等判断（朝向与结构状态一致直接返回）保证不叠加。
 * layout-land/activity_main.xml 若已是分栏结构（冷启动横屏），识别后直接采认。
 *
 * ⚠️ 重挂视图前必须先 [detach]：被挂过的视图再 addView 会抛
 * "The specified child already has a parent"。横竖屏来回切时尤其容易踩 ——
 * restore 里把那四个视图从 left/right 内部摘出来这一步曾漏掉，导致转回竖屏必崩。
 */
internal class TouchpadSplit(private val act: Activity) {

    private var applied = false // true = 当前页面已是横屏分栏结构
    private var observer: ViewTreeObserver? = null

    private val listener = ViewTreeObserver.OnGlobalLayoutListener { sync() }

    fun bind(decor: View) {
        val o = decor.viewTreeObserver
        observer = o
        o.addOnGlobalLayoutListener(listener)
        sync()
    }

    fun unbind() {
        val o = observer
        if (o != null && o.isAlive) o.removeOnGlobalLayoutListener(listener)
        observer = null
    }

    /** 朝向与结构状态不一致才重排；只在主线程布局 pass 里跑。 */
    private fun sync() {
        val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land == applied) return
        val page = act.findViewById<LinearLayout>(R.id.pageTouchpad) ?: return
        val ok = if (land) split(page) else restore(page)
        if (ok) {
            applied = land
            Log.i(TAG, "sync: landscape=$land -> children=${page.childCount}")
        } else {
            Log.i(TAG, "sync skipped: landscape=$land applied=$applied children=${page.childCount}")
        }
    }

    /** 横屏：左栏 weight=2（操作行 + 手势面），分隔线，右栏 weight=1（快捷键） */
    private fun split(page: LinearLayout): Boolean {
        if (isSplit(page)) return true // 冷启动横屏的 layout-land 已是分栏，直接采认
        if (!isVertical(page)) return false
        val topRow = page.getChildAt(0) as? LinearLayout ?: return false
        val touchArea = page.getChildAt(1)
        val chipSection = page.getChildAt(2) as? LinearLayout ?: return false
        val scroll = page.getChildAt(3) as? ScrollView ?: return false

        // 屏幕/手柄/遥控：从快捷键条挪进顶部操作行（顺序：文件 剪贴板 屏幕 手柄 遥控）
        for (id in intArrayOf(R.id.btnScreen, R.id.btnGamepad, R.id.btnRemote)) {
            val btn = chipSection.findViewById<TextView>(id) ?: continue
            (btn.parent as? ViewGroup)?.removeView(btn)
            (btn.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(16)
            topRow.addView(btn)
        }

        // 同 restore()：重挂前先摘，保持"任何 addView 之前该视图已无父"的不变式
        detach(topRow)
        detach(touchArea)
        detach(chipSection)
        detach(scroll)
        page.removeAllViews()
        page.orientation = LinearLayout.HORIZONTAL

        val left = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f)
        }
        topRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        touchArea.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        left.addView(topRow)
        left.addView(touchArea)

        val divider = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            val a = act.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
            background = a.getDrawable(0)
            a.recycle()
            if (background == null) setBackgroundColor(0x2E000000) // drawable 取不到时的兜底
        }

        val right = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        chipSection.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) // 100dp 限高 → 撑满右栏可滚
        right.addView(chipSection)
        right.addView(scroll)

        page.addView(left)
        page.addView(divider)
        page.addView(right)
        return true
    }

    /** 竖屏：还原 XML 原始竖排（顺序与 LayoutParams 按 activity_main.xml 定值恢复） */
    private fun restore(page: LinearLayout): Boolean {
        if (isVertical(page)) return true
        if (!isSplit(page)) return false
        val left = page.getChildAt(0) as? LinearLayout ?: return false
        val right = page.getChildAt(2) as? LinearLayout ?: return false
        if (left.childCount < 2 || right.childCount < 2) return false
        val topRow = left.getChildAt(0)
        val touchArea = left.getChildAt(1)
        val chipSection = right.getChildAt(0) as? LinearLayout ?: return false
        val scroll = right.getChildAt(1) as? ScrollView ?: return false

        // 三个按钮移回快捷键条（原顺序：标签 屏幕 手柄 遥控 编辑 —— 从下标 1 起依次插）
        var idx = 1
        for (id in intArrayOf(R.id.btnScreen, R.id.btnGamepad, R.id.btnRemote)) {
            val btn = topRow.findViewById<TextView>(id) ?: continue
            (btn.parent as? ViewGroup)?.removeView(btn)
            (btn.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
            chipSection.addView(btn, idx++)
        }

        // ⚠️ 必须先把这四个从 left/right **内部**摘出来再重挂：
        //   `page.removeAllViews()` 只摘直接子节点（left / divider / right），
        //   topRow / touchArea / chipSection / scroll 仍挂在 left/right 身上，
        //   直接 page.addView(...) 会抛
        //   "The specified child already has a parent" —— 横屏转回竖屏时必崩（真机实测）。
        detach(topRow)
        detach(touchArea)
        detach(chipSection)
        detach(scroll)
        page.removeAllViews()
        page.orientation = LinearLayout.VERTICAL
        topRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        touchArea.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        chipSection.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100)) // XML 固定 100dp
        page.addView(topRow)
        page.addView(touchArea)
        page.addView(chipSection)
        page.addView(scroll)
        return true
    }

    // 结构识别：竖排 = 4 子（第 2 子是手势面、第 4 子是网格 ScrollView）
    private fun isVertical(page: LinearLayout): Boolean =
        page.childCount == 4 &&
            page.getChildAt(1).id == R.id.touchArea &&
            page.getChildAt(3) is ScrollView

    // 分栏 = 3 子（左栏内第 2 子是手势面）
    private fun isSplit(page: LinearLayout): Boolean {
        if (page.childCount != 3) return false
        val left = page.getChildAt(0) as? LinearLayout ?: return false
        return left.childCount >= 2 && left.getChildAt(1).id == R.id.touchArea
    }

    /**
     * 从当前父容器里摘下来。
     *
     * 重挂载视图前**必须**先摘：`ViewGroup.addView` 对被挂过的视图会抛
     * `IllegalStateException: The specified child already has a parent`
     * —— 这是本类唯一踩过的崩溃（横屏转回竖屏必崩，真机复现）。
     */
    private fun detach(v: View?) {
        (v?.parent as? ViewGroup)?.removeView(v)
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "TouchpadSplit"
    }
}