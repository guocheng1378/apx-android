package com.allperiph.ui.compose

import android.content.Context
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.core.ModuleId
import com.allperiph.ui.AgentController
import com.allperiph.ui.AgentForegroundService
import com.allperiph.ui.ThemePref
import com.allperiph.ui.ThemeSkin
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.MiuixTokens
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.touchpad.TouchpadModule
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 迁移期的**并行宿主**（阶段 2/3 的落点）。
 *
 * 已迁移至此的屏：状态页、触控板页。主壳（横竖屏策略、四指切页、键盘页、剪贴板同步、
 * 三指手势遮蔽、设备选择）仍在 [com.allperiph.ui.MainActivity]，等阶段 6 一次性接过来
 * 并切换默认入口 —— 本轮不切 launcher，保证「迁移动码编不过」时整机仍有可用入口。
 *
 * 与旧界面的行为差异：
 *  · 只有两个页签（状态 / 触控板），没有键盘页与设置页；键盘页入口仍走旧主界面；
 *  · 四指左右滑切页未实现（旧逻辑依赖 `Activity.dispatchTouchEvent` + ViewFlipper，
 *    迁移版改为状态驱动的底栏切换，手势切页随主壳一起做）；
 *  · 触控板页不再挂 `PhysKeyBridge`（物理蓝牙键盘转发），该桥接在旧主壳里。
 */
class ApShellActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSkin.applyRuntimeTheme(this)
        ApAccentSync.apply(this)
        applyImmersive()
        setContent {
            ApMiuixTheme { Shell() }
        }
    }

    @Composable
    private fun Shell() {
        val insets = rememberApInsets()
        var page by remember { mutableIntStateOf(0) }
        var running by remember { mutableStateOf(AgentController.running) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (insets.top > 0.dp) insets.top else 0.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    when (page) {
                        0 -> StatusPage(
                            ctx = this@ApShellActivity,
                            running = running,
                            onToggleRunning = { want ->
                                running = want
                                toggleAgent(want)
                            },
                        )
                        1 -> TouchpadPage(
                            ctx = this@ApShellActivity,
                            onGesture = { ev -> touchpadModule()?.feed(ev) },
                            onLongPressArmDrag = { touchpadModule()?.armDrag() },
                            onLongPressRightDrag = { touchpadModule()?.armRightDrag() },
                        )
                        else -> SettingsPage(
                            ctx = this@ApShellActivity,
                            onRecreate = { recreate() },
                        )
                    }
                }
            }

            // 底栏：0.9.4 里 FloatingNavigationBar 自带离底高度与导航栏 inset，
            // 外层只负责贴底（组件内部 Column 会丢弃 BoxScope.align）
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                FloatingNavigationBar(color = MiuixTheme.colorScheme.surfaceContainer) {
                    FloatingNavigationBarItem(
                        selected = page == 0,
                        onClick = { page = 0 },
                        icon = MiuixIcons.Home,
                        label = "状态",
                        enabled = true,
                    )
                    FloatingNavigationBarItem(
                        selected = page == 1,
                        onClick = { page = 1 },
                        icon = MiuixIcons.ListView,
                        label = "触控板",
                        enabled = true,
                    )
                    // 图标：0.9.4 目前只核实过 Home / ListView 两个扩展图标，
                    // 设置页先复用 ListView，待 ui/miuix 补充图标后再换
                    FloatingNavigationBarItem(
                        selected = page == 2,
                        onClick = { page = 2 },
                        icon = MiuixIcons.ListView,
                        label = "设置",
                        enabled = true,
                    )
                }
            }
        }
    }

    private fun touchpadModule(): TouchpadModule? =
        AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule

    /** 与旧 `btnStart` 同一组副作用：模块编排 + 前台服务 */
    private fun toggleAgent(want: Boolean) {
        if (want) {
            AgentController.build(this)
            AgentController.startEnabled(this)
            if (AgentController.TRANSPORTS.any { AgentController.isTransportEnabled(this, it) }) {
                AgentForegroundService.start(this)
            }
        } else {
            AgentController.stopAll()
            AgentForegroundService.stop(this)
        }
    }

    private fun applyImmersive() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.show(WindowInsets.Type.statusBars())
        c.hide(WindowInsets.Type.navigationBars())
    }
}

/** ThemeSkin → MiuixTokens 的一次性同步（皮肤切换后要重新调用） */
internal object ApAccentSync {
    fun apply(ctx: Context) {
        val picked = ThemeSkin.picked(ctx, ThemeSkin.TOUCHPAD).isNotBlank()
        MiuixTokens.skinAccent = if (picked) {
            androidx.compose.ui.graphics.Color(ThemeSkin.current(ctx, ThemeSkin.TOUCHPAD).accent)
        } else {
            null
        }
    }
}
