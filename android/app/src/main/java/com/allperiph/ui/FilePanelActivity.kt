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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.allperiph.files.PhoneFiles
import com.allperiph.shared.net.FileReceiver
import com.allperiph.shared.net.FileSender
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.ApTwoPaneScaffold
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import top.yukonga.miuix.kmp.basic.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.allperiph.ui.miuix.applyEdgeToEdge
import androidx.compose.ui.res.colorResource

/**
 * 手机端「文件面板」(v2，「文件管理」第一批)：**看得到收到的文件，也能发出去，还能就地整理**。
 *
 * v1（2026-11-04 迁 Compose 那版）只有「收到的文件列表 + 转发 + 删除」。v2（2026-11-11，
 * ROADMAP「文件管理」方向一 + §6.2 文件传输页双栏）补齐：
 *  · **打开 / 分享**：走 [ApxFileProvider] 的 `content://` 一次性授权（file:// 会撞
 *    FileUriExposedException，见 TV 端同名类的由来）—— 相册 / 播放器 / 分享目标直接用；
 *  · **修改时间**：文件行第二行 `大小 · 时间`，整理时知道哪个是新到的；
 *  · **传输记录**：本次面板会话内每次收/发记一条（进度 / 成功 / 失败 + 重试），
 *    退出即清（不做持久化 —— 系统通知里已有到达提示，先不做跨会话存档）；
 *  · **平板双栏（≥600dp，§6.2）**：左栏文件列表（点选），右栏详情（大小/时间/路径 +
 *    操作按钮）+ 传输记录（进度/失败/重试）；窄屏仍是单栏，行内操作不变。
 *
 * 交互口径：窄屏 tap 行 = 转发（v1 习惯保留）；双栏 tap 行 = 右栏选中，
 * 动作都在右栏按钮上（列表-详情语义，与设置页/状态页一致）。
 *
 * 与 TV 端 `com.allperiph.tv.ui.TvFileActivity` 是同一份交互设计（收/发、目标自动）。
 * 发送/接收逻辑（`FileSender.send`、`TvDiscovery`、`ControlTarget`、`AtomicBoolean busy`）未改。
 */
class FilePanelActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** (ip, 展示名) */
    private var targets: List<Pair<String, String>> = emptyList()
    private var targetIndex = 0

    private companion object {
        // 传输记录状态：进行中 / 成功 / 失败（int 而非 enum：Compose mutableStateOf 简单值即可）
        const val REC_RUNNING = 0
        const val REC_OK = 1
        const val REC_FAIL = 2
        const val REQ_PICK = 9101
    }

    /**
     * 一条传输记录（**内存态**，退出面板即清）。file 非空 = 发的是本机可重取的文件，
     * 失败后可「重试」；从系统选择器发的拿不到落盘路径（content:// 授权随 Activity 结束失效），
     * 收到的文件不需要重试 —— 两种情况的 file 都为 null，不显示重试按钮。
     */
    private class TransferRec(
        val name: String,
        val size: Long,
        val outgoing: Boolean,
        val file: File?,
    ) {
        var progress by mutableStateOf(0)
        var state by mutableStateOf(REC_RUNNING)
        /** 失败原因。FileSender 目前只回成败不回原因（网络层异常被吞在日志里），先落通用文案 */
        var error by mutableStateOf("")
        val startedAt: Long = System.currentTimeMillis()
    }

    /** 新记录插最前（最新在上），与收到的文件列表同一排序口径 */
    private val records = androidx.compose.runtime.mutableStateListOf<TransferRec>()

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

    /** 平板双栏（≥600dp）：右栏当前选中的文件；窄屏不用（tap 行即转发） */
    private var selected by mutableStateOf<File?>(null)

    /**
     * 「所有文件访问」授权状态（方向三：电脑浏览手机文件的前提）。
     * onCreate 首查 + onResume 复查——用户从系统设置授完权回来立刻消失，不用重启面板。
     */
    private var storageOk by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageOk = PhoneFiles.hasStorageAccess(this)
        // 全屏（成宇 2026-11-02「所有页屏蔽状态栏」）：与主壳同一口径，本页也不露系统时间。
        applyEdgeToEdge()
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        val adaptive = rememberApAdaptiveLayout()
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
                            permGrantButton()
                            if (adaptive.twoPane) {
                                // 双栏（§6.2 文件传输页）：左=列表点选，右=详情+记录。
                                // 顶栏（标题/目标）留在双栏外全宽 —— 目标切换是全页面的事。
                                ApTwoPaneScaffold(
                                    left = { fileListPane(twoPane = true) },
                                    right = { detailPane() },
                                    modifier = Modifier.padding(top = 4.dp).weight(1f),
                                )
                                pickButton()
                                statusLine()
                            } else {
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
                                        items(files) { f -> fileRow(f, twoPane = false) }
                                    }
                                }
                                pickButton()
                                statusLine()
                            }
                        }
                    }
                }
            },
        )
        refreshTargets()
        refreshList()
        // 面板开着时收到文件 → 顺手记一条「收到」记录并刷新列表；关闭时清回调防泄漏 Activity。
        FileReceiver.onReceived = { _, f ->
            records.add(0, TransferRec(f.name, f.length(), outgoing = false, file = null))
            refreshList()
        }
    }

    override fun onResume() {
        super.onResume()
        storageOk = PhoneFiles.hasStorageAccess(this)
        refreshTargets()
        refreshList()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    /**
     * 文件列表（双栏的左栏 / 窄屏的主列表共用一份行渲染）。
     * twoPane=true：tap=选中（动作在右栏）；twoPane=false：tap=转发（v1 习惯）+ 行内打开/分享/删除。
     */
    @androidx.compose.runtime.Composable
    private fun fileListPane(twoPane: Boolean) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (files.isEmpty()) {
                Text(
                    text = getString(R.string.ui_filepanel_text_file_no_local),
                    fontSize = 13.sp,
                    color = colorResource(R.color.overlay_muted),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { f -> fileRow(f, twoPane) }
                }
            }
        }
    }

    /** 双栏右栏：所选文件详情（大小/时间/路径 + 动作）+ 传输记录。没选中时给引导文案。 */
    @androidx.compose.runtime.Composable
    private fun detailPane() {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
            val sel = selected
            if (sel == null || !sel.isFile) {
                Text(
                    text = getString(R.string.ui_filepanel_detail_none),
                    fontSize = 13.sp,
                    color = colorResource(R.color.overlay_muted),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(text = sel.name, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = "${sizeText(sel.length())} · ${timeText(sel.lastModified())}",
                    fontSize = 12.sp,
                    color = colorResource(R.color.overlay_muted),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = getString(R.string.ui_filepanel_where, sel.absolutePath),
                    fontSize = 11.sp,
                    color = colorResource(R.color.overlay_muted),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    actionText(getString(R.string.ui_filepanel_text_forward), R.color.overlay_accent) { sendFile(sel) }
                    actionText(getString(R.string.ui_filepanel_open), R.color.overlay_accent) { openFile(sel) }
                    actionText(getString(R.string.ui_filepanel_share), R.color.overlay_accent) { shareFile(sel) }
                    actionText(getString(R.string.ui_common_delete), R.color.overlay_danger) { confirmDelete(sel) }
                }
            }
            Text(
                text = getString(R.string.ui_filepanel_records),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            if (records.isEmpty()) {
                Text(
                    text = getString(R.string.ui_filepanel_record_none),
                    fontSize = 12.sp,
                    color = colorResource(R.color.overlay_muted),
                )
            } else {
                // 记录数不大（内存态、退出即清），直接随详情一起滚动，不再嵌套 LazyColumn
                for (rec in records) recordRow(rec)
            }
        }
    }

    /** 传输记录行：方向 + 文件名，第二行状态（进度/成功/失败），失败的本地文件可重试。 */
    @androidx.compose.runtime.Composable
    private fun recordRow(rec: TransferRec) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getString(
                        if (rec.outgoing) R.string.ui_filepanel_record_send else R.string.ui_filepanel_record_recv,
                        rec.name,
                    ),
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                val stateLine = when (rec.state) {
                    REC_OK -> getString(R.string.ui_filepanel_record_done)
                    REC_FAIL -> getString(R.string.ui_filepanel_record_failed, rec.error)
                    else -> getString(R.string.ui_filepanel_record_in, rec.progress)
                }
                Text(
                    text = "${timeText(rec.startedAt)} · $stateLine",
                    fontSize = 11.sp,
                    color = when (rec.state) {
                        REC_FAIL -> colorResource(R.color.overlay_danger)
                        REC_OK -> colorResource(R.color.overlay_accent)
                        else -> colorResource(R.color.overlay_muted)
                    },
                )
            }
            // 重试 = 原样再发一次（新增一条记录，旧记录留着不动 —— 事后能对出「第几次才成功」）
            if (rec.state == REC_FAIL && rec.file != null) {
                Text(
                    text = getString(R.string.ui_filepanel_retry),
                    fontSize = 13.sp,
                    color = colorResource(R.color.overlay_accent),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable { sendFile(rec.file) },
                )
            }
        }
    }

    /** 窄屏行内小动作文字按钮（双栏右栏也用它排动作行）：中色随语义，行为由调用方给 */
    @androidx.compose.runtime.Composable
    private fun actionText(label: String, colorRes: Int, onClick: () -> Unit) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colorResource(colorRes),
            modifier = Modifier
                .padding(end = 18.dp, bottom = 4.dp)
                .clickable(onClick = onClick),
        )
    }

    @androidx.compose.runtime.Composable
    private fun fileRow(f: File, twoPane: Boolean) {
        val isSel = twoPane && selected == f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isSel) colorResource(R.color.overlay_accent).copy(alpha = 0.12f)
                    else colorResource(android.R.color.transparent),
                )
                .clickable { if (twoPane) selected = f else sendFile(f) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = f.name, fontSize = 14.sp, maxLines = 1)
                Text(
                    text = "${sizeText(f.length())} · ${timeText(f.lastModified())}",
                    fontSize = 11.sp,
                    color = colorResource(R.color.overlay_muted),
                )
            }
            if (!twoPane) {
                // 窄屏行内动作（v2）：转发 / 打开 / 分享 / 删除。tap 整行仍是转发（v1 习惯不变）。
                Text(
                    text = getString(R.string.ui_filepanel_text_forward),
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_accent),
                    modifier = Modifier.padding(start = 12.dp).clickable { sendFile(f) },
                )
                Text(
                    text = getString(R.string.ui_filepanel_open),
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_accent),
                    modifier = Modifier.padding(start = 12.dp).clickable { openFile(f) },
                )
                Text(
                    text = getString(R.string.ui_filepanel_share),
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_accent),
                    modifier = Modifier.padding(start = 12.dp).clickable { shareFile(f) },
                )
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
    }

    /** 底部「发本机其它文件」大按钮（v1 原样，仅抽成函数给两种布局共用） */
    @androidx.compose.runtime.Composable
    private fun pickButton() {
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
            Text(
                text = getString(R.string.ui_filepanel_send_pick),
                fontSize = 14.sp,
                color = colorResource(R.color.overlay_on_accent),
            )
        }
    }

    /**
     * 「允许电脑浏览手机文件」入口（方向三）：没授「所有文件访问」时显示一行 muted
     * 文字按钮，tap 跳系统授权页（onResume 复查，授完自动消失）；已授权不渲染。
     * 系统页可能被某些 ROM 裁剪，startActivity 兜 try-catch，失败提示走通用状态行。
     */
    @androidx.compose.runtime.Composable
    private fun permGrantButton() {
        if (storageOk) return
        Text(
            text = getString(R.string.ui_filepanel_perm_grant),
            fontSize = 13.sp,
            color = colorResource(R.color.overlay_muted),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp)
                .clickable { requestStorageAccess() },
        )
    }

    /** 跳「所有文件访问」系统设置页；入口拿不到（ROM 差异）就只提示用户去系统设置 */
    private fun requestStorageAccess() {
        val intent = PhoneFiles.storageSettingsIntent(this)
        runCatching {
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        status(getString(R.string.ui_filepanel_perm_grant_manual), warn = true)
    }

    @androidx.compose.runtime.Composable
    private fun statusLine() {
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = if (statusWarn) colorResource(R.color.overlay_danger) else colorResource(R.color.overlay_muted),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    /**
     * 行内「删除」：文件删除不可恢复，必须弹确认（用户反馈"文件管理里没有删除"）。
     * 双栏里删除的是右栏选中文件 —— 删掉后顺手清掉选中态，右栏退回引导文案。
     */
    private fun confirmDelete(f: File) {
        ApDialog.builder(this)
            .setTitle(getString(R.string.ui_filepanel_delete_title))
            .setMessage(getString(R.string.ui_filepanel_delete_msg, f.name))
            .setPositiveButton(getString(R.string.ui_common_delete)) { _, _ ->
                val ok = runCatching { f.delete() }.getOrDefault(false)
                status(if (ok) getString(R.string.ui_filepanel_deleted, f.name) else getString(R.string.ui_filepanel_delete_failed, f.name), !ok)
                if (ok) {
                    if (selected == f) selected = null
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    // ————————————————————————————— 打开 / 分享（v2）—————————————————————————————

    /** 用系统应用打开（相册看图 / 播放器放视频 / 安装器装 apk…），走 ApxFileProvider 临时授权 */
    private fun openFile(f: File) {
        if (!f.isFile) {
            status(getString(R.string.ui_filepanel_open_missing), true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ApxFileProvider.uriFor(this@FilePanelActivity, f), ApxFileProvider.mimeOf(f.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            status(getString(R.string.ui_filepanel_open_failed), true)
        }
    }

    /** 系统分享（发微信 / 存网盘…），与 [openFile] 同授权口径 */
    private fun shareFile(f: File) {
        if (!f.isFile) {
            status(getString(R.string.ui_filepanel_open_missing), true)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ApxFileProvider.mimeOf(f.name) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, ApxFileProvider.uriFor(this@FilePanelActivity, f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.ui_filepanel_share)))
        } catch (_: Throwable) {
            status(getString(R.string.ui_filepanel_open_failed), true)
        }
    }

    // ————————————————————————————— 数据 —————————————————————————————

    /** 接收目录：与 [FileReceiver] 落盘位置一致（<外部文件>/APX） */
    private fun recvDir(): File = File(getExternalFilesDir(null) ?: filesDir, "APX")

    private fun refreshList() {
        val dir = recvDir()
        recvPath = dir.absolutePath
        files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        // 选中的文件被删/被移走时清掉选中态，右栏退回引导文案
        val sel = selected
        if (sel != null && !sel.isFile) selected = null
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

    /** 转发收到的文件（或重试）到当前目标：每次点都新记一条，进度/结果写进记录 */
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
        val rec = TransferRec(f.name, f.length(), outgoing = true, file = f)
        records.add(0, rec)
        Thread({
            val ok = FileSender.send(this, host, Uri.fromFile(f)) { p ->
                mainHandler.post { rec.progress = p; status(getString(R.string.ui_filepanel_sending_detail, p, f.name), false) }
            }
            mainHandler.post {
                busy.set(false)
                rec.state = if (ok) REC_OK else REC_FAIL
                if (!ok) rec.error = getString(R.string.ui_filepanel_text_send_failed_2)
                status(
                    if (ok) getString(R.string.ui_filepanel_text_send_done, host, f.name)
                    else getString(R.string.ui_filepanel_text_send_failed, f.name),
                    !ok,
                )
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
        // 选择器拿到的只有 content://（授权随本 Activity 结束失效），落不了盘就重试不了
        // —— 记录 file=null，失败不显示重试按钮（类头 TransferRec 注释有口径）。
        val rec = TransferRec(uri.lastPathSegment ?: "file", 0L, outgoing = true, file = null)
        records.add(0, rec)
        Thread({
            val ok = FileSender.send(this, host, uri) { p ->
                mainHandler.post { rec.progress = p; status(getString(R.string.ui_filepanel_sending, p), false) }
            }
            mainHandler.post {
                busy.set(false)
                rec.state = if (ok) REC_OK else REC_FAIL
                if (!ok) rec.error = getString(R.string.ui_filepanel_text_send_failed_2)
                status(
                    if (ok) getString(R.string.ui_filepanel_text_send_done_2, host)
                    else getString(R.string.ui_filepanel_text_send_failed_2),
                    !ok,
                )
            }
        }, "apx-file-panel-pick").start()
    }

    // ————————————————————————————— 小工具 —————————————————————————————

    private fun status(text: String, warn: Boolean) {
        statusText = text
        statusWarn = warn
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** 与 TvFiles.sizeText 同口径，钉 Locale 防止本地化数字（阿拉伯语等）混进 UI */
    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 修改时间/记录时间统一 `MM-dd HH:mm`（面板是「今天/最近」的语境，年份省略） */
    private fun timeText(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        // 面板关了就摘掉收到回调：不摘会长期持着本 Activity（内存泄漏 + 重复记账）
        if (FileReceiver.onReceived != null) FileReceiver.onReceived = null
        super.onDestroy()
    }
}
