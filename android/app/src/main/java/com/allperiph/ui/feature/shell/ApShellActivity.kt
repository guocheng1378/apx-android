package com.allperiph.ui.feature.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.allperiph.ui.AgentController
import com.allperiph.ui.AgentForegroundService
import com.allperiph.ui.ClipboardSync
import com.allperiph.ui.feature.keyboard.KeyboardScreen
import com.allperiph.ui.feature.remote.RemoteScreen
import com.allperiph.ui.feature.send.SendScreen
import com.allperiph.ui.feature.settings.SettingsPage
import com.allperiph.ui.feature.shell.ShellStatusScreen
import com.allperiph.ui.feature.touchpad.TouchpadScreen
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.LocalMiuixFormOverride
import com.allperiph.ui.miuix.MiuixForm
import com.allperiph.touchpad.GestureShield
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery

/**
 * 新外壳的宿主 Activity（Compose 重构线），现已是**唯一启动入口**。
 *
 * 形态自适应骨架（手机底栏 / 平板宽屏侧栏 / 电视展开侧栏）+ 三个标签页；
 * 六个主标签（首页/键盘/遥控/状态/发送/设置）均已是 Compose 真实现。
 *
 * 启动期引导从旧 `ui.MainActivity` 接管：
 *  - TV 设备发现（TvDiscovery）
 *  - 前台服务（AgentForegroundService，任一传输开关开着时拉起）
 *  - 环境探测（AgentController.refreshEnv）
 *  - 通知权限（用于前台服务 / 被控通知）
 *  - 反向剪贴板（ClipboardSync）
 *  - 三指手势遮蔽（GestureShield，触控板页前台期间）
 *  - 退出时清理（TvDiscovery.stop / onReverseClipboard 解绑）
 *
 * 暂未搬的细化项（标注以便后续跟进，不影响基本可用）：
 *  - 旧 MainActivity 的「旋屏自动切触控板/键盘操控面」监听：新 Compose 各页自行管理朝向，
 *    这里不再全局强制，避免与单页 orientation 请求打架。
 *  - 退出确认弹窗：属 UX 细化，可在 Shell 层补。
 */
class ApShellActivity : ComponentActivity() {

    private val REQ_NOTIFICATION = 1
    private val HOLDER_SHELL = "shell"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // —— 启动期引导（原 MainActivity 的职责）——
        TvDiscovery.start()
        if (anyTransportOn()) AgentForegroundService.start(this)
        ensureNotificationPermission()
        // 环境探测本身必要，回调只是旧 UI 渲染，这里无需回调。
        AgentController.refreshEnv(this)

        setContent {
            ApMiuixTheme {
                // 形态覆盖：null = 跟随窗口宽度；非 null = 手动钉死
                var formOverride by remember { mutableStateOf<MiuixForm?>(null) }
                CompositionLocalProvider(LocalMiuixFormOverride provides formOverride) {
                    ApShell { tab ->
                        when (tab) {
                            ShellTab.HOME -> TouchpadScreen()
                            ShellTab.KEYBOARD -> KeyboardScreen()
                            ShellTab.REMOTE -> RemoteScreen()
                            ShellTab.STATUS -> ShellStatusScreen(this@ApShellActivity)
                            ShellTab.SEND -> SendScreen()
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

    override fun onResume() {
        super.onResume()
        ClipboardSync.register(this)
        GestureShield.acquireWithHint(this, HOLDER_SHELL)
    }

    override fun onPause() {
        super.onPause()
        ClipboardSync.unregister(this)
        GestureShield.release(this, HOLDER_SHELL)
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlTarget.controlClient?.onReverseClipboard = null
        TvDiscovery.stop()
    }

    private fun anyTransportOn(): Boolean =
        listOf("wifi", "bt", "usb").any { AgentController.isTransportEnabled(this, it) }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
    }
}
