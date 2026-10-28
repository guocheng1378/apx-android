package com.allperiph.ui.feature.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.allperiph.ui.feature.settings.SettingsPage
import com.allperiph.ui.feature.touchpad.TouchpadScreen
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.LocalMiuixFormOverride
import com.allperiph.ui.miuix.MiuixForm

/**
 * 新外壳的宿主 Activity（Compose 重构线）。
 *
 * 形态自适应骨架（手机底栏 / 平板宽屏侧栏 / 电视展开侧栏）+ 三个标签页；
 * **状态页已经是真数据**，首页与设置页还是占位。
 *
 * 与旧的 `ui.MainActivity` 并存到对应页面迁完为止 —— 届时旧 Activity 与它的
 * 布局/Fragment 会在同一个提交里删除（不留双轨）。
 *
 * 手动启动（未挂桌面图标，避免出现两个启动器）：
 * ```bash
 * adb shell am start -n com.allperiph/com.allperiph.ui.feature.shell.ApShellActivity
 * ```
 */
class ApShellActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApMiuixTheme {
                // 形态覆盖：null = 跟随窗口宽度；非 null = 手动钉死
                var formOverride by remember { mutableStateOf<MiuixForm?>(null) }
                CompositionLocalProvider(LocalMiuixFormOverride provides formOverride) {
                    ApShell { tab ->
                        when (tab) {
                            ShellTab.HOME -> TouchpadScreen()
                            ShellTab.STATUS -> ShellStatusScreen(this@ApShellActivity)
                            ShellTab.SETTINGS -> SettingsPage(
                                context = this@ApShellActivity,
                                formOverride = formOverride,
                                onFormOverride = { formOverride = it },
                            )
                        }
                    }
                }
            }
        }
    }
}
