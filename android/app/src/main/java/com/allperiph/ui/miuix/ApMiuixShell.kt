package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面外壳（顶栏 + 底栏）的 Miuix 版 —— **页面内容仍是原来的 Views**，只把 chrome 换掉。
 *
 * 为什么先换外壳：`activity_main.xml` 里 4 个页面是 ViewFlipper 的 View 树（触控板 / 键盘 /
 * 设置，外加设置页内嵌的状态页），自绘逻辑很重（TouchCursorView、KeyboardPanels、HotkeyBoard）。
 * 一次性重写成 Compose 风险不可控；外壳是纯展示 + 三个回调，替换成本低、收益立刻可见
 * （HyperOS 风格顶栏 + 悬浮玻璃底栏），后续每个页面可以单独迁进 Compose 而不必再动外壳。
 *
 * 数据流是**单向**的：Activity 写 [ApMiuixShellState]，Compose 只读；点击回调直接调 Activity
 * 原有方法（showPage / toggleOrientLock / showTargetMenu）—— 不复制第二份业务逻辑。
 */

/** 顶栏 / 底栏的状态源（Compose `mutableStateOf`，写侧一律在主线程）。 */
class ApMiuixShellState {

    /** 顶栏标题（页名） */
    var title by mutableStateOf("")

    /** 顶栏副说明：默认页副标题，运行期被链路状态覆盖（见 [subtitleArgb]） */
    var subtitle by mutableStateOf("")

    /** 副说明颜色（ARGB）；0 = 用主题次级色 */
    var subtitleArgb by mutableIntStateOf(0)

    /** 状态徽章文案：`状态 · 上行出口` */
    var badge by mutableStateOf("")

    /** 徽章颜色（ARGB）；0 = 用主题主色 */
    var badgeArgb by mutableIntStateOf(0)

    /** 方向锁定按钮文案（未锁 / 锁横 / 锁竖三态） */
    var orientLockLabel by mutableStateOf("")

    /** 顶栏「设置」入口是否可见（竖屏且不在设置页） */
    var settingsVisible by mutableStateOf(false)

    /** 当前页（与 ViewFlipper 的 displayedChild 同值，见 MainActivity.PAGE_*） */
    var selectedPage by mutableIntStateOf(0)
}

/**
 * 顶栏：页名 + 副说明 + 状态徽章 + 方向锁定 + 设置入口。
 *
 * 徽章点一下 = 「控制目标」菜单 —— 旧版这是**长按底栏「触控板」页签**的入口，
 * Miuix 的 FloatingNavigationBarItem 只暴露单击，故移到一直可见的徽章上。
 */
@Composable
fun ApMiuixTopBar(
    state: ApMiuixShellState,
    onTargetClick: () -> Unit,
    onOrientLockClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val subtitleColor =
        if (state.subtitleArgb != 0) Color(state.subtitleArgb) else colors.onSurfaceVariantSummary
    val badgeColor = if (state.badgeArgb != 0) Color(state.badgeArgb) else colors.primary
    // 顶栏右侧三个可点元素统一的内衬：默认 insideMargin 偏宽，窄屏（360dp）会挤掉标题
    val tightMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.subtitle,
                fontSize = 12.sp,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(badgeColor.copy(alpha = 0.12f))
                .clickable(onClick = onTargetClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = state.badge,
                fontSize = 11.sp,
                color = badgeColor,
                maxLines = 1,
            )
        }
        TextButton(
            text = state.orientLockLabel,
            onClick = onOrientLockClick,
            insideMargin = tightMargin,
        )
        if (state.settingsVisible) {
            TextButton(
                text = stringResource(R.string.ui_main_btn_settings_entry),
                onClick = onSettingsClick,
                insideMargin = tightMargin,
            )
        }
    }
}

/**
 * 底栏：Miuix 悬浮玻璃导航栏（3 项：触控板 / 键盘 / 设置）。
 *
 * 与旧自绘底栏（`nav_tabs.xml` + `NavBarDrawable` 滑动胶囊）相比：
 *  · 选中态是 Miuix 的状态色 + 材质，不再自己画胶囊与波前；
 *  · 图标沿用工程自有的 `ic_apx_*` 矢量（`ImageVector.vectorResource`），不需要另配图标集；
 *  · 宽度上限与底部留白由组件自己管（默认离底 36dp，无导航栏 inset 时），
 *    页面底部 88dp 的 `inset_bottom_nav` 仍然对得上。
 */
@Composable
fun ApMiuixBottomBar(state: ApMiuixShellState, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.ui_main_item_touchpad),
        stringResource(R.string.ui_touchpad_label_keyboard),
        stringResource(R.string.section_settings),
    )
    val icons = listOf(
        R.drawable.ic_apx_touchpad,
        R.drawable.ic_apx_keyboard,
        R.drawable.ic_apx_settings,
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        FloatingNavigationBar(
            modifier = Modifier.widthIn(max = 430.dp),
            // 页面是 Views + fitsSystemWindows 的树，inset 已由外层吃掉；交给组件会重复避让
            defaultWindowInsetsPadding = false,
        ) {
            labels.indices.forEach { i ->
                FloatingNavigationBarItem(
                    selected = state.selectedPage == i,
                    onClick = { onSelect(i) },
                    icon = ImageVector.vectorResource(icons[i]),
                    label = labels[i],
                )
            }
        }
    }
}
