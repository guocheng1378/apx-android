package com.allperiph.shared.inject

import android.content.Context

/**
 * 平台特定的被控服务控制器。
 *
 * 手机端和 TV 端的被控服务类不同（ControlledService vs TvServerService），
 * 通过此接口让共享 [KeepAlive] 与具体服务类解耦：KeepAlive 只负责闹钟自检闭环，
 * "是否启用 / 是否在跑 / 怎么拉起 / 怎么停"由各端 ServiceController 实现决定。
 *
 * 实现方通常是被控服务类的 companion object（持有进程内静态状态），由各端在
 * Application.onCreate 中注入到 [KeepAlive.controller]。
 */
interface ServiceController {
    /** 用户是否已启用被控（读持久化偏好，进程重启后仍记得） */
    fun enabled(ctx: Context): Boolean
    /** 被控服务当前是否在运行（进程内静态量：进程被杀后必为 false，正好当"已死"判据） */
    fun isRunning(): Boolean
    /** 拉起被控服务 */
    fun start(ctx: Context)
    /** 停止被控服务（可选，TV 端用；默认空实现，手机端不覆盖即不做事） */
    fun stopAll(ctx: Context) {}
}
