package com.allperiph.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 控制页快捷入口：给快捷设置 Tile 与外部 App（`com.allperiph.OPEN_CONTROL`）用。
 *
 * 为什么不直接指向 MainActivity：
 *  - Tile 在通知栏里点，需要一个**轻量、无可见界面**的中转（直接跳 MainActivity 会
 *    在通知栏上方完整闪一下界面并带转场动画）；
 *  - MainActivity 是 singleTop，从后台被拉起时若任务栈已有实例，行为不如显式中转可控。
 *
 * 所以本类只做一件事：把请求转发到 MainActivity 后立刻 finish，用户看不到它。
 *
 * ⚠️ v196 之前 manifest 注册了 `.ui.ControlShortcutActivity` 而**这个类并不存在** ——
 * 按注册去启动会找不到类（Tile 点击 / 外部 intent 都会失败）。
 */
class ControlShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先结束自己：中转页不该有任何可见界面
        finish()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }
}