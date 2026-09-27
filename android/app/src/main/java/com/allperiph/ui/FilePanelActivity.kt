package com.allperiph.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.controlled.TvFileReceiver
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import com.allperiph.wireless.TvFileSender
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端「文件面板」(v1)：**看得到收到的文件，也能发出去**。
 *
 * 此前手机端只有「发送文件」一个入口 —— 电脑/TV/另一台手机推过来的文件落在
 * `<外部文件>/APX/`，界面上完全看不见（Android 11+ 又不好翻应用私有目录）。
 * 现在：
 *  · 上半：**收到的文件**（就是 TvFileReceiver 的落盘目录），点一行即转发给目标；
 *  · 下半：从本机再挑一个文件发出去；
 *  · 目标：当前受控设备优先，其次是局域网发现到的设备 —— 点一下即可切换，不用输 IP。
 *
 * 与 TV 端 `com.allperiph.tv.ui.TvFileActivity` 是同一份交互设计（收/发、目标自动）。
 */
class FilePanelActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var targetView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** (ip, 展示名) */
    private var targets: List<Pair<String, String>> = emptyList()
    private var targetIndex = 0

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshTargets()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
        refreshList()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(android.R.color.white))
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.ui_filepanel_text_file)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.md_on_surface))
        })

        targetView = TextView(this).apply {
            textSize = 13f
            setTextColor(resources.getColor(R.color.md_primary))
            setPadding(0, dp(8f), 0, dp(4f))
            setOnClickListener { switchTarget() }
        }
        root.addView(targetView)

        root.addView(TextView(this).apply {
            text = getString(R.string.ui_filepanel_text_file_forward)
            textSize = 14f
            setTextColor(resources.getColor(R.color.md_on_surface))
            setPadding(0, dp(6f), 0, dp(4f))
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(button("发送本机其它文件…") { pickFromSystem() })
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
            setPadding(0, dp(6f), 0, 0)
        }
        root.addView(statusView)
        return root
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(resources.getColor(R.color.md_primary))
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        setOnClickListener { onClick() }
    }

    // ————————————————————————————— 数据 —————————————————————————————

    /** 接收目录：与 [TvFileReceiver] 落盘位置一致（<外部文件>/APX） */
    private fun recvDir(): File = File(getExternalFilesDir(null) ?: filesDir, "APX")

    private fun refreshList() {
        val dir = recvDir()
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        listBox.removeAllViews()
        if (files.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.ui_filepanel_text_file_no_local)
                textSize = 13f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
                setPadding(0, dp(8f), 0, 0)
            })
            return
        }
        files.forEach { f -> listBox.addView(fileRow(f)) }
    }

    private fun fileRow(f: File): ViewGroup {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { sendFile(f) }
        }
        row.addView(TextView(this).apply {
            text = f.name
            textSize = 14f
            setTextColor(resources.getColor(R.color.md_on_surface))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = sizeText(f.length())
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.ui_filepanel_text_forward)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.md_primary))
        })
        return row
    }

    private fun refreshTargets() {
        val out = LinkedHashMap<String, String>()
        // 1) 当前受控设备（已经在控制的那个）优先
        if (ControlTarget.host.isNotBlank()) out[ControlTarget.host] = ControlTarget.label
        // 2) 其次是局域网发现到的设备
        runCatching {
            for (d in TvDiscovery.list()) {
                if (!out.containsKey(d.ip)) out[d.ip] = "${d.name} ${d.typeLabel}"
            }
        }
        targets = out.map { it.key to it.value }
        if (targetIndex >= targets.size) targetIndex = 0
        val cur = targets.getOrNull(targetIndex)
        targetView.text = when {
            cur == null -> "目标：暂无（先连一台设备，或等发现到设备）"
            targets.size == 1 -> "目标：${cur.second}  ${cur.first}"
            else -> "目标：${cur.second}  ${cur.first}（${targetIndex + 1}/${targets.size}，点击切换）"
        }
    }

    private fun switchTarget() {
        if (targets.isEmpty()) {
            toast(getString(R.string.ui_filepanel_toast_device_no_first))
            return
        }
        if (targets.size == 1) {
            toast(getString(R.string.ui_filepanel_toast_only, targets[0].first))
            return
        }
        targetIndex = (targetIndex + 1) % targets.size
        refreshTargets()
    }

    private fun currentTarget(): String? = targets.getOrNull(targetIndex)?.first

    // ————————————————————————————— 发送 —————————————————————————————

    private fun sendFile(f: File) {
        val host = currentTarget()
        if (host == null) {
            status(getString(R.string.ui_filepanel_toast_device_no_first), true)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            status(getString(R.string.ui_filepanel_status_send_please), true)
            return
        }
        status(getString(R.string.ui_filepanel_status_send, f.name), false)
        Thread({
            val ok = TvFileSender.send(this, host, Uri.fromFile(f)) { p ->
                mainHandler.post { status("发送中 ${p}% · ${f.name}", false) }
            }
            mainHandler.post {
                busy.set(false)
                status(if (ok) getString(R.string.ui_filepanel_text_send_done, host, f.name) else getString(R.string.ui_filepanel_text_send_failed, f.name), !ok)
            }
        }, "apx-file-panel").start()
    }

    private fun pickFromSystem() {
        if (currentTarget() == null) {
            status(getString(R.string.ui_filepanel_toast_device_no_first), true)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (_: Throwable) {
            status(getString(R.string.ui_filepanel_status_file_pick), true)
        }
    }

    @Deprecated("沿用传统回调（本机无 AndroidX）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val host = currentTarget() ?: return
        if (!busy.compareAndSet(false, true)) return
        status(getString(R.string.ui_filepanel_status_send_2), false)
        Thread({
            val ok = TvFileSender.send(this, host, uri) { p ->
                mainHandler.post { status("发送中 ${p}%", false) }
            }
            mainHandler.post {
                busy.set(false)
                status(if (ok) getString(R.string.ui_filepanel_text_send_done_2, host) else getString(R.string.ui_filepanel_text_send_failed_2), !ok)
            }
        }, "apx-file-panel-pick").start()
    }

    // ————————————————————————————— 小工具 —————————————————————————————

    private fun status(text: String, warn: Boolean) {
        statusView.text = text
        statusView.setTextColor(
            if (warn) Color.parseColor("#B3261E")
            else resources.getColor(R.color.md_on_surface_variant)
        )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK = 9101
    }
}
