package com.allperiph.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.allperiph.ui.feature.shell.ApShellActivity

/**
 * 控制页快捷入口：给快捷设置 Tile 与外部 App（`com.allperiph.OPEN_CONTROL`）用。
 *
 * 为什么不直接指向 ApShellActivity：
 *  - Tile 在通知栏里点，需要一个**轻量、无可见界面**的中转（直接跳 ApShellActivity 会
 *    在通知栏上方完整闪一下界面并带转场动画）；
 *  - ApShellActivity 是 singleTop，从后台被拉起时若任务栈已有实例，行为不如显式中转可控。
 *
 * 所以本类只做一件事：把请求转发到 ApShellActivity 后立刻 finish，用户看不到它。
 *
 * ⚠️ v196 之前 manifest 注册了 `.ui.ControlShortcutActivity` 而**这个类并不存在** ——
 * 按注册去启动会找不到类（Tile 点击 / 外部 intent 都会失败）。
 */
class ControlShortcutActivity : Activity() {

    /**
     * 主题方案（issue#44）：这些页面此前**没接** ThemePref，用户选「深色」也只按系统走 ——
     * 于是深色系统下页面还是亮色底、亮色字，或者反过来，看着"字体取色异常"。
     * 与 ApShellActivity / TouchpadActivity / RemoteActivity 同一套实现。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.allperiph.ui.ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先结束自己：中转页不该有任何可见界面
        finish()
        startActivity(
            Intent(this, ApShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }
}