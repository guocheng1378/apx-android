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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.tv.R
import com.allperiph.shared.util.Log
import com.allperiph.shared.net.FileSender
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TvFileActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var targetView: TextView
    private lateinit var countView: TextView
    private lateinit var dirView: TextView
    private lateinit var progressBar: ProgressBar
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
        val backBtn = button("← 返回") { finish() }
        TvUi.contentDescription(backBtn, "返回")
        head.addView(backBtn)
        val titleView = TextView(this).apply { text = "文件传输"; setTextColor(TvUi.Pal.text); typeface = Typeface.DEFAULT_BOLD; TvUi.applyTextSize(this, TvUi.Type.DISPLAY); setPadding(gap, 0, 0, 0) }
        TvUi.contentDescription(titleView, "文件传输页面")
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        targetView = TextView(this).apply { isFocusable = true; isClickable = true; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, TvUi.Type.BODY); background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@TvFileActivity, TvUi.Radius.CONTROL), TvUi.dp(this@TvFileActivity, 2f)); setPadding(gap, gap / 2, gap, gap / 2); setOnClickListener { switchTarget() } }
        head.addView(targetView); root.addView(head, LinearLayout.LayoutParams(-1, -2))
        dirView = TextView(this).apply { setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, TvUi.Type.MICRO); setPadding(0, gap / 2, 0, 0) }; root.addView(dirView)
        countView = TextView(this).apply { setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, TvUi.Type.BODY); setPadding(0, gap / 3, 0, gap / 3) }; root.addView(countView)
        // 进度条
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            setPadding(0, gap / 2, 0, gap / 2)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, TvUi.dp(this, 8)))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox, LinearLayout.LayoutParams(-1, -2)); isFillViewport = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, gap / 2, 0, 0) }
        val sendBtn = button("发送本机其它文件…") { pickFromSystem() }
        TvUi.contentDescription(sendBtn, "发送本机其它文件")
        foot.addView(sendBtn)
        statusView = TextView(this).apply { setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, TvUi.Type.CAPTION); setPadding(gap, 0, 0, 0) }
        foot.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        status("点一个文件可以打开，或发回给手机 / 电脑", TvUi.Pal.textDim); return root
    }
    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply { this.text = text; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, TvUi.Type.BODY); gravity = Gravity.CENTER; isFocusable = true; isClickable = true; background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@TvFileActivity, TvUi.Radius.CONTROL), TvUi.dp(this@TvFileActivity, 2f)); setPadding(gap * 2, gap, gap * 2, gap); setOnClickListener { onClick() } }
    private fun refreshTargets() { targets = TvFiles.targets(); if (targetIndex >= targets.size) targetIndex = 0; val cur = targets.getOrNull(targetIndex); targetView.text = when { cur == null -> "发给：还没有设备连过本机"; targets.size == 1 -> "发给：$cur"; else -> "发给：$cur · 点这里换设备（${targetIndex + 1}/${targets.size}）" }; targetView.contentDescription = when { cur == null -> "发送目标：还没有设备连过本机"; targets.size == 1 -> "发送目标：$cur"; else -> "发送目标：$cur，点击切换设备（${targetIndex + 1}/${targets.size}）" } }
    private fun switchTarget() { if (targets.size <= 1) { toast(if (targets.isEmpty()) NO_TARGET_HINT else "只有一台设备连过本机，没有别的可选"); return }; targetIndex = (targetIndex + 1) % targets.size; refreshTargets() }
    private fun currentTarget(): String? = targets.getOrNull(targetIndex)
    private fun refreshList() { val dir = TvFiles.recvDir(this); dirView.text = "存到：${dir.absolutePath}"; val files = TvFiles.listReceived(this); countView.text = "共 ${files.size} 个"; listBox.removeAllViews(); if (files.isEmpty()) { listBox.addView(TextView(this).apply { text = getString(R.string.tv_files_empty); setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, TvUi.Type.BODY); setPadding(0, gap, 0, 0) }); return }; files.forEach { f -> listBox.addView(fileRow(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = TvUi.dp(this@TvFileActivity, 6f) }) } }
    private fun fileRow(f: File): View { val radius = TvUi.dp(this, TvUi.Radius.CONTROL); val stroke = TvUi.dp(this, 2f); val icon = TvFiles.fileIcon(f.name); val size = TvFiles.sizeText(f.length()); val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; setPadding(gap * 2, gap, gap * 2, gap); background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, radius, stroke); setOnClickListener { showFileActions(f) } }; TvUi.contentDescription(this, "$icon ${f.name}, $size，点击打开")
        row.addView(TextView(this).apply { text = icon; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, TvUi.Type.BODY); setPadding(0, 0, gap, 0) })
        row.addView(TextView(this).apply { text = f.name; setTextColor(TvUi.Pal.text); TvUi.applyTextSize(this, TvUi.Type.BODY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(TextView(this).apply { text = size; setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, TvUi.Type.CAPTION) }); row.addView(TextView(this).apply { text = "  打开"; setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, TvUi.Type.BODY); typeface = Typeface.DEFAULT_BOLD }); return row }
    private fun showFileActions(f: File) { val options = if (currentTarget() != null) arrayOf("打开", "发回给手机 / 电脑") else arrayOf("打开"); AlertDialog.Builder(this).setTitle(f.name).setItems(options) { _, which -> when (which) { 0 -> openFile(f); 1 -> sendFile(f) } }.setNegativeButton("取消", null).show() }
    private fun openFile(f: File) { if (f.name.endsWith(".apk", ignoreCase = true)) installApk(f) else openGeneric(f) }
    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            status("要先允许这台电视安装应用（接下来会打开开关页）", TvUi.Pal.accent)
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: ActivityNotFoundException) {}
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { status("这台电视没有安装程序，打不开这个安装包", TvUi.Pal.warn) }
    }
    private fun openGeneric(f: File) { val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(f), guessMime(f.name)); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; try { startActivity(intent) } catch (_: ActivityNotFoundException) { status("这台电视没有能打开它的应用（可以先发回给手机打开）", TvUi.Pal.warn) } }
    private fun guessMime(name: String): String? = when { name.endsWith(".apk", true) -> "application/vnd.android.package-archive"; name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"; name.endsWith(".png", true) -> "image/png"; name.endsWith(".gif", true) -> "image/gif"; name.endsWith(".mp4", true) -> "video/mp4"; name.endsWith(".mkv", true) -> "video/x-matroska"; name.endsWith(".mp3", true) -> "audio/mpeg"; name.endsWith(".txt", true) -> "text/plain"; name.endsWith(".pdf", true) -> "application/pdf"; name.endsWith(".zip", true) -> "application/zip"; else -> null }
    private fun showProgress(p: Int) {
        progressBar.progress = p
        progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }
    private fun sendFile(f: File) { val target = currentTarget() ?: run { status(NO_TARGET_HINT, TvUi.Pal.warn); return }; if (!busy.compareAndSet(false, true)) { status("正在发送上一个文件，等它发完", TvUi.Pal.warn); return }; status("正在发送 ${f.name}", TvUi.Pal.accent); showProgress(0); Thread({ val ok = FileSender.send(target, f) { p -> mainHandler.post { status("正在发送 ${p}%", TvUi.Pal.accent); showProgress(p) } }; mainHandler.post { busy.set(false); showProgress(if (ok) 100 else 0); status(if (ok) "已发送：${f.name} → $target" else SEND_FAIL_HINT, if (ok) TvUi.Pal.ok else TvUi.Pal.warn); if (ok) mainHandler.postDelayed({ showProgress(0) }, 2000) } }, "apxtv-file-send").start() }
    private fun pickFromSystem() { if (currentTarget() == null) { status(NO_TARGET_HINT, TvUi.Pal.warn); return }; try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_PICK) } catch (_: ActivityNotFoundException) { status("这台电视没有文件选择器，可以改从手机 / 电脑发过来", TvUi.Pal.warn) } }
    @Deprecated("电视端沿用传统回调") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode != REQ_PICK || resultCode != RESULT_OK) return; val uri: Uri = data?.data ?: return; val target = currentTarget() ?: run { status(NO_TARGET_HINT, TvUi.Pal.warn); return }; if (!busy.compareAndSet(false, true)) return; val (name, size) = queryNameSize(uri); status("正在发送 $name", TvUi.Pal.accent); showProgress(0); Thread({ val ok = try { contentResolver.openInputStream(uri)?.use { ins -> FileSender.send(target, name, size, ins) { p -> mainHandler.post { status("正在发送 ${p}%", TvUi.Pal.accent); showProgress(p) } } } ?: false } catch (t: Throwable) { Log.e("发送失败：${t.message}"); false }; mainHandler.post { busy.set(false); showProgress(if (ok) 100 else 0); status(if (ok) "已发送：$name" else SEND_FAIL_HINT, if (ok) TvUi.Pal.ok else TvUi.Pal.warn); if (ok) mainHandler.postDelayed({ showProgress(0) }, 2000) } }, "apxtv-file-pick").start() }
    private fun queryNameSize(uri: Uri): Pair<String, Long> { var name = "file.bin"; var size = -1L; runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }; c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) } } } }; return name to size }
    private fun status(text: String, color: Int) { statusView.text = text; statusView.setTextColor(color) }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { mainHandler.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object {
        private const val REQ_PICK = 9001
        private const val NO_TARGET_HINT = "还没有手机 / 电脑连过本机 —— 先让对方连上来，再回来发文件"
        private const val SEND_FAIL_HINT = "发送失败 —— 确认两边还在同一网络，再试一次"
    }
}