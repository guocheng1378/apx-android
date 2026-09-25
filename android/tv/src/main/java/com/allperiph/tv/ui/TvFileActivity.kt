package com.allperiph.tv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *
 *  · 上半：**收到的文件**列表（手机推过来的都在 [TvFiles.recvDir]）—— 选中一个即可**发回对端**；
 *  · 下半：从本机再挑一个文件发出去（电视上通常没有 DocumentsUI，失败会如实提示，不假装成功）；
 *  · 发送目标不要求手输 IP：用「当前连入方 → 曾连过的对端」，按一下就能切换。
 *
 * 界面全部走 [TvUi] 的自适应尺寸 + 过扫描安全边距，DPAD 焦点用两态背景显示（见 [TvUi.focusBg]）。
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
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(buildUi())
        refreshTargets()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
        refreshList()
    }

    // ——————————————————————————————— 界面 ———————————————————————————————

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        // 顶栏：返回 + 标题 + 目标（目标可点切换）
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(button("← 返回") { finish() })
        head.addView(TextView(this).apply {
            text = "文件传输"
            setTextColor(TvUi.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            TvUi.applyTextSize(this, 22f)
            setPadding(gap, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        targetView = TextView(this).apply {
            isFocusable = true
            isClickable = true
            setTextColor(TvUi.TEXT)
            TvUi.applyTextSize(this, 14f)
            background = TvUi.focusBg(TvUi.CARD, TvUi.CARD_FOCUS, TvUi.dp(this@TvFileActivity, 10f), TvUi.dp(this@TvFileActivity, 2f))
            setPadding(gap, gap / 2, gap, gap / 2)
            setOnClickListener { switchTarget() }
        }
        head.addView(targetView)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 目录 + 计数
        dirView = TextView(this).apply {
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 12f)
            setPadding(0, gap / 2, 0, 0)
        }
        root.addView(dirView)
        countView = TextView(this).apply {
            setTextColor(TvUi.ACCENT)
            TvUi.applyTextSize(this, 14f)
            setPadding(0, gap / 3, 0, gap / 3)
        }
        root.addView(countView)

        // 文件列表（可滚动）
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listBox, LinearLayout.LayoutParams(-1, -2))
            isFillViewport = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 底部：选本机文件 + 状态
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap / 2, 0, 0)
        }
        foot.addView(button("发送本机其它文件…") { pickFromSystem() })
        statusView = TextView(this).apply {
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 13f)
            setPadding(gap, 0, 0, 0)
        }
        foot.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(foot, LinearLayout.LayoutParams(-1, -2))

        status("就绪：选中「收到的文件」即发回对端", TvUi.TEXT_DIM)
        return root
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TvUi.TEXT)
        TvUi.applyTextSize(this, 14f)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = TvUi.focusBg(TvUi.CARD, TvUi.CARD_FOCUS, TvUi.dp(this@TvFileActivity, 10f), TvUi.dp(this@TvFileActivity, 2f))
        setPadding(gap * 2, gap, gap * 2, gap)
        setOnClickListener { onClick() }
    }

    // ——————————————————————————————— 数据 ———————————————————————————————

    private fun refreshTargets() {
        targets = TvFiles.targets()
        if (targetIndex >= targets.size) targetIndex = 0
        val cur = targets.getOrNull(targetIndex)
        targetView.text = when {
            cur == null -> "目标：暂无（先让手机/PC 连上本机）"
            targets.size == 1 -> "目标：$cur"
            else -> "目标：$cur（${targetIndex + 1}/${targets.size}，点击切换）"
        }
    }

    private fun switchTarget() {
        if (targets.size <= 1) {
            toast(if (targets.isEmpty()) "还没有设备连过本机" else "只有一个可发目标：${targets[0]}")
            return
        }
        targetIndex = (targetIndex + 1) % targets.size
        refreshTargets()
    }

    private fun currentTarget(): String? = targets.getOrNull(targetIndex)

    private fun refreshList() {
        val dir = TvFiles.recvDir(this)
        dirView.text = "接收目录：${dir.absolutePath}"
        val files = TvFiles.listReceived(this)
        countView.text = "收到的文件：${files.size} 个（选中即发回对端）"
        listBox.removeAllViews()
        if (files.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有收到文件。手机端「设置 → 文件传输」选本机（TV）即可推过来。"
                setTextColor(TvUi.TEXT_DIM)
                TvUi.applyTextSize(this, 14f)
                setPadding(0, gap, 0, 0)
            })
            return
        }
        files.forEach { f ->
            listBox.addView(fileRow(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = TvUi.dp(this@TvFileActivity, 6f) })
        }
    }

    private fun fileRow(f: File): View {
        val radius = TvUi.dp(this, 12f)
        val stroke = TvUi.dp(this, 2f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(gap * 2, gap, gap * 2, gap)
            background = TvUi.focusBg(TvUi.CARD, TvUi.CARD_FOCUS, radius, stroke)
            setOnClickListener { sendFile(f) }
        }
        val name = TextView(this).apply {
            text = f.name
            setTextColor(TvUi.TEXT)
            TvUi.applyTextSize(this, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = TvFiles.sizeText(f.length())
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 13f)
        })
        row.addView(TextView(this).apply {
            text = "  发送"
            setTextColor(TvUi.ACCENT)
            TvUi.applyTextSize(this, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        return row
    }

    // ——————————————————————————————— 发送 ———————————————————————————————

    private fun sendFile(f: File) {
        val target = currentTarget()
        if (target == null) {
            status("还没有设备连过本机：让手机/PC 先连上本机，再发文件", TvUi.WARN)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            status("正在发送中，请稍候…", TvUi.WARN)
            return
        }
        status("发送中… ${f.name}", TvUi.ACCENT)
        Thread({
            val ok = TvFileSender.send(target, f) { p ->
                mainHandler.post { status("发送中 ${p}% · ${f.name}", TvUi.ACCENT) }
            }
            mainHandler.post {
                busy.set(false)
                status(
                    if (ok) "已发送：${f.name} → $target" else "发送失败：${f.name}（对端没在收？）",
                    if (ok) TvUi.OK else TvUi.WARN,
                )
            }
        }, "apxtv-file-send").start()
    }

    /** 从系统选择器挑文件（电视上多数机型没有 DocumentsUI，这里如实提示而不是静默失败） */
    private fun pickFromSystem() {
        if (currentTarget() == null) {
            status("还没有设备连过本机：先让手机/PC 连上本机", TvUi.WARN)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (_: ActivityNotFoundException) {
            status("本机没有文件选择器：可让手机把文件推到本机后再从这里发回去", TvUi.WARN)
        }
    }

    @Deprecated("电视端不引 AndroidX，沿用传统回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val target = currentTarget()
        if (target == null) {
            status("还没有设备连过本机", TvUi.WARN)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val (name, size) = queryNameSize(uri)
        status("发送中… $name", TvUi.ACCENT)
        Thread({
            val ok = try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    TvFileSender.send(target, name, size, ins) { p ->
                        mainHandler.post { status("发送中 ${p}% · $name", TvUi.ACCENT) }
                    }
                } ?: false
            } catch (t: Throwable) {
                Log.e("选择文件发送失败：${t.message}")
                false
            }
            mainHandler.post {
                busy.set(false)
                status(
                    if (ok) "已发送：$name → $target" else "发送失败：$name",
                    if (ok) TvUi.OK else TvUi.WARN,
                )
            }
        }, "apxtv-file-pick").start()
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "file.bin"
        var size = -1L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return name to size
    }

    // ——————————————————————————————— 小工具 ———————————————————————————————

    private fun status(text: String, color: Int) {
        statusView.text = text
        statusView.setTextColor(color)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK = 9001
    }
}
