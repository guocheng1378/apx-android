package com.allperiph.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.shared.util.Log
import java.io.File

/**
 * 「收到文件」交互浮窗（TV 端）。
 *
 * 文件由 [com.allperiph.shared.net.FileReceiver] 在后台流式落盘，原先只弹一句 Toast：
 * 用户既不能直接打开，也无法删除。这里复用「显示在其他应用上层」权限（与光标浮层同一个），
 * 在后台浮出一个带「打开 / 删除」两个按钮的窗口 —— **后台也能浮现，且不受 Android 10+
 * 后台启动 Activity 限制**。遥控器方向键可切换按钮、确认键执行。
 *
 * 拿不到浮层权限时静默退回一句 Toast（与改动前行为一致，绝不崩）。
 */
object TvFilePrompt {

    private var wm: WindowManager? = null
    private var view: View? = null

    /** 收到文件时调用（主线程）。重复到来会先关掉上一个，只保留最新一个。 */
    fun show(ctx: Context, f: File) {
        val app = ctx.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        dismiss()
        val ui = buildCard(app, f)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        // flags = 0：**可聚焦**，才能用遥控器方向键 / 确认键操作（不能加 FLAG_NOT_FOCUSABLE）
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            manager.addView(ui, params)
            wm = manager
            view = ui
        } catch (t: Throwable) {
            Log.w("收到文件浮窗创建失败（需「显示在其他应用上层」权限）：${t.message}")
            runCatching { Toast.makeText(app, "已接收文件：${f.name}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun dismiss() {
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        wm = null
    }

    // ————————————————————————————— 视图 —————————————————————————————

    private fun buildCard(ctx: Context, f: File): View {
        val gap = TvUi.dp(ctx, 12f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap * 2, gap * 2, gap * 2, gap * 2)
            background = TvUi.solidBg(TvUi.Pal.card, TvUi.dp(ctx, TvUi.Radius.CARD))
            isFocusable = true
            // 遥控器「返回」关闭浮窗（否则一个不可关闭的窗口会挡住电视上的操作）
            setOnKeyListener { _, keyCode, ev ->
                if (ev?.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss(); true
                } else false
            }
        }
        card.addView(label(ctx, "已收到文件", TvUi.Type.TITLE, TvUi.Pal.accent, bold = true))
        card.addView(label(ctx, f.name, TvUi.Type.BODY, TvUi.Pal.text, bold = true).apply {
            setPadding(0, gap / 2, 0, 0)
        })
        card.addView(label(ctx, TvFiles.sizeText(f.length()), TvUi.Type.CAPTION, TvUi.Pal.textDim).apply {
            setPadding(0, gap / 4, 0, 0)
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        val openBtn = button(ctx, "打开") { dismiss(); open(ctx, f) }
        val delBtn = button(ctx, "删除") { dismiss(); delete(ctx, f) }
        row.addView(openBtn)
        row.addView(delBtn)
        card.addView(row)

        // 让第一个按钮先拿到焦点，遥控器一按确认即「打开」
        openBtn.post { runCatching { openBtn.requestFocus() } }
        return card
    }

    private fun label(ctx: Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            TvUi.applyTextSize(this, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

    private fun button(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TvUi.Pal.text)
        TvUi.applyTextSize(this, TvUi.Type.ACTION)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        minimumHeight = TvUi.dp(ctx, 48f)
        minimumWidth = TvUi.dp(ctx, 96f)
        contentDescription = text
        background = TvUi.focusBg(
            TvUi.Pal.card,
            TvUi.Pal.cardFocus,
            TvUi.dp(ctx, TvUi.Radius.CONTROL),
            TvUi.dp(ctx, 3f),
        )
        setPadding(TvUi.dp(ctx, 24f), TvUi.dp(ctx, 12f), TvUi.dp(ctx, 24f), TvUi.dp(ctx, 12f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            rightMargin = TvUi.dp(ctx, 16f)
        }
        setOnClickListener { onClick() }
    }

    // ————————————————————————————— 动作 —————————————————————————————

    private fun open(ctx: Context, f: File) {
        if (!f.isFile) { toast(ctx, "文件已不在（可能已被删除）"); return }
        if (f.name.endsWith(".apk", ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
                toast(ctx, "要先允许这台电视安装应用（接下来会打开开关页）")
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            launch(ctx, f, "application/vnd.android.package-archive", "这台电视没有安装程序，打不开这个安装包")
            return
        }
        launch(ctx, f, guessMime(f.name), "这台电视没有能打开它的应用（可以先发回给手机打开）")
    }

    private fun launch(ctx: Context, f: File, mime: String?, failHint: String) {
        // 与 TvFileActivity 同样的处理：清掉 VmPolicy 的 file:// 暴露检测，
        // 否则用 Uri.fromFile 拉起外部应用会抛 FileUriExposedException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build()) }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(f), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { if (it is ActivityNotFoundException) toast(ctx, failHint) else Log.w("打开文件失败：${it.message}") }
    }

    private fun delete(ctx: Context, f: File) {
        val ok = runCatching { f.delete() }.getOrDefault(false)
        toast(ctx, if (ok) "已删除：${f.name}" else "删除失败：${f.name}")
    }

    private fun guessMime(name: String): String? = when {
        name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".zip", true) -> "application/zip"
        else -> null
    }

    private fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}