package com.allperiph.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.allperiph.shared.util.Log

/**
 * 「显示在其他应用上层」权限的检测与引导（2026-11-02 审查条 1.10）。
 *
 * ## 为什么需要它
 * 通知浮窗 [TvNotificationPrompt] 与收文件浮窗 [TvFilePrompt] 都用
 * `TYPE_APPLICATION_OVERLAY` 挂窗口。窗口类型与 flag 本仓是对的，缺的是**权限链路**：
 * tv 模块此前 `grep canDrawOverlays|MANAGE_OVERLAY` 零命中，没权限时 `addView` 抛异常、
 * 通知浮窗只 `Log.w` 一行日志 —— 用户看到的现象是「电视端通知从来不出现」，
 * 而且无从知道要去系统设置授权。
 *
 * ## 分寸
 * 电视系统（尤其国内 ROM）多数**没有**「显示在其他应用上层」这一项，`ACTION_MANAGE_OVERLAY_PERMISSION`
 * 常常直接 `ActivityNotFoundException`。所以这里只做两件事：
 *  · [granted] 给调用方一个可判定的前置条件（无权限就别去 addView 白抛异常）；
 *  · [guide] 给一次可见提示（Toast）+ 尝试打开系统设置页，失败也只说清「需要手动去系统设置开」。
 * 不反复弹：同一进程只提示一次（[nagged]），避免每条通知都 Toast 一遍。
 */
object TvOverlayPermission {

    @Volatile private var nagged = false

    /** 是否已获「显示在其他应用上层」。API 23 起才有该概念，tv 的 minSdk 远高于此 */
    fun granted(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        Log.w("查询悬浮窗权限失败：${t.message}")
        false
    }

    /**
     * 无权限时给一次可见提示（并尝试跳到系统设置）。
     * @return 是否已授权（true = 调用方可以继续 addView）
     */
    fun checkAndGuide(ctx: Context, scene: String): Boolean {
        if (granted(ctx)) return true
        if (nagged) return false
        nagged = true
        val msg = "「$scene」需要「显示在其他应用上层」权限：请到 系统设置 → 应用 → 特殊权限 里打开"
        runCatching { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
        Log.w(msg)
        // 电视 ROM 上的设置页通常没有这一项，失败不算异常，提示已经把路径说清了
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w("打开悬浮窗权限设置页失败（该 ROM 可能没有此项）：${it.message}") }
        return false
    }
}
