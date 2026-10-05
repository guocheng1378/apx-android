package com.allperiph.tv.core

import android.os.Build
import com.allperiph.shared.inject.CursorOverlay
import com.allperiph.shared.inject.InjectorPlatform
import com.allperiph.shared.inject.TvInjector
import com.allperiph.tv.ui.TvOverlay

/**
 * TV 端被控注入器的平台配置。
 *
 * 原先 TV 端的 [com.allperiph.shared.inject.TvInjector]（`com.allperiph.tv.core` 包）
 * 与手机端是两份近乎一致的拷贝；本对象把 TV 端"与手机端不同"的那部分收拢进来，
 * 交给共享 [TvInjector] 经 [InjectorPlatform] 注入：
 *
 *  - **无障碍手势优先，root 兜底**（[accessibilityFirst] = true）—— 这台 ROM 的
 *    SELinux 是 Enforcing，root shell 的 `u:r:toolbox:s0` 域没有 INJECT_EVENTS，
 *    `input tap/swipe/keyevent` 发出的事件被内核静默丢弃；而 RootInput.run() 只把
 *    命令写进 su 的 stdin 就返回 true —— 日志显示"成功"，实际一个点击都没落地。
 *    无障碍 dispatchGesture 是系统级真实触摸注入，实测可靠，所以放在最前；
 *  - **线程名前缀 "apx-tv"**；
 *  - **toast 提示**（[toast]）：电视上"按了但看不见效果"的操作（电源 / 手柄）需要屏幕反馈，
 *    用 [TvInjector.appContext]（应用级），不依赖 Activity —— 界面不在前台时也能提示；
 *  - **通知权限自检**（[extraCapabilities]）：没有它前台服务退化成普通后台服务、更易被回收；
 *  - **channelText / capabilities 的 TV 文案**（电视上没有状态栏提示、用户又看不见日志，
 *    "缺哪一项"必须直接列在界面上，且用词只讲"能做什么"）。
 */
object TvInjectorPlatform : InjectorPlatform {
    override val overlay: CursorOverlay = TvOverlay

    override val accessibilityFirst: Boolean = true

    override val threadPrefix: String = "apx-tv"

    override fun toast(msg: String) {
        val c = TvInjector.appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    override fun extraCapabilities(): List<Triple<String, Boolean, String>> = listOf(
        Triple(
            "通知权限（前台服务常驻所需）",
            notificationsEnabled(),
            "到「应用 → 全能外设 → 通知」里允许",
        ),
    )

    /** 通知权限是否已允许（API 24+ 可查；更早的版本一律视为允许） */
    private fun notificationsEnabled(): Boolean {
        val c = TvInjector.appContext ?: return false
        if (Build.VERSION.SDK_INT < 24) return true
        return runCatching {
            c.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled()
        }.getOrNull() ?: true
    }

    // —————————————————————— channelText TV 文案 ——————————————————————

    override fun channelTextFullKey(): String = "全部按键都能用（靠 root 权限）"
    override fun channelTextEvdev(): String = "全部按键都能用（可以像真遥控器一样操作）"
    override fun channelTextAccessibility(): String = "只能点按、滑动和打字，遥控器按键按不了"
    override fun channelTextNone(): String = "点不动 —— 需要先在系统设置里给它权限"

    // —————————————————————— capabilities TV 文案 + 顺序 ——————————————————————

    override fun capabilitySpecs(): List<Triple<String, String, String>> = listOf(
        Triple(
            "evdev",
            "全部按键控制（不用 root）",
            "系统输入设备打不开（可以改用 root，或先打开下面的点按控制）",
        ),
        Triple("root", "全部按键控制（用 root）", "没有 root 权限（一般用不着，上面那项够用）"),
        Triple(
            "a11y",
            "点按 / 滑动 / 打字控制",
            "点首页「开启无障碍」自动开启（有 root）；否则到系统「无障碍」里启用本应用",
        ),
        Triple(
            "overlay",
            "悬浮窗（把手机光标画在电视上）",
            "到「显示在其他应用上层」里允许本应用",
        ),
        Triple(
            "ime",
            "输入法（中文打字最稳的通道）",
            "到「输入法 / 键盘」设置里启用「全能外设输入」并切过去",
        ),
        Triple(
            "uinput",
            "虚拟手柄（手柄摇杆唯一通道）",
            "本机 /dev/uinput 不可写（有 root 会自动尝试 chmod）",
        ),
    )
}
