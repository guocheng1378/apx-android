package com.allperiph.ui.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.allperiph.R
import com.allperiph.ui.miuix.MiuixForm
import com.allperiph.ui.miuix.currentMiuixForm
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks

/** 新外壳的三个标签页 */
enum class ShellTab {
    HOME,
    KEYBOARD,
    REMOTE,
    STATUS,
    SEND,
    SETTINGS,
}

/** 一个标签页的展示信息（标签文字要 stringResource，只能在 Composable 里组装） */
private data class ShellEntry(
    val tab: ShellTab,
    val label: String,
    val icon: ImageVector,
)

/**
 * 新外壳（Compose 重构线 P1）—— **形态自适应**的应用骨架。
 *
 * ## 形态怎么决定
 * 判据是**当前窗口宽度**（[currentMiuixForm]，断点 600 / 840 / 1200dp）：
 *  · 手机 —— 底部导航栏（[NavigationBar]）；
 *  · 平板 / 宽屏 —— 左侧导航栏（[NavigationRail]，折叠态）；
 *  · 电视（≥1200dp）—— 左侧导航栏展开成完整侧栏（`expanded = true`）。
 *
 * 断点与展开规则取自 MiuixGuiExample（840dp 起双栏、1200dp 起完整侧栏），
 * 但**实现放在本仓**：Miuix 的 NavigationRail 只提供组件，骨架是我们自己的。
 *
 * ## 形态可以手动钉死
 * [com.allperiph.ui.miuix.LocalMiuixFormOverride] 由宿主注入 —— 真机上不必改窗口大小
 * 就能逐个形态验收；屏幕形态固定的设备（TV 端）也用它把形态钉死。
 *
 * @param screen 当前标签页的内容（由宿主提供，这样宿主才能拿到主题/形态的 setter）。
 */
@Composable
fun ApShell(
    modifier: Modifier = Modifier,
    initialTab: ShellTab = ShellTab.HOME,
    landscape: Boolean = false,
    screen: @Composable (ShellTab) -> Unit,
) {
    val form = currentMiuixForm()
    var tab by remember { mutableStateOf(initialTab) }

    val entries = listOf(
        ShellEntry(ShellTab.HOME, stringResource(R.string.shell_tab_touchpad), MiuixIcons.Home),
        ShellEntry(ShellTab.KEYBOARD, stringResource(R.string.shell_tab_keyboard), MiuixIcons.GridView),
        ShellEntry(ShellTab.REMOTE, stringResource(R.string.shell_tab_remote), MiuixIcons.Home),
        ShellEntry(ShellTab.STATUS, stringResource(R.string.shell_tab_status), MiuixIcons.Tasks),
        ShellEntry(ShellTab.SEND, stringResource(R.string.shell_tab_send), MiuixIcons.ListView),
        ShellEntry(ShellTab.SETTINGS, stringResource(R.string.shell_tab_settings), MiuixIcons.Settings),
    )

    // 平板 / 宽屏 / 电视走左侧导航栏，手机走底部导航栏
    val useRail = form != MiuixForm.PHONE

    // 旋屏沉浸操控面：横持且停在「操控面」三类页（首页/键盘/遥控）时，隐藏导航栏把整屏让给操控面；
    // 状态 / 设置页保留导航（沿用旧 MainActivity 的口径：这两类页保持用户的方向控制权）。
    // 注意：这里只做「沉浸隐藏」，不强制 requestedOrientation —— 旧壳那套 SENSOR_LANDSCAPE +
    // MIUI 兜底的强制朝向在 Compose 重构线里不搬，方向与系统自动旋转保持一致即可。
    val immersive = landscape && tab in setOf(ShellTab.HOME, ShellTab.KEYBOARD, ShellTab.REMOTE)

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (!useRail && !immersive) {
                NavigationBar {
                    entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry.tab,
                            onClick = { tab = entry.tab },
                            icon = entry.icon,
                            label = entry.label,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        content = { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (useRail && !immersive) {
                    NavigationRail(expanded = form == MiuixForm.TV) {
                        entries.forEach { entry ->
                            NavigationRailItem(
                                selected = tab == entry.tab,
                                onClick = { tab = entry.tab },
                                icon = entry.icon,
                                label = entry.label,
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    screen(tab)
                }
            }
        },
    )
}
