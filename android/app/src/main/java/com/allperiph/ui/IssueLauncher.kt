package com.allperiph.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.allperiph.BuildConfig
import com.allperiph.R
import com.allperiph.shared.util.Log as SharedLog
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 意见反馈：跳 GitHub Issues 新建页（公开仓库，零后端）。
 *
 * 与 [Feedback]（按键震动/音效的即时反馈）是两回事 —— 那边先占了 Feedback 这个名字，
 * 这里叫 IssueLauncher，别再撞车。
 *
 * 预填内容：版本 / 机型 / 系统 / 时间 + 最后 50 条运行日志，用户只需补一句哪里不对。
 * 日志在本机拼进 URL、只投向本仓库自己的 issue 页，不经任何第三方。
 */
object IssueLauncher {
    private const val NEW_ISSUE = "https://github.com/guocheng1378/apx-android/issues/new"
    private const val LABEL = "bug"
    // fix 2026-11-18：50 条太少，高频 ping/pong 会把关键痕迹（如 R2 握手日志）挤出
    // 附带日志，排查握手类问题时收端像「零日志」。放宽到 200 条；再多的走设置页
    // 导出（全量 512 环形缓冲），这里只取 200 是为了 URL 长度不超浏览器限制。
    private const val LOG_LINES = 200
    private const val FENCE = "```"

    fun open(context: Context) {
        val title = "问题反馈 · ${BuildConfig.VERSION_NAME} · ${Build.MODEL}"
        val body = buildString {
            appendLine("### 环境")
            appendLine("- App 版本：${UpdateChecker.currentTag()}（versionName ${BuildConfig.VERSION_NAME}）")
            appendLine("- 设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- 系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("- 时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("### 问题描述")
            appendLine("<!-- 哪里不对、期望是什么 -->")
            appendLine()
            appendLine("### 复现步骤")
            appendLine("1. ")
            appendLine("2. ")
            appendLine()
            appendLine("### 运行日志（最后 ${LOG_LINES} 条）")
            appendLine(FENCE)
            SharedLog.snapshot().takeLast(LOG_LINES).forEach { e ->
                appendLine("${e.level} ${e.tag}: ${e.msg}")
            }
            appendLine(FENCE)
        }
        val url = "${NEW_ISSUE}?labels=${LABEL}" +
                "&title=${URLEncoder.encode(title, "UTF-8")}" +
                "&body=${URLEncoder.encode(body, "UTF-8")}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            SharedLog.w("IssueLauncher", "no browser: ${e}")
            Toast.makeText(context, R.string.about_feedback_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
