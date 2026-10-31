package com.allperiph.ui.compose

import android.content.Context
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.allperiph.ui.AgentController
import com.allperiph.ui.AgentForegroundService
import com.allperiph.ui.ThemePref
import com.allperiph.ui.ThemeSkin
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.MiuixTokens
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 状态页的**迁移宿主**（并行宿主，阶段 2 产物）。
 *
 * 为什么先开一个并行 Activity 而不是直接改 [com.allperiph.ui.MainActivity]：
 *  · 本环境没有 Android 工具链，迁移代码无法编译验证；直接替换主宿主等于拿唯一可用的
 *    入口做赌博，一旦有一处签名不符，整机 UI 全黑且无法回退到可用状态；
 *  · 并行宿主可以逐屏落地、逐屏真机验收，主入口的切换放在所有屏迁完之后（阶段 6），
 *    且只需改一行 `setContent` / 一个 manifest 属性，回退同样是一行。
 *
 * 与旧主界面的**行为差异**（如实记录）：
 *  · 不接管四指切页、横屏键盘页、剪贴板同步、GestureShield —— 这些仍归 MainActivity；
 *  · 启动/停止只做 AgentController + 前台服务两条，与旧 `btnStart` 的副作用一致，
 *    但旧实现还会额外刷新状态页局部视图，这里靠 1s 轮询自然跟上。
 */
class ApStatusActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSkin.applyRuntimeTheme(this)
        // 皮肤 → Miuix 主题：迁移后所有页面都从这里取强调色，避免两套配色并存
        ApAccentSync.apply(this)
        applyImmersive()
        setContent {
            ApMiuixTheme {
                var running by remember { mutableStateOf(AgentController.running) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background),
                ) {
                    StatusPage(
                        ctx = this,
                        running = running,
                        onToggleRunning = { want ->
                            running = want
                            if (want) {
                                AgentController.build(this)
                                AgentController.startEnabled(this)
                                if (AgentController.TRANSPORTS.any {
                                        AgentController.isTransportEnabled(this, it)
                                    }
                                ) {
                                    AgentForegroundService.start(this)
                                }
                            } else {
                                AgentController.stopAll()
                                AgentForegroundService.stop(this)
                            }
                        },
                    )
                }
            }
        }
    }

    /** 状态栏显示、导航条隐藏：与 MainActivity.applyImmersive 同一口径 */
    private fun applyImmersive() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.show(WindowInsets.Type.statusBars())
        c.hide(WindowInsets.Type.navigationBars())
    }
}

/** ThemeSkin → MiuixTokens 的一次性同步（皮肤切换后要重新调用） */
internal object ApAccentSync {
    fun apply(ctx: Context) {
        val picked = ThemeSkin.picked(ctx, ThemeSkin.TOUCHPAD).isNotBlank()
        MiuixTokens.skinAccent = if (picked) {
            androidx.compose.ui.graphics.Color(ThemeSkin.current(ctx, ThemeSkin.TOUCHPAD).accent)
        } else {
            null
        }
    }
}
