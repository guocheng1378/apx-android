package com.allperiph.ui

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.miuix.MiuixShowcaseActivity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 设置页「关于」卡 —— **Compose + Miuix PoC**（miuix-ui 分支）。
 *
 * ## 这张卡在验证什么
 * 把 Miuix（HyperOS 风格 Compose 组件库）接进**纯 Views 的存量 App**，走的是渐进路线，
 * 不是重写：
 *
 *  · 依赖面只有两个（都 Apache-2.0、Maven Central 可查）：androidx.activity:activity-compose
 *    （宿主能力）与 top.yukonga.miuix.kmp:miuix（组件库）；Compose 运行时/foundation 由它
 *    传递引入，本工程不直接声明；
 *  · 宿主：ApShellActivity 从 android.app.Activity 换成 **ComponentActivity** —— ComposeView
 *    需要 ViewTreeLifecycleOwner / ViewModelStoreOwner，只有 androidx 的宿主会挂；
 *  · 主题：MiuixTheme 跟随 **ThemePref** 的明暗（不是系统日夜）—— ThemePref.wrap 把用户的
 *    选择写进 configuration，本卡读 configuration.uiMode，AUTO 时交给系统；
 *  · 版本对齐：Miuix 0.5.0（kotlin-stdlib 2.2.0 → Kotlin ≥ 2.1；Compose 1.8.2 → compileSdk
 *    35 不动）。更新的 0.9.x 要 Kotlin 2.3/2.4 + compileSdk 36，本分支不追。
 *
 * ## 与 AboutCard 的关系
 * 行为一致（版本行 + 检查更新 + 意见反馈 + 首次可见静默检查），只是渲染从
 * 纯 Views（about_card.xml）换成 Miuix 组件。同一插槽（设置页）二选一：
 * 合并本分支即切换到本卡，旧 AboutCard 保留在树里便于对照/回退。
 *
 * ## 已知差异（如实说）
 *  · 本卡**只做编译级验证**（本机没有安卓工具链）；渲染效果、触摸反馈、与滚动容器的
 *    嵌套（设置页是 ScrollView + 自定义滚动逻辑）都要真机确认；
 *  · Compose 的重组需要 ApShellActivity 走 ComponentActivity —— 其他 Activity
 *    （触控板/遥控器/副屏…）仍是纯 framework 的 Activity，不受影响。
 */
class MiuixAboutCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 版本行文案（与 AboutCard 同一套状态机：检查中 / 已是最新 / 发现新版 / 失败）。
     *  用 mutableStateOf：文案变化要驱动 Compose 重组，普通 var 不会。 */
    private var versionText by mutableStateOf(
        context.getString(R.string.about_version, UpdateChecker.currentTag())
    )

    /** init 完成标志：onVisibilityChanged 可能在构造期（XML 写了 visibility）就被回调 */
    private var ready = false
    private var autoChecked = false

    init {
        val view = ComposeView(context)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        view.setContent { AboutContent() }
        ready = true
    }

    // 参数必须是 View（非空）：SDK 对 changedView 标了非空，写 View? 会 "overrides nothing"
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!ready || autoChecked || visibility != View.VISIBLE) return
        autoChecked = true
        check(manual = false)
    }

    private fun check(manual: Boolean) {
        versionText = context.getString(R.string.about_checking)
        // 在途保护在 UpdateChecker 里：连点也只会发出一次请求
        if (manual) UpdateChecker.check(context) { r -> render(r) }
        else UpdateChecker.autoCheck(context) { r -> render(r) }
    }

    private fun render(r: UpdateChecker.Result) {
        versionText = when (r) {
            is UpdateChecker.Result.UpToDate ->
                context.getString(R.string.about_up_to_date, r.tag)
            is UpdateChecker.Result.NewVersion -> {
                UpdateChecker.showUpdateDialog(context, r.tag, r.url)
                context.getString(R.string.about_update_new, r.tag)
            }
            is UpdateChecker.Result.Failed ->
                context.getString(R.string.about_update_fail)
        }
    }

    @Composable
    private fun AboutContent() {
        // 明暗跟随 ThemePref（不是 isSystemInDarkTheme）：ThemePref.wrap 把用户选择写进了
        // configuration，AUTO 时 configuration 的日夜就是系统的 —— 一条路覆盖三档
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                // Card 的 content 本身是 ColumnScope —— 子项直接排，不用再套 Column
                SmallTitle(
                    text = context.getString(R.string.lay_settings_tab_about),
                    insideMargin = PaddingValues(16.dp, 10.dp, 16.dp, 0.dp),
                )
                Text(
                    text = versionText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 8.dp, 16.dp, 0.dp),
                )
                // 两个按钮各占整行（并排时窄屏上文字会顶到边 —— 与旧卡同一约定）
                // TextButton 的 onClick 是**第二个位置参数**（尾 lambda 会绑到末尾的
                // insideMargin: PaddingValues 上）—— 必须 named 传，CI 实测两种错各一条。
                TextButton(
                    text = context.getString(R.string.about_check_update),
                    onClick = { check(manual = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 10.dp, 16.dp, 0.dp),
                )
                TextButton(
                    text = context.getString(R.string.about_feedback),
                    onClick = { IssueLauncher.open(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 10.dp, 16.dp, 0.dp),
                )
                // miuix-ui 分支：组件总览（开发页）—— 一页看全 Miuix 组件在本主题下的效果
                TextButton(
                    text = "组件总览（Miuix）",
                    onClick = {
                        context.startActivity(android.content.Intent(context, MiuixShowcaseActivity::class.java))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 10.dp, 16.dp, 16.dp),
                )
            }
        }
    }
}
