package com.allperiph.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.shared.net.FileReceiver
import com.allperiph.shared.net.FileSender
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import top.yukonga.miuix.kmp.basic.Text
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.allperiph.ui.miuix.applyEdgeToEdge
import androidx.compose.ui.res.colorResource

/**
 * 手机端「文件面板」(v1)：**看得到收到的文件，也能发出去**。
 *
 * 此前手机端只有「发送文件」一个入口 —— 电脑/TV/另一台手机推过来的文件落在
 * `<外部文件>/APX/`，界面上完全看不见（Android 11+ 又不好翻应用私有目录）。
 * 现在：
 *  · 上半：**收到的文件**（就是 FileReceiver 的落盘目录），点一行即转发给目标；
 *  · 下半：从本机再挑一个文件发出去；
 *  · 目标：当前受控设备优先，其次是局域网发现到的设备 —— 点一下即可切换，不用输 IP。
 *
 * 与 TV 端 `com.allperiph.tv.ui.TvFileActivity` 是同一份交互设计（收/发、目标自动）。
 *
 * **本轮迁 Compose**（旧主题栈下线的一步）：`LinearLayout + ScrollView + removeAllViews/addView`
 * 换成 `LazyColumn` + `mutableStateOf`；按钮强调色从 `ApPalette.primary/onPrimary` 换成
 * Compose 侧常量色，`AlertDialog` 不再走 `ApPalette.dialogStyle()`（用 Activity 自身主题）。
 * 不再需要 `attachBaseContext(ThemePref.wrap)` 与 `ApPalette.applyRuntimeTheme`。
 * 发送/接收逻辑（`FileSender.send`、`TvDiscovery`、`ControlTarget`、`AtomicBoolean busy`）未改。
 *
 * 2026-11-04 资源化（C3 同款病灶）：`refreshTargets()` 此前直接拼「目标：…」三分支中文行、
 * 发送按钮也是裸中文字面量 —— 数据层写死文案，改措辞要同时记得 Kotlin 与 strings 两处。
 * 现在 5 处全走 `getString`（见 `ui_filepanel_target_*` / `ui_filepanel_send_pick`）。
 */
class FilePanelActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** (ip, 展示名) */
    private var targets: List<Pair<String, String>> = emptyList()
    private var targetIndex = 0

    // ———— Compose 状态 ————
    /**
     * 初始值留空串：属性初始化器跑在 `attachBaseContext` **之前**，那时 `getString`
     * 还没有 Context（会抛）。`onCreate` 末尾的 `refreshTargets()` 是同步的，
     * 首帧渲染前就会填上真实文案，空串不可见。
     */
    private var targetLine by mutableStateOf("")
    private var recvPath by mutableStateOf("")
    private var files by mutableStateOf<List<File>>(emptyList())
    private var statusText by mutableStateOf("")
    private var statusWarn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏（成宇 2026-11-02「所有页屏蔽状态栏」）：与主壳同一口径，本页也不露系统时间。
        applyEdgeToEdge()
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Text(
                                text = getString(R.string.ui_filepanel_text_file),
                                fontSize = 20.sp,
                            )
                            Text(
                                text = targetLine,
                                fontSize = 13.sp,
                                color = colorResource(R.color.overlay_accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp)
                                    .clickable { switchTarget() },
                            )
                            Text(
                                text = getString(R.string.ui_filepanel_text_file_forward),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                            )
                            // 说明（v205）：文件存哪 + 行内按钮怎么用（用户反馈"说明也没写，文件在哪"）
                            Text(
                                text = getString(R.string.ui_filepanel_where, recvPath),
                                fontSize = 11.sp,
                                color = colorResource(R.color.overlay_muted),
                            )
                            if (files.isEmpty()) {
                                Text(
                                    text = getString(R.string.ui_filepanel_text_file_no_local),
                                    fontSize = 13.sp,
                                    color = colorResource(R.color.overlay_muted),
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    items(files) { f -> fileRow(f) }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorResource(R.color.overlay_accent))
                                    .clickable { pickFromSystem() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                // 资源化（2026-11-04）：裸中文字面量 → ui_filepanel_send_pick
                                Text(
                                    text = getString(R.string.ui_filepanel_send_pick),
                                    fontSize = 14.sp,
                                    color = colorResource(R.color.overlay_on_accent),
                                )
                            }
                            if (statusText.isNotEmpty()) {
                                Text(
                                    text = statusText,
                                    fontSize = 12.sp,
                                    color = if (statusWarn) colorResource(R.color.overlay_danger) else colorResource(R.color.overlay_muted),
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
        refreshTargets()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
        refreshList()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    @androidx.compose.runtime.Composable
    private fun fileRow(f: File) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { sendFile(f) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = f.name,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(text = sizeText(f.length()), fontSize = 12.sp, color = colorResource(R.color.overlay_muted))
            Spacer(Modifier.width(10.dp))
            Text(text = getString(R.string.ui_filepanel_text_forward), fontSize = 14.sp, color = colorResource(R.color.overlay_accent))
            // 行内「删除」：子视图可点击会先消费触摸事件，不会误触发整行的转发
            Text(
                text = getString(R.string.ui_common_delete),
                fontSize = 14.sp,
                color = colorResource(R.color.overlay_danger),
                modifier = Modifier
                    .padding(start = 14.dp)
                    .clickable { confirmDelete(f) },
            )
        }
    }

    /**
     * 行内「删除」：文件删除不可恢复，必须弹确认（用户反馈"文件管理里没有删除"）。
     * 本轮去掉了 `ApPalette.dialogStyle()`，改用 Activity 自身主题——旧主题栈正在下线，
     * 对话框不该是它最后一个依赖点。
     */
    private fun confirmDelete(f: File) {
        ApDialog.builder(this)
            .setTitle(getString(R.string.ui_filepanel_delete_title))
            .setMessage(getString(R.string.ui_filepanel_delete_msg, f.name))
            .setPositiveButton(getString(R.string.ui_common_delete)) { _, _ ->
                val ok = runCatching { f.delete() }.getOrDefault(false)
                status(if (ok) getString(R.string.ui_filepanel_deleted, f.name) else getString(R.string.ui_filepanel_delete_failed, f.name), !ok)
                if (ok) refreshList()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    // ————————————————————————————— 数据 —————————————————————————————

    /** 接收目录：与 [FileReceiver] 落盘位置一致（<外部文件>/APX） */
    private fun recvDir(): File = File(getExternalFilesDir(null) ?: filesDir, "APX")

    private fun refreshList() {
        val dir = recvDir()
        recvPath = dir.absolutePath
        files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .sortedByDescending { it.lastModified() }
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
        // 资源化（2026-11-04，C3 同款病灶）：此前这三个分支直接拼「目标：…」中文行
        // —— 数据层写死面向用户的文案，与状态页 snapshot() 那次一模一样。
        targetLine = when {
            cur == null -> getString(R.string.ui_filepanel_target_none)
            targets.size == 1 -> getString(R.string.ui_filepanel_target_one, cur.second, cur.first)
            else -> getString(
                R.string.ui_filepanel_target_more,
                cur.second,
                cur.first,
                targetIndex + 1,
                targets.size,
            )
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
            val ok = FileSender.send(this, host, Uri.fromFile(f)) { p ->
                mainHandler.post { status(getString(R.string.ui_filepanel_sending_detail, p, f.name), false) }
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
            val ok = FileSender.send(this, host, uri) { p ->
                mainHandler.post { status(getString(R.string.ui_filepanel_sending, p), false) }
            }
            mainHandler.post {
                busy.set(false)
                status(if (ok) getString(R.string.ui_filepanel_text_send_done_2, host) else getString(R.string.ui_filepanel_text_send_failed_2), !ok)
            }
        }, "apx-file-panel-pick").start()
    }

    // ————————————————————————————— 小工具 —————————————————————————————

    private fun status(text: String, warn: Boolean) {
        statusText = text
        statusWarn = warn
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