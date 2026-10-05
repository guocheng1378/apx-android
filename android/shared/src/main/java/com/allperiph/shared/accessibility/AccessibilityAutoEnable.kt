package com.allperiph.shared.accessibility

import android.content.Context
import com.allperiph.shared.input.RootInput
import com.allperiph.shared.util.Log

/**
 * 无障碍服务「自启用」：有 root 时免手动开启，无 root 时不做任何事（由界面引导）。
 *
 * 安卓限制：无障碍服务**只能**由用户在系统设置里勾选，或由 root / `WRITE_SECURE_SETTINGS`
 * 直接改写 secure 设置项。本应用在探测到 root 通道（[RootInput.available]）时就地写入
 * `enabled_accessibility_services`（**追加**本组件，不覆盖用户已有的其它无障碍服务）并打开
 * `accessibility_enabled`；没有 root 时静默跳过，绝不报错。
 *
 * 命令做成**单条 shell**：先读现有列表，已包含则跳过，否则追加 —— 因为 [RootInput.run]
 * 是 fire-and-forget 的常驻 shell，拿不到命令回显，只能在 shell 内完成判断。
 */
object AccessibilityAutoEnable {

    private const val TAG = "A11yAutoEnable"

    /** 本应用无障碍组件的完整 id：`包名/服务类全名`（与 Manifest 里的声明一致） */
    fun componentId(ctx: Context): String =
        "${ctx.packageName}/${ApxAccessibilityService::class.java.name}"

    /** 无障碍是否已经生效（服务已连上即视为启用） */
    fun isActive(): Boolean = ApxAccessibilityService.isReady()

    /**
     * 有 root 时尝试自动开启无障碍。
     *
     * @return true 表示命令已交给 root shell（不保证系统即时生效，调用方可稍后用 [isActive] 复核）
     */
    fun tryEnableViaRoot(ctx: Context): Boolean {
        if (!RootInput.available) {
            Log.i(TAG, "无 root，跳过自动开启无障碍 → 改由界面引导用户到系统设置手动开启")
            return false
        }
        val comp = componentId(ctx)
        // cur=$(settings get ...)：读取现有启用列表
        // case \ "$cur\" in *comp*) ;; *) ...追加... ;; esac：已包含则跳过，避免重复与覆盖
        // ${cur:+:}：列表非空时补一个分隔符 ':'，为空时不补
        val cmd = "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$cur\" in *$comp*) ;; " +
            "*) settings put secure enabled_accessibility_services \"\${cur}\${cur:+:}$comp\" ;; " +
            "esac; settings put secure accessibility_enabled 1"
        val ok = RootInput.run(cmd)
        Log.i(TAG, if (ok) "已通过 root 尝试开启无障碍：$comp" else "root 开启无障碍失败")
        return ok
    }
}