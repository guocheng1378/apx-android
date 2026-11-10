package com.allperiph.ui

import androidx.compose.runtime.mutableFloatStateOf

/**
 * 全局界面缩放（成宇 2026-11-07「或者做成可以无极自定义 dpi 缩放」）。
 *
 * ## 为什么需要它
 * 此前全仓只有「键帽密度」三档（[com.allperiph.ui.KeyPref]）与设备类型 —— 弹窗 /
 * 编辑页这类内容密度高的面，元素稍大就横向溢出、纵向装不下。这里存 0.80~1.25 的
 * 乘数，**两处**把它乘进 LocalDensity：
 *  · 主壳 `ApShellActivity.setContent`（四页整体）；
 *  · `ApComponents.DialogCard`（弹窗是独立窗口独立组合，只在主壳供它收不到 ——
 *    这正是「缩放设了弹窗没反应」的坑）。
 *
 * ## 读取口径
 * [current] 是 Compose 状态：设置页拖滑条即刻重组生效，不用 recreate。
 * [load] 由 ApShellActivity.onCreate 调一次（幂等）；悬浮球等非主壳宿主
 * 目前按 100% 渲染，等有反馈再扩。
 */
object UiScalePref {
    private const val FILE = "apx_ui_scale"
    private const val KEY = "scale"

    /** 下限/上限：再小字就不可读，再大就失去「多看一截」的意义 */
    const val MIN = 0.80f
    const val MAX = 1.25f

    /** 当前缩放（Compose 状态 —— 读它的地方随拖动即时重组） */
    val current = mutableFloatStateOf(1f)

    /** 冷启动读回（幂等，可重复调）。 */
    fun load(ctx: android.content.Context) {
        val sp = ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        current.floatValue = sp.getFloat(KEY, 1f).coerceIn(MIN, MAX)
    }

    /** 设置页滑条写入：内存态 + 持久化一次到位（非拖动路径的调用方用）。 */
    fun set(ctx: android.content.Context, value: Float) {
        setLive(value)
        persist(ctx)
    }

    /**
     * 拖动中的逐帧更新：**只改内存态，不碰磁盘**（成宇 2026-11-10「界面调节，又开始卡着了」）。
     * 此前这里每帧 `.edit().putFloat().apply()` —— apply 虽异步，但每次调用都往
     * SharedPreferences 的待写队列塞一份全量快照，拖动全程（几十上百帧）在主线程
     * 排队落盘，正是调节发卡的来源。内存态 [current] 是唯一渲染来源，先改它即是
     * 逐帧实况预览；持久化交给 [persist] 在松手时一次落盘。
     */
    fun setLive(value: Float) {
        current.floatValue = value.coerceIn(MIN, MAX)
    }

    /** 落盘当前缩放（幂等；滑条 onValueChangeFinished 调）。 */
    fun persist(ctx: android.content.Context) {
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putFloat(KEY, current.floatValue).apply()
    }
}
