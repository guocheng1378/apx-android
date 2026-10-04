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
 *  - **root 通道优先于无障碍**（[accessibilityFirst] = false）—— 手机 root shell 的
 *    `input` 命令能正常落地，且 root 通道能力更全（任意按键）；
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

    override val accessibilityFirst: Boolean = false

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
