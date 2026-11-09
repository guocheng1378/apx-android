package com.allperiph.controlled

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.allperiph.shared.accessibility.ApxAccessibilityService
import com.allperiph.shared.inject.CursorOverlay
import com.allperiph.shared.inject.InjectorPlatform
import com.allperiph.shared.inject.TvInjector

/**
 * 手机端被控注入器的平台配置。
 *
 * 原先手机端的 [com.allperiph.shared.inject.TvInjector]（`com.allperiph.controlled` 包）
 * 与 TV 端是两份近乎一致的拷贝；本对象把手机端"与 TV 端不同"的那部分收拢进来，
 * 交给共享 [TvInjector] 经 [InjectorPlatform] 注入：
 *
 *  - **无障碍手势优先、root 兜底**（[accessibilityFirst] = true，真机报障后对调）——
 *    这里原本是 root 优先，依据是"手机 root shell 的 input 能正常落地"。真机打脸：
 *    [com.allperiph.shared.input.RootInput.run] 只把命令写进 su 的 stdin 就返回 true，
 *    **无从验证事件是否真落地**；SELinux/HyperOS 一旦把 `input tap` 静默丢弃，
 *    tapAt 视为成功直接 return —— **已开启的无障碍兜底永远轮不到**。用户症状
 *    "有 root 也有无障碍，蓝点就是点不动"就是这条独占路径。TV 端早已因同款坑
 *    （tv/core/TvInjector 注释：日志显示成功、一个点击都没落地）改成无障碍优先，
 *    手机端对齐：dispatchGesture 是系统级真实注入，TV 实测可靠；root 保留为兜底，
 *    任意按键（key()）照旧走 root —— 本开关只影响 tap/swipe/长按/滚动四类的顺序；
 *  - **浮窗走 WindowManager，必须切回主线程**（[needsMainThreadSwitch] / [runOnMain]）——
 *    被控服务的握手 / 收流线程直接动 View 会抛 CalledFromWrongThreadException，
 *    且会被当 成"握手失败"把刚建立的连接当场关掉（真机症状：显示已连接却立刻掉线）；
 *  - **线程名前缀 "apx"**；
 *  - **额外的按键分支**（[handleExtraKey]）：Tab 走文本、PageUp/Down 与 MoveHome/End
 *    退化为浮层光标大幅移动 —— 至少让用户看到反馈，而不是像以前那样什么都不发生；
 *  - **channelText 的 root + 虚拟手柄合并档位**（[channelTextUinputGamepad]）。
 *
 * 文案与 [TvInjector.capabilities] 的共用项取 [InjectorPlatform] 默认值（即手机端原文案）。
 * 各字段默认值的意义见 [InjectorPlatform]。
 */
object PhoneInjectorPlatform : InjectorPlatform {
    /** 与原 TvInjector.key() 一致的微移步长（PageUp/Down 用 8 倍） */
    private const val NUDGE = 48f

    private val mainHandler = Handler(Looper.getMainLooper())

    override val overlay: CursorOverlay = TvOverlay

    /**
     * v-fix（蓝点点不动）：**无障碍优先**。root 分支的问题不是 root 不行，而是
     * RootInput.run() 写进 su stdin 就返回 true、无法验证落地 —— 事件被丢时
     * tapAt 直接 return，已开的无障碍永远不被尝试。对调后：无障碍在 → 手势真实
     * 注入（TV 端同款顺序已实测）；无障碍没开 → 照旧落回 root。
     */
    override val accessibilityFirst: Boolean = true

    override val threadPrefix: String = "apx"

    override val needsMainThreadSwitch: Boolean = true

    override fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    override fun channelTextUinputGamepad(): String? = "root 注入 + 虚拟手柄（全键 + 摇杆）"

    override fun handleExtraKey(kc: Int, down: Boolean, injector: TvInjector): Boolean {
        // 这些分支原先只在手机端 TvInjector.key() 的 when 里；TV 端没有 → 落到 else 打"未支持"日志。
        // 共享 TvInjector.key() 在 `!down` 时已 return，因此本函数仅在 down=true 时被调到。
        when (kc) {
            // 手机上很常用的几个键：Tab 走文本（多数输入框会跳焦点/缩进）；PageUp/Down 与
            // Home/End 没有通用系统语义，退化为"浮层光标大幅移动" —— 至少让用户看到反馈。
            KeyEvent.KEYCODE_TAB -> { ApxAccessibilityService.instance?.typeText("\t"); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { injector.nudge(0f, -NUDGE * 8f); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { injector.nudge(0f, NUDGE * 8f); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { injector.nudge(-injector.screenWidth.toFloat(), 0f); return true }
            KeyEvent.KEYCODE_MOVE_END -> { injector.nudge(injector.screenWidth.toFloat(), 0f); return true }
        }
        return false
    }
}
