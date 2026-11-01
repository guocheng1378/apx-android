package com.allperiph

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.allperiph.controlled.ControlledService
import com.allperiph.shared.inject.KeepAlive
import com.allperiph.touchpad.GestureShield

/**
 * 应用入口：在进程启动早期把平台特定的被控服务控制器注入共享 [KeepAlive]。
 *
 * 必须在这里做——保活闹钟的杀手鐱就是进程被杀后由系统重新拉起一个全新进程来投递
 * 闹钟广播；新进程里 Application.onCreate 是 BroadcastReceiver.onReceive 之前唯一
 * 跑得到的平台代码，若不在此注入 controller，[KeepAlive.onAlarm] 会拿到 null 而无法
 * 拉起被控服务，保活闭环就断了。
 */
class App : Application() {
    companion object {
        /**
         * 进程级上下文：给没有 UI 依赖的底层组件（如 TvControllerClient 收到
         * 0x25 代输请求时的兜底弹窗）用。UI 层自己的场景仍用各自 Activity。
         */
        @Volatile
        var context: Context? = null
            private set

        /**
         * 需要遮蔽三指手势的界面（触控板能收到三指滑动的两处）。
         *
         * 只在这两个界面前台期间关掉系统三指截屏；退到后台（Home / 切应用 / 锁屏）
         * 立刻还回去 —— 否则用户在桌面想三指下拉截屏时会发现按不动。
         * 触控板页与键盘/设置页之间不做区分：都在本 App 前台，三指都可能是误触，
         * 真要截图随时可以用音量下 + 电源键（那条系统组合键没动）。
         */
        private val SHIELD_ACTIVITIES = setOf(
            "com.allperiph.ui.compose.ApShellActivity",
            "com.allperiph.touchpad.TouchpadActivity",
        )
    }

    override fun onCreate() {
        super.onCreate()
        context = this
        // ControlledService companion 实现了 ServiceController；交给共享 KeepAlive 使用。
        KeepAlive.controller = ControlledService
        // 三指手势遮蔽的自愈：上次进程被杀时可能还留着「系统三指手势被改掉」的状态。
        // 必须在这里做 —— 用户可能再也不会打开触控板，靠界面还原就永远等不到。
        GestureShield.healIfNeeded(this)
        // issue #52 建议 6：全局键盘转发（默认关）——开关从偏好恢复，并把转发实现
        // 注册进无障碍服务的钩子。无障碍服务由系统随时拉起、没有自己的初始化时机，
        // 开关状态与实现都只能由 Application 在进程起点灌进去。
        com.allperiph.ui.GlobalKeyForwarder.loadPref(
            getSharedPreferences("apx_ime_bridge", Context.MODE_PRIVATE)
        )
        // 键映射覆盖层：与转发开关一样在进程起点灌进去，无障碍服务随时被拉起也能读到。
        com.allperiph.ui.HidKeyOverrides.load(
            getSharedPreferences("apx_key_overrides", Context.MODE_PRIVATE)
        )
        com.allperiph.shared.accessibility.ApxAccessibilityService.keyForwarder =
            com.allperiph.ui.GlobalKeyForwarder::forward
        // issue #52 建议 6 第二条腿：外接触控板的 root 直读（默认关）—— 同样在进程
        // 起点恢复；模块起来后由 RootPointerBridge.start() 实际接管。
        com.allperiph.touchpad.RootPointerBridge.loadPref(
            getSharedPreferences("apx_root_pointer", Context.MODE_PRIVATE)
        )
        registerActivityLifecycleCallbacks(ShieldLifecycle)
    }

    /**
     * 三指手势遮蔽的界面钩子：集中在这里，避免每个 Activity 各写一遍
     * （MainActivity 与 TouchpadActivity 都要，漏一处就是一条会误触的路径）。
     */
    private object ShieldLifecycle : ActivityLifecycleCallbacks {
        private fun nameOf(a: Activity) = a.javaClass.name

        override fun onActivityResumed(activity: Activity) {
            if (nameOf(activity) in SHIELD_ACTIVITIES) {
                GestureShield.acquireWithHint(activity, nameOf(activity))
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (nameOf(activity) in SHIELD_ACTIVITIES) {
                // releaseSoon：A.onPause → B.onResume 挨着发生，等 1.2s 再决定要不要还，
                // 免得为了几十毫秒白跑两次 su（每次 fork 都要 200ms 上下），屏幕也不会闪。
                GestureShield.releaseSoon(activity, nameOf(activity))
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
