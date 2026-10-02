package com.allperiph.shared.inject

import android.content.Context

/**
 * 全局光标浮层抽象：被控端把对方光标画在最上层，提示当前落点。
 *
 * 手机端与 TV 端各有一个 `TvOverlay`（功能相同、实现几乎一致），但类分别落在
 * `com.allperiph.controlled` 与 `com.allperiph.tv.ui` 包里 —— 直接 import 进共享
 * [TvInjector] 会形成循环依赖。通过此接口让共享注入器只依赖「浮层能做什么」，
 * 具体浮层对象由各端在 [InjectorPlatform.overlay] 里提供（通常让本端 `TvOverlay`
 * 直接 implement 本接口）。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；未授予时 [isReady] 为
 * false，仅丢失可视化，不影响注入通道。
 */
interface CursorOverlay {
    /** 浮层是否真的建起来了（没拿到「显示在其他应用上层」时会失败） */
    val isReady: Boolean

    fun init(ctx: Context)
    fun move(x: Float, y: Float)
    fun hide()
    fun destroy()
}
