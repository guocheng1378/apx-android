package com.allperiph.localsend

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

/**
 * LocalSend 发送页（MVP2 · issue#45）：列出 60s 内 announce 过的设备（每 2s 刷新）→
 * 点设备 → 系统文件选择（多选）→ 后台三步推送。依赖互传开关打开。
 *
 * 本轮迁 Compose：列表从 `ListView + ArrayAdapter + 两个 View 的 VISIBLE/GONE` 换成
 * `ComposeView` + `LazyColumn` + `mutableStateOf`，**不再需要 attachBaseContext(ThemePref.wrap)
 * 与 applyRuntimeTheme** —— 取色统一由 `ApMiuixTheme`（Miuix colorScheme）负责，
 * 这是旧外观总控（已拆成 ApPalette / ApSkin）/ `ThemePref` 下线的一步。
 */
class SendActivity : Activity() {

    companion object { private const val PICK_CODE = 0x105F }

    private var devices by mutableStateOf<List<LocalSendClient.Discovered>>(emptyList())
    private val handler = Handler(Looper.getMainLooper())
    private var picked: LocalSendClient.Discovered? = null
    private val tmpFiles = mutableListOf<java.io.File>()

    private val refresh = object : Runnable {
        override fun run() {
            devices = LocalSendClient.activeDevices()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "发送到设备"
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Text(
                                text = if (devices.isEmpty()) {
                                    "附近还没有设备：确认目标端已开启互传，且与本机在同一网络"
                                } else {
                                    "点一台设备，然后选要发送的文件（${devices.size} 台在线）"
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                items(devices) { d ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .clickable { pickFile(d) },
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                            Text(text = d.alias)
                                            Text(text = "${d.deviceType} · ${d.ip}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    private fun pickFile(target: LocalSendClient.Discovered) {
        picked = target
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), PICK_CODE)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacksAndMessages(null) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CODE || resultCode != RESULT_OK) return
        val target = picked ?: return
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i)?.uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val items = uris.mapNotNull { buildItem(it) }
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.ui_localsend_none), Toast.LENGTH_SHORT).show(); return
        }
        val deviceType = if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        val selfInfo = LocalSendProtocol.deviceInfo(android.os.Build.MODEL, deviceType, true)
        Toast.makeText(this, getString(R.string.ui_localsend_sending, items.size, target.alias), Toast.LENGTH_SHORT).show()
        val pendingTmp = tmpFiles.toList()
        Thread {
            val r = LocalSendClient.send(target, selfInfo, items)
            pendingTmp.forEach { f -> try { f.delete() } catch (_: Throwable) {} }
            runOnUiThread { Toast.makeText(applicationContext, r.message, Toast.LENGTH_LONG).show() }
        }.apply { isDaemon = true; start() }
    }

    /** Uri → SendItem：DISPLAY_NAME + 精确 size（拿不到则拷 cache 测量） */
    private fun buildItem(uri: Uri): LocalSendClient.SendItem? {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Throwable) {}
        if (size > 0) {
            return LocalSendClient.SendItem(name, size) {
                contentResolver.openInputStream(uri) ?: throw java.io.IOException("open failed")
            }
        }
        // size 拿不到：拷到 cache 精确测量（Content-Length 必须与实传字节一致）
        val tmp = java.io.File(cacheDir, "ls_push_${System.nanoTime()}")
        try {
            contentResolver.openInputStream(uri)!!.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            }
        } catch (_: Throwable) { tmp.delete(); return null }
        tmpFiles.add(tmp)
        return LocalSendClient.SendItem(name, tmp.length()) { tmp.inputStream() }
    }
}
