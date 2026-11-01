package com.allperiph.tv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.ComposeView
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电视端文件页（Compose 重写）。
 *
 * 逻辑（扫描目录 / 目标切换 / 发送 / 安装 APK / 系统选择器）原样保留，只把 `buildUi()` 那套
 * 手工 `LinearLayout + TextView` 换成 Compose：
 * - 状态用 `mutableStateOf` 持有，UI 只读，不再持有 `lateinit var xxxView`；
 * - 列表用 `LazyColumn`，每行可聚焦（DPAD 上下走位 + 焦点描边）；
 * - 文件操作从 `AlertDialog` 换成 Compose `Dialog`（焦点留在 Compose 树里，DPAD 不会丢）。
 */
class TvFileActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var targets: List<String> = emptyList()
    private var targetIndex = 0
    private val pad get() = TvUi.safeInset(this)
    private val gap get() = TvUi.dp(this, 10f)

    // —— Compose 状态（UI 只读这些）——
    private var filesState by mutableStateOf<List<File>>(emptyList())
    private var targetLabel by mutableStateOf("")
    private var dirLabel by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var statusColor by mutableIntStateOf(0)
    private var sendProgress by mutableIntStateOf(0)
    private var actionTarget by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        // 跨应用传递改走 ApxFileProvider 的 content:// + 一次性读授权，不再清 StrictMode 的进程级策略。
        window.setBackgroundDrawableResource(android.R.color.black)
        statusColor = TvUi.Pal.textDim
        setContentView(
            ComposeView(this).apply {
                setContent { TvFileScreen() }
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

    @Composable
    private fun TvFileScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(TvUi.Pal.bg))
                .padding(horizontal = pad.dp, vertical = gap.dp),
        ) {
            TvText("收到的文件", size = TvUi.Type.TITLE)
            TvText(dirLabel, size = TvUi.Type.MICRO, color = TvUi.Pal.textDim)
            TvText(targetLabel, size = TvUi.Type.BODY, color = TvUi.Pal.text)
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                TvButton("换设备") { switchTarget() }
                TvButton("选择文件发送") { pickFromSystem() }
            }
            if (statusText.isNotEmpty()) {
                TvText(statusText, size = TvUi.Type.MICRO, color = statusColor)
            }
            if (sendProgress in 1..99) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = gap.dp / 2)
                        .background(Color(TvUi.Pal.card), RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sendProgress / 100f)
                            .background(Color(TvUi.Pal.accent), RoundedCornerShape(4.dp))
                            .padding(vertical = 3.dp),
                    )
                }
            }
            if (filesState.isEmpty()) {
                TvText(getString(com.allperiph.tv.R.string.tv_files_empty), size = TvUi.Type.BODY, color = TvUi.Pal.textDim)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filesState, key = { it.absolutePath }) { f ->
                        TvFileRow(f) { actionTarget = f }
                    }
                }
            }
        }

        actionTarget?.let { f ->
            Dialog(onDismissRequest = { actionTarget = null }) {
                Column(
                    modifier = Modifier
                        .background(Color(TvUi.Pal.card), RoundedCornerShape(TvUi.Radius.CONTROL.dp))
                        .padding(gap.dp * 2),
                ) {
                    TvText(f.name, size = TvUi.Type.BODY)
                    TvText("${TvFiles.fileIcon(f.name)} ${TvFiles.sizeText(f.length())}", size = TvUi.Type.MICRO, color = TvUi.Pal.textDim)
                    TvButton("打开") {
                        actionTarget = null
                        openFile(f)
                    }
                    if (currentTarget() != null) {
                        TvButton("发回给手机 / 电脑") {
                            actionTarget = null
                            sendFile(f)
                        }
                    }
                    TvButton("取消") { actionTarget = null }
                }
            }
        }
    }

    @Composable
    private fun TvFileRow(f: File, onClick: () -> Unit) {
        var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(
                    Color(if (focused) TvUi.Pal.cardFocus else TvUi.Pal.card),
                    RoundedCornerShape(TvUi.Radius.CONTROL.dp),
                )
                .border(
                    width = 2.dp,
                    color = if (focused) Color(TvUi.Pal.accent) else Color.Transparent,
                    shape = RoundedCornerShape(TvUi.Radius.CONTROL.dp),
                )
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .clickable { onClick() }
                .padding(horizontal = gap.dp * 2, vertical = gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvText(
                "${TvFiles.fileIcon(f.name)}  ${f.name}",
                size = TvUi.Type.BODY,
                color = if (focused) TvUi.Pal.text else TvUi.Pal.textDim,
            )
            TvText("  ${TvFiles.sizeText(f.length())}", size = TvUi.Type.MICRO, color = TvUi.Pal.textDim)
        }
    }

    @Composable
    private fun TvText(text: String, size: Float, color: Int = TvUi.Pal.text) {
        top.yukonga.miuix.kmp.basic.Text(
            text = text,
            color = Color(color),
            fontSize = size.sp,
            fontWeight = FontWeight.Normal,
        )
    }

    @Composable
    private fun TvButton(text: String, onClick: () -> Unit) {
        var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box(
            modifier = Modifier
                .padding(vertical = 3.dp)
                .background(
                    Color(if (focused) TvUi.Pal.cardFocus else TvUi.Pal.card),
                    RoundedCornerShape(TvUi.Radius.CONTROL.dp),
                )
                .border(
                    width = 2.dp,
                    color = if (focused) Color(TvUi.Pal.accent) else Color.Transparent,
                    shape = RoundedCornerShape(TvUi.Radius.CONTROL.dp),
                )
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .clickable { onClick() }
                .padding(horizontal = gap.dp * 2, vertical = gap.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvText(text, size = TvUi.Type.BODY, color = if (focused) TvUi.Pal.text else TvUi.Pal.textDim)
        }
    }

    // ———————————————— 以下是原有逻辑，只把"写 View"换成"写状态" ————————————————

    private fun refreshTargets() {
        targets = TvFiles.targets()
        if (targetIndex >= targets.size) targetIndex = 0
        val cur = targets.getOrNull(targetIndex)
        targetLabel = when {
            cur == null -> "发给：还没有设备连过本机"
            targets.size == 1 -> "发给：$cur"
            else -> "发给：$cur · 点这里换设备（${targetIndex + 1}/${targets.size}）"
        }
    }

    private fun switchTarget() {
        if (targets.size <= 1) {
            toast(if (targets.isEmpty()) NO_TARGET_HINT else "只有一台设备连过本机，没有别的可选")
            return
        }
        targetIndex = (targetIndex + 1) % targets.size
        refreshTargets()
    }

    private fun currentTarget(): String? = targets.getOrNull(targetIndex)

    private fun refreshList() {
        val dir = TvFiles.recvDir(this)
        dirLabel = "存到：${dir.absolutePath}（共 ${TvFiles.listReceived(this).size} 个）"
        filesState = TvFiles.listReceived(this)
    }

    private fun openFile(f: File) {
        if (f.name.endsWith(".apk", ignoreCase = true)) installApk(f) else openGeneric(f)
    }

    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            status("要先允许这台电视安装应用（接下来会打开开关页）", TvUi.Pal.accent)
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ApxFileProvider.uriFor(this@TvFileActivity, f), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
            @Suppress("DEPRECATION") overridePendingTransition(com.allperiph.tv.R.anim.liquid_in, com.allperiph.tv.R.anim.liquid_out)
        } catch (e: ActivityNotFoundException) {
            status("这台电视没有安装程序，打不开这个安装包", TvUi.Pal.warn)
        }
    }

    private fun openGeneric(f: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ApxFileProvider.uriFor(this@TvFileActivity, f), TvFiles.mimeOf(f.name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
            @Suppress("DEPRECATION") overridePendingTransition(com.allperiph.tv.R.anim.liquid_in, com.allperiph.tv.R.anim.liquid_out)
        } catch (_: ActivityNotFoundException) {
            status("这台电视没有能打开它的应用（可以先发回给手机打开）", TvUi.Pal.warn)
        }
    }

    private fun showProgress(p: Int) {
        sendProgress = p
    }

    private fun sendFile(f: File) {
        val target = currentTarget() ?: run {
            status(NO_TARGET_HINT, TvUi.Pal.warn)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            status("正在发送上一个文件，等它发完", TvUi.Pal.warn)
            return
        }
        status("正在发送 ${f.name}", TvUi.Pal.accent)
        showProgress(0)
        Thread({
            val ok = FileSender.send(target, f) { p ->
                mainHandler.post {
                    status("正在发送 ${p}%", TvUi.Pal.accent)
                    showProgress(p)
                }
            }
            mainHandler.post {
                busy.set(false)
                showProgress(if (ok) 100 else 0)
                status(if (ok) "已发送：${f.name} → $target" else SEND_FAIL_HINT, if (ok) TvUi.Pal.ok else TvUi.Pal.warn)
                if (ok) mainHandler.postDelayed({ showProgress(0) }, 2000)
            }
        }, "apxtv-file-send").start()
    }

    private fun pickFromSystem() {
        if (currentTarget() == null) {
            status(NO_TARGET_HINT, TvUi.Pal.warn)
            return
        }
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_PICK,
            )
        } catch (_: ActivityNotFoundException) {
            status("这台电视没有文件选择器，可以改从手机 / 电脑发过来", TvUi.Pal.warn)
        }
    }

    @Deprecated("电视端沿用传统回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val target = currentTarget() ?: run {
            status(NO_TARGET_HINT, TvUi.Pal.warn)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val (name, size) = queryNameSize(uri)
        status("正在发送 $name", TvUi.Pal.accent)
        showProgress(0)
        Thread({
            val ok = try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    FileSender.send(target, name, size, ins) { p ->
                        mainHandler.post {
                            status("正在发送 ${p}%", TvUi.Pal.accent)
                            showProgress(p)
                        }
                    }
                } ?: false
            } catch (t: Throwable) {
                Log.e("发送失败：${t.message}")
                false
            }
            mainHandler.post {
                busy.set(false)
                showProgress(if (ok) 100 else 0)
                status(if (ok) "已发送：$name" else SEND_FAIL_HINT, if (ok) TvUi.Pal.ok else TvUi.Pal.warn)
                if (ok) mainHandler.postDelayed({ showProgress(0) }, 2000)
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
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        name = c.getString(it) ?: name
                    }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let {
                        size = c.getLong(it)
                    }
                }
            }
        }
        return name to size
    }

    private fun status(text: String, color: Int) {
        statusText = text
        statusColor = color
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && actionTarget != null) {
            actionTarget = null
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val REQ_PICK = 9001
        const val NO_TARGET_HINT = "还没有设备连过本机，先在手机端发起一次连接"
        const val SEND_FAIL_HINT = "发送失败，检查两台设备是否在同一网络"
    }
}
