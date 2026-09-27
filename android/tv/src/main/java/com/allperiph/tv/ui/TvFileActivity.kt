package com.allperiph.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.tv.core.Log
import com.allperiph.tv.net.TvFileSender
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV 文件传输面板（遥控器友好、尺寸自适应）。
 */
class TvFileActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var targetView: TextView
    private lateinit var countView: TextView
    private lateinit var dirView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var targets: List<String> = emptyList()
    private var targetIndex = 0
    private val pad get() = TvUi.safeInset(this)
    private val gap get() = TvUi.dp(this, 10f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StrictMode.VmPolicy.Builder().build().let { StrictMode.setVmPolicy(it) }
        }
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(buildUi()); refreshTargets(); refreshList()
    }
    override fun onResume() { super.onResume(); refreshTargets(); refreshList() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(TvUi.Pal.bg); setPadding(pad, pad / 2, pad, pad / 2) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(button("← 返回") { finish() })
        head.addView(TextView(this).apply { text = "文件传输"; setTextColor(TvUi.Pal.text); typeface = Typeface.DEFAULT_BOLD; TvUi.applyTextSize(this, 22f); setPadding(gap, 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        targetView = TextView(this).apply { isFocusable = true; isClickable = true; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, 14f); background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@TvFileActivity, 10f), TvUi.dp(this@TvFileActivity, 2f)); setPadding(gap, gap / 2, gap, gap / 2); setOnClickListener { switchTarget() } }
        head.addView(targetView); root.addView(head, LinearLayout.LayoutParams(-1, -2))
        dirView = TextView(this).apply { setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, 12f); setPadding(0, gap / 2, 0, 0) }; root.addView(dirView)
        countView = TextView(this).apply { setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, 14f); setPadding(0, gap / 3, 0, gap / 3) }; root.addView(countView)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox, LinearLayout.LayoutParams(-1, -2)); isFillViewport = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, gap / 2, 0, 0) }
        foot.addView(button("发送本机其它文件…") { pickFromSystem() })
        statusView = TextView(this).apply { setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, 13f); setPadding(gap, 0, 0, 0) }
        foot.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        status("就绪：点击文件可打开或发送回对端", TvUi.Pal.textDim); return root
    }
    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply { this.text = text; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, 14f); gravity = Gravity.CENTER; isFocusable = true; isClickable = true; background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@TvFileActivity, 10f), TvUi.dp(this@TvFileActivity, 2f)); setPadding(gap * 2, gap, gap * 2, gap); setOnClickListener { onClick() } }
    private fun refreshTargets() { targets = TvFiles.targets(); if (targetIndex >= targets.size) targetIndex = 0; val cur = targets.getOrNull(targetIndex); targetView.text = when { cur == null -> "目标：暂无"; targets.size == 1 -> "目标：$cur"; else -> "目标：$cur（${targetIndex + 1}/${targets.size}，点击切换）" } }
    private fun switchTarget() { if (targets.size <= 1) { toast(if (targets.isEmpty()) "还没有设备连过本机" else "只有一个可发目标"); return }; targetIndex = (targetIndex + 1) % targets.size; refreshTargets() }
    private fun currentTarget(): String? = targets.getOrNull(targetIndex)
    private fun refreshList() { val dir = TvFiles.recvDir(this); dirView.text = "接收目录：${dir.absolutePath}"; val files = TvFiles.listReceived(this); countView.text = "收到的文件：${files.size} 个"; listBox.removeAllViews(); if (files.isEmpty()) { listBox.addView(TextView(this).apply { text = "还没有收到文件。"; setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, 14f); setPadding(0, gap, 0, 0) }); return }; files.forEach { f -> listBox.addView(fileRow(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = TvUi.dp(this@TvFileActivity, 6f) }) } }
    private fun fileRow(f: File): View { val radius = TvUi.dp(this, 12f); val stroke = TvUi.dp(this, 2f); val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; setPadding(gap * 2, gap, gap * 2, gap); background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, radius, stroke); setOnClickListener { showFileActions(f) } }; row.addView(TextView(this).apply { text = f.name; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, 15f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(TextView(this).apply { text = TvFiles.sizeText(f.length()); setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, 13f) }); row.addView(TextView(this).apply { text = "  打开"; setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, 14f); typeface = Typeface.DEFAULT_BOLD }); return row }
    private fun showFileActions(f: File) { val options = if (currentTarget() != null) arrayOf("打开", "发送到对端") else arrayOf("打开"); AlertDialog.Builder(this).setTitle(f.name).setItems(options) { _, which -> when (which) { 0 -> openFile(f); 1 -> sendFile(f) } }.setNegativeButton("取消", null).show() }
    private fun openFile(f: File) { if (f.name.endsWith(".apk", ignoreCase = true)) installApk(f) else openGeneric(f) }
    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            status("需要授权安装未知来源应用", TvUi.Pal.accent)
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: ActivityNotFoundException) {}
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { status("没有找到安装器", TvUi.Pal.warn) }
    }
    private fun openGeneric(f: File) { val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(f), guessMime(f.name)); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; try { startActivity(intent) } catch (_: ActivityNotFoundException) { status("本机没有能打开此文件类型的应用", TvUi.Pal.warn) } }
    private fun guessMime(name: String): String? = when { name.endsWith(".apk", true) -> "application/vnd.android.package-archive"; name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"; name.endsWith(".png", true) -> "image/png"; name.endsWith(".gif", true) -> "image/gif"; name.endsWith(".mp4", true) -> "video/mp4"; name.endsWith(".mkv", true) -> "video/x-matroska"; name.endsWith(".mp3", true) -> "audio/mpeg"; name.endsWith(".txt", true) -> "text/plain"; name.endsWith(".pdf", true) -> "application/pdf"; name.endsWith(".zip", true) -> "application/zip"; else -> null }
    private fun sendFile(f: File) { val target = currentTarget() ?: run { status("还没有设备连过本机", TvUi.Pal.warn); return }; if (!busy.compareAndSet(false, true)) { status("正在发送中…", TvUi.Pal.warn); return }; status("发送中… ${f.name}", TvUi.Pal.accent); Thread({ val ok = TvFileSender.send(target, f) { p -> mainHandler.post { status("发送中 ${p}%", TvUi.Pal.accent) } }; mainHandler.post { busy.set(false); status(if (ok) "已发送：${f.name} → $target" else "发送失败", if (ok) TvUi.Pal.ok else TvUi.Pal.warn) } }, "apxtv-file-send").start() }
    private fun pickFromSystem() { if (currentTarget() == null) { status("还没有设备连过本机", TvUi.Pal.warn); return }; try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_PICK) } catch (_: ActivityNotFoundException) { status("本机没有文件选择器", TvUi.Pal.warn) } }
    @Deprecated("电视端沿用传统回调") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode != REQ_PICK || resultCode != RESULT_OK) return; val uri: Uri = data?.data ?: return; val target = currentTarget() ?: run { status("还没有设备连过本机", TvUi.Pal.warn); return }; if (!busy.compareAndSet(false, true)) return; val (name, size) = queryNameSize(uri); status("发送中… $name", TvUi.Pal.accent); Thread({ val ok = try { contentResolver.openInputStream(uri)?.use { ins -> TvFileSender.send(target, name, size, ins) { p -> mainHandler.post { status("发送中 ${p}%", TvUi.Pal.accent) } } } ?: false } catch (t: Throwable) { Log.e("发送失败：${t.message}"); false }; mainHandler.post { busy.set(false); status(if (ok) "已发送：$name" else "发送失败", if (ok) TvUi.Pal.ok else TvUi.Pal.warn) } }, "apxtv-file-pick").start() }
    private fun queryNameSize(uri: Uri): Pair<String, Long> { var name = "file.bin"; var size = -1L; runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }; c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) } } } }; return name to size }
    private fun status(text: String, color: Int) { statusView.text = text; statusView.setTextColor(color) }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { mainHandler.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object { private const val REQ_PICK = 9001 }
}