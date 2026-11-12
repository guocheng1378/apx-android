package com.allperiph.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 形态自适应（2026-11-01 起）：**按窗口宽度分级**，不判断机型、不判断平台字段。
 *
 * 为什么不用 `Build.MODEL` / `platform == "..."` 那一套：仓库里 pc/web 与 host 侧的
 * platform 分支只用于**能力差异**（能否枚举播放端点这类），不是排版依据；同一台设备
 * 分屏、折叠、投屏后窗口尺寸都会变，按机型写死必然错。窗口宽度是唯一稳定的输入。
 *
 * Miuix 0.9.4 自身没有 WindowSizeClass 之类的公开 API（模块只有 ui / preference /
 * icons / blur / squircle / shader / nav / window），所以这里落成两层：
 *  · 分级：本文件按 Material 的宽度断点把窗口分成 Compact / Medium / Expanded；
 *  · 形态：各级用**Miuix 自带的组件**表达（Compact 走底部 FloatingNavigationBar +
 *    单栏内容；Medium/Expanded 开始用双栏与更大的 Miuix 卡片，栏位仍是 Miuix 组件），
 *    而不是自己画壳。
 */
enum class ApWidthClass { Compact, Medium, Expanded }

@Immutable
data class ApAdaptiveLayout(
    /** 窗口宽度分级 */
    val widthClass: ApWidthClass,
    /** 窗口宽度（dp） */
    val width: Dp,
    /** 高度（dp） */
    val height: Dp,
    /** 是否横屏（宽 > 高）。横屏策略见 [ApShellActivity]：内容绘制到状态栏之下 */
    val landscape: Boolean,
    /** 内容是否分两栏（Medium 起；设置页把它当"列表 + 详情"） */
    val twoPane: Boolean,
    /**
     * 设备形态（手机 / 平板 / 电视）—— v223.29.9 新增。
     *
     * 与 [widthClass] 的分工：宽度分级是**排版断点**（纯几何），形态是**设备语义**
     * （电视没有电池、遥控器只有方向键）。两者大多数时候一致，但当用户在设置里
     * 强制指定形态时，形态以用户选择为准（见 [com.allperiph.ui.DeviceFormPref.resolve]）。
     */
    val form: com.allperiph.ui.DeviceFormPref.Form,
    /** 侧边留白是否要避开挖孔（横屏时刘海在左右两侧，必须垫） */
    val padCutout: Boolean,
)

/**
 * 读当前**窗口**尺寸并给出排版建议。
 *
 * 断点取 Material 3 的宽度分级（600 / 840dp），与 Miuix 组件的尺寸语义对齐：
 *  · Compact  (<600dp)：手机竖屏、分屏一半、折叠外屏；
 *  · Medium   (600–839dp)：手机横屏、小平板竖屏、大折叠内屏；
 *  · Expanded (≥840dp)：平板横屏、电视。
 */
@Composable
fun rememberApAdaptiveLayout(
    /** 横竖屏覆盖（悬浮面板专用）：Service overlay 的 ComposeView 挂在 WindowManager 上，
     *  LocalWindowInfo.containerSize 拿到的是**小窗尺寸**、LocalConfiguration 也是服务
     *  竖屏配置（issue#168 返工「悬浮键盘横屏画出竖屏布局」的根因）。调用方用
     *  realScreenSize 实测的整屏宽高覆盖后，本函数的**全部派生量**
     *  （widthClass / landscape / twoPane / form）都按覆盖值重算 —— 不是只改一个字段。 */
    landscapeOverride: Boolean? = null,
    widthDpOverride: Float? = null,
    heightDpOverride: Float? = null,
): ApAdaptiveLayout {
    val cfg = LocalConfiguration.current
    // 横屏判定（成宇 2026-11-04「横屏时有时低栏不会到右上角」）：
    // v223.29.7 已把 `cfg.orientation` 换成 `cfg.screenWidthDp > cfg.screenHeightDp`，
    // 但两个字段都还是 Configuration 的内容。本壳 Activity 声明了
    // configChanges=orientation|screenSize，旋转**不重建**；真机复现（竖屏启动 →
    // 转横屏 → uiautomator dump）显示旋转瞬间低栏"底部居中"居中的已经是**新宽**，
    // 而判定仍按竖屏走 —— 即 Compose 的测量拿到了新窗口、LocalConfiguration 的
    // 回写滞后一拍，宽高比较自然就错。这就是「有时不会到右上角」的完整根因。
    //
    // 所以判定改用 Compose 自己观察到的窗口实测尺寸：LocalWindowInfo.containerSize
    // 是 snapshot state，onSizeChanged 随 relayout 即时写入，与布局测量**同源**，
    // 不等 Configuration 回写。首组合前它还是 0×0（尚未首帧布局），此时退回
    // cfg —— 冷启动本来就用首配置建窗，两者一致。
    val win = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val w: Dp
    val h: Dp
    if (win.width > 0 && win.height > 0) {
        w = with(density) { win.width.toDp() }
        h = with(density) { win.height.toDp() }
    } else {
        w = cfg.screenWidthDp.dp
        h = cfg.screenHeightDp.dp
    }
    // 覆盖先于一切派生：悬浮面板传入的是整屏实测值，窗口/配置在 overlay 里不可信
    // （参数文档说明根因）。任一覆盖非空就整体重算，避免「覆盖了宽、横竖还按小窗猜」。
    if (landscapeOverride != null || widthDpOverride != null || heightDpOverride != null) {
        val ow = widthDpOverride?.dp ?: w
        val oh = heightDpOverride?.dp ?: h
        val oLandscape = landscapeOverride ?: (ow > oh)
        return ApAdaptiveLayout(
            widthClass = when {
                ow.value >= 840 -> ApWidthClass.Expanded
                ow.value >= 600 -> ApWidthClass.Medium
                else -> ApWidthClass.Compact
            },
            width = ow,
            height = oh,
            landscape = oLandscape,
            twoPane = ow.value >= 600,
            form = com.allperiph.ui.DeviceFormPref.resolve(LocalContext.current, ow.value, oh.value),
            padCutout = oLandscape,
        )
    }
    val cls = when {
        w.value >= 840 -> ApWidthClass.Expanded
        w.value >= 600 -> ApWidthClass.Medium
        else -> ApWidthClass.Compact
    }
    // 横屏 = 窗口实测宽 > 高：与布局测量同源、永不滞后；「宽 > 高」与
    // [ApAdaptiveLayout.landscape] 的语义自洽。
    val isLandscape = w > h
    // 形态：优先用户手动选择，否则按实测窗口**短边** + 系统 uiMode 电视判据
    // （v223.29.9 引入；v223.29.86 改短边口径，理由见 DeviceFormPref.resolve 注释 ——
    // 手机横屏宽可过 840dp，按当前宽判会把手机误判成平板）。
    val form = com.allperiph.ui.DeviceFormPref.resolve(LocalContext.current, w.value, h.value)
    return ApAdaptiveLayout(
        widthClass = cls,
        width = w,
        height = h,
        landscape = isLandscape,
        twoPane = w.value >= 600,
        form = form,
        padCutout = isLandscape,
    )
}
