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
         * 后台启前台服务被系统拒绝、待首个界面可见时补启的挂起位。
         *
         * Android 12+ 禁止后台启动前台服务：更新（MY_PACKAGE_REPLACED）/ 闹钟广播把
         * 进程在后台拉起时，Application.onCreate 里 startForegroundService 必抛
         * ForegroundServiceStartNotAllowedException（真机日志：装完新版首次被广播拉起，
         * 外设服务与悬浮球同时报 not allowed）。此刻前台不可见，唯一合规的补启时机是
         * 首个 Activity onStart —— 由 [AgentStartRetry] 消费此位。
         */
        @Volatile
        private var pendingAgentStart = false

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
            com.allperiph.ui.GlobalKeyForwarder.prefs(this)
        )
        // 键映射覆盖层：与转发开关一样在进程起点灌进去，无障碍服务随时被拉起也能读到。
        com.allperiph.ui.HidKeyOverrides.load(
            com.allperiph.ui.HidKeyOverrides.prefs(this)
        )
        com.allperiph.shared.accessibility.ApxAccessibilityService.keyForwarder =
            com.allperiph.ui.GlobalKeyForwarder::forward
        registerActivityLifecycleCallbacks(ShieldLifecycle)
        // 桌面悬浮球的前后台让位（见 [BallForegroundGate]）：App 前台收球、退后台还球。
        registerActivityLifecycleCallbacks(BallForegroundGate)
        // 后台启前台服务被拒（Android 12+）时的补启钩子 —— 与 ShieldLifecycle 分开注册，
        // 职责不同：那个管手势还原，这个只管「界面第一次可见时把被拒的服务补起来」。
        registerActivityLifecycleCallbacks(AgentStartRetry)
        // 成宇 2026-11-02「悬浮窗不会自动拉起」：悬浮球此前是纯手动开关 ——
        // 用户开着球，进程被系统回收 / 手机重启后，球就再也不回来，得手动去设置页再打开一次。
        // 这里在进程起点做自愈（只认「用户意愿位」[FloatingBallService.isOn]，
        // 关过悬浮球的用户不会被强行拉起）：
        //   · 有悬浮窗权限 → 直接重新起前台服务，球立刻回来；
        //   · 没有权限 → 只能等用户下次开界面时再要（这里不弹系统页，进程起点弹窗很唐突）。
        // 冷却判定放在服务里：进程若是被反复拉起，也不会把球切成一条闪烁的动画。
        runCatching { com.allperiph.ui.FloatingBallService.ensureRunningIfWanted(this) }
            .onFailure { com.allperiph.shared.util.Log.w("App", "悬浮球自愈失败：${'$'}{it.message}") }
        /* 成宇 2026-11-04「不要总开关，只要无线 / 蓝牙 / 有线开关，对应各自的功能」：
           总闸去掉后，三个传输开关就是模块启停的唯一真源 —— 但偏好写在 `transport.*`
           里，进程重启时没人读它，于是会出现「开关亮着、外设不动」（此前那枚总闸
           恰好是进程起点唯一的启动者）。
           在进程起点按开关状态自愈，与上面悬浮球同一口径（只认用户意愿位，不强行
           拉起用户没开的组）；三个全关时什么都不做，保持「全关即静」。
           服务 onStartCommand 会调 startEnabled，而 startEnabled 读的就是各模块的
           enable 位 —— 与开关同源，不会出现「界面开、实际关」。 */
        runCatching {
            val anyTransport = com.allperiph.ui.AgentController.TRANSPORTS.any { t ->
                com.allperiph.ui.AgentController.isTransportEnabled(this, t)
            }
            if (anyTransport) com.allperiph.ui.AgentForegroundService.start(this)
        }.onFailure { deferAgentStartOnNotAllowed(it) }
    }

    /**
     * [com.allperiph.ui.AgentForegroundService.start] 被 Android 12+ 后台启动限制拒绝时：
     * 记挂起位、等首个 Activity onStart 补启（此时前台可见，系统放行）。
     *
     * 判定用类名字符串而不是 `is ForegroundServiceStartNotAllowedException`：
     * 该类 API 31 才有，直接引用在低版本设备上做 instanceof 有类加载风险；
     * 其余异常维持原日志口径，不挂起（界面可见后重试也没意义）。
     */
    private fun deferAgentStartOnNotAllowed(t: Throwable) {
        if (t.javaClass.name.contains("ForegroundServiceStartNotAllowed")) {
            pendingAgentStart = true
            com.allperiph.shared.util.Log.w(
                "App",
                "外设自愈：后台启前台服务被系统拒绝（更新广播拉起的常态），待界面可见后补启"
            )
        } else {
            com.allperiph.shared.util.Log.w("App", "外设自愈失败：${'$'}{t.message}")
        }
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
                // releaseSoon：A.onPause → B.onResume 挨着发生，先等 1.2s 再决定要不要还，
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

    /**
     * 桌面悬浮球的前后台让位（issue#168 2026-11-11 追加反馈「悬浮球进入后隐藏，出来后显示没做」）：
     * 低栏改成壳内导航球后，两枚球在 App 前台期间同屏打架 —— 本 app 任一界面可见
     * （started 计数 ≥1）就把桌面球收起来，全部退到后台（计数归 0，桌面 / 切其它应用）
     * 再自动放回。收放都走 [FloatingBallService.setAppForegroundHidden]。
     *
     * 用计数而不是「某个 Activity 的暂停/恢复」：应用内跳页时新页 onStart 先于旧页 onStop
     * （onPause → 新页 onCreate/onStart/onResume → 旧页 onStop），计数不会中途归零，
     * 球不会在页间跳一下。锁屏 / 弹系统页等同为退后台，同样还球 —— 桌面球的承载对象
     * 本来就只在「本 App 之外」。
     */
    private object BallForegroundGate : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            if (started == 1) {
                runCatching {
                    com.allperiph.ui.FloatingBallService.setAppForegroundHidden(activity, true)
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            if (started == 0) {
                runCatching {
                    com.allperiph.ui.FloatingBallService.setAppForegroundHidden(activity, false)
                }
            }
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    /**
     * 挂起位 [pendingAgentStart] 的消费者：首个 Activity onStart（界面已可见，
     * 后台启动限制不再适用）时补启外设前台服务。
     *
     * 真机场景（v223.29.11 装机日志）：更新广播在后台拉起进程 → onCreate 里启动
     * 被拒 → AgentForegroundService 起不来，面板/推送链路断到下次手动开界面为止；
     * 悬浮球同场景靠 BroadcastKeepAlive 兜住、外设服务没有对等路径 —— 这里补上。
     * 补启仍失败（可见期理论上不会是后台限制）则保留挂起位，下次 onStart 再试，
     * 每次界面启动至多尝试一次，不会打成循环。
     */
    private object AgentStartRetry : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (!pendingAgentStart) return
            runCatching { com.allperiph.ui.AgentForegroundService.start(activity) }
                .onFailure {
                    com.allperiph.shared.util.Log.w("App", "前台服务补启失败：${'$'}{it.message}")
                }
                .onSuccess { pendingAgentStart = false }
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }
}
