package com.allperiph.ui.feature.shell

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.view.OrientationEventListener
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
 *  - 退出确认弹窗：属 UX 细化，可在 Shell 层补。
 *
 * 已搬回的细化项：
 *  - 「旋屏沉浸操控面」：见 [onResume] 里的 [OrientationEventListener]，横持且停在首页/键盘/
 *    遥控页时，由 [ApShell] 隐藏导航栏把整屏让给操控面（状态/设置页保留导航）。只做沉浸隐藏，
 *    不复刻旧壳那套强制 requestedOrientation + MIUI 兜底。
 */
class ApShellActivity : ComponentActivity() {

    private val REQ_NOTIFICATION = 1
    private val HOLDER_SHELL = "shell"

    /** 物理朝向：横持为 true；仅用于「横屏沉浸操控面」判断（见 [ApShell] 的 immersive）。 */
    private val landscape = mutableStateOf(false)
    private var orientListener: OrientationEventListener? = null

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
                    val landscape by this@ApShellActivity.landscape
                    ApShell(landscape = landscape) { tab ->
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

        // 旋屏监听：物理横持 → 切沉浸操控面。只做沉浸隐藏，不强制 requestedOrientation，
        // 方向跟随系统自动旋转（旧壳那套 SENSOR_LANDSCAPE + MIUI 兜底在 Compose 线不搬）。
        orientListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_GAME) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == OrientationEventListener.ORIENTATION_UNKNOWN) return
                // 60~120° / 240~300° 视为横持，其余为竖持；仅在状态变化时才写，避免无谓重组。
                val land = (deg in 60..120) || (deg in 240..300)
                if (landscape.value != land) landscape.value = land
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientListener?.enable()
        ClipboardSync.register(this)
        GestureShield.acquireWithHint(this, HOLDER_SHELL)
    }

    override fun onPause() {
        orientListener?.disable()
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
