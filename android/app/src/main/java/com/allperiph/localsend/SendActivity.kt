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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.allperiph.ui.miuix.applyEdgeToEdge
import com.allperiph.ui.miuix.rememberApInsets

/**
 * LocalSend 发送页（MVP2 · issue#45）：列出 60s 内 announce 过的设备（每 2s 刷新）→
 * 点设备 → 系统文件选择（多选）→ 后台三步推送。依赖互传开关打开。
 *
 * 本轮迁 Compose：列表从 `ListView + ArrayAdapter + 两个 View 的 VISIBLE/GONE` 换成
 * `ComposeView` + `LazyColumn` + `mutableStateOf`，**不再需要 attachBaseContext(ThemePref.wrap)
 * 与 applyRuntimeTheme** —— 取色统一由 `ApMiuixTheme`（Miuix colorScheme）负责，
 * 这是旧外观总控（已拆成 ApPalette / ApSkin）/ `ThemePref` 下线的一步。
 */
class SendActivity : ComponentActivity() {

    companion object {
        private const val PICK_CODE = 0x105F
        /** Bundle 键：选中的发送目标（v223.29.73，防转屏/回收后丢失） */
        private const val STATE_PICKED = "picked_target"
    }

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
        // v223.29.73：恢复上一轮选中的发送目标（存取见 onSaveInstanceState 注释）。
        // 没有它，选文件中途页面重建后 picked 恒为 null，onActivityResult 只能空手而归。
        savedInstanceState?.getString(STATE_PICKED)?.let { raw ->
            runCatching {
                val o = org.json.JSONObject(raw)
                picked = LocalSendClient.Discovered(
                    o.getString("alias"), o.getString("deviceType"), o.getString("ip"),
                    o.getInt("port"), o.getString("fingerprint"), System.currentTimeMillis(),
                )
            }
        }
        // 全屏（成宇 2026-11-02「所有页屏蔽状态栏」）：与主壳同一口径，本页也不露系统时间。
        applyEdgeToEdge()
        // 互传服务兜底启动（2026-11-14 用户「互传还是不能用」）：本页依赖服务在听 53317
        // 并发 announce，但开关在设置里 —— 开关没开时进来就是一张永远空白的列表，
        // 谁也看不出为什么。现在进页面就兜底拉起服务（只补运行态、不动开关的记录值），
        // 对端发过来的 register/probe 才有应答，列表才有得刷新。
        if (!LocalSendService.isRunning) LocalSendService.start(this)
        title = "发送到设备"
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        // edge-to-edge 后内容自行垫边（与主壳 rememberApInsets 同口径，
                        // issue#170 第 3 条：12dp 固定边距下标题文字顶进状态栏）——
                        // 四边取「原 12dp 与系统栏/挖孔留白」的较大者，横屏挖孔侧也一并避开。
                        val bars = rememberApInsets()
                        Column(
                            modifier = Modifier.fillMaxSize().padding(
                                start = maxOf(12.dp, bars.left),
                                top = maxOf(12.dp, bars.top),
                                end = maxOf(12.dp, bars.right),
                                bottom = maxOf(12.dp, bars.bottom),
                            ),
                        ) {
                            if (devices.isEmpty()) {
                                // issue#168 返工（2026-11-12「发送文件的文字要高度（垂直）居中，
                                // 之前只把左右居中了」）：空态提示不再顶在页首 —— 占满剩余高度
                                // 后垂直居中，视线落在屏幕中部，页面不再「上半有字下半全空」。
                                Box(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "附近还没有设备：确认目标端已开启互传，且与本机在同一网络",
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                // v223.29.58（issue#168 2026-11-11）：提示居中换行 + 字色与
                                // 全局次级文案同口径（此前默认色偏黑，和其余页面观感不一）。
                                Text(
                                    text = "点一台设备，然后选要发送的文件（${devices.size} 台在线）",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    textAlign = TextAlign.Center,
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
                            // 按 IP 直连（2026-11-13）：自动发现四路（多播/受限广播/网段广播/
                            // /24 单播探测）对「同 Wi-Fi 却跨网段」的路由器无能为力（DHCP 池
                            // 跨网段很常见，两台设备一个 10.184.x 一个 10.191.x，自动发现全灭）。
                            // 手动填对端 IP 直拨一次：命中即双向入表并记住对端，之后自动直拨。
                            val ctx = LocalContext.current
                            var manualOpen by remember { mutableStateOf(false) }
                            com.allperiph.ui.miuix.ApComponents.ActionButton(
                                text = ctx.getString(
                                    if (manualOpen) R.string.ui_localsend_manual_collapse
                                    else R.string.ui_localsend_manual_btn,
                                ),
                                onClick = { manualOpen = !manualOpen },
                            )
                            if (manualOpen) {
                                val ipState = rememberTextFieldState()
                                // 2026-11-13 UI 审查：手动直连的提示从 Toast 改行内文本 ——
                                // 探测要几秒，Toast 转瞬即逝，失败原因看不见；行内常驻
                                // 提示与 v223.29.37「就地提示」口径一致，状态可回看。
                                var manualMsg by remember { mutableStateOf<String?>(null) }
                                TextField(
                                    state = ipState,
                                    label = ctx.getString(R.string.ui_localsend_manual_hint),
                                    // 与上下 ActionButton 同水平边距（ActionButton 自带
                                    // horizontal=16dp）：此前裸铺比按钮条两侧各宽 16dp，不齐
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                                com.allperiph.ui.miuix.ApComponents.ActionButton(
                                    text = ctx.getString(R.string.ui_localsend_manual_go),
                                    onClick = {
                                        val ip = ipState.text.toString().trim()
                                        if (ip.isEmpty()) {
                                            manualMsg = ctx.getString(R.string.ui_localsend_manual_hint)
                                        } else {
                                            manualMsg = ctx.getString(R.string.ui_localsend_manual_probing)
                                            LocalSendService.manualProbeStatic(ip) { ok ->
                                                // 回调在子线程，setState 本身线程安全；行内文本自会重组合
                                                manualMsg = ctx.getString(
                                                    if (ok) R.string.ui_localsend_manual_ok else R.string.ui_localsend_manual_fail,
                                                )
                                            }
                                        }
                                    },
                                )
                                manualMsg?.let { msg ->
                                    Text(
                                        text = msg,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
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

    /**
     * v223.29.73：把选中的发送目标落进 Bundle。
     * [LocalSendClient.Discovered] 不是 Parcelable，序列化成 JSON 存字符串；
     * lastSeen 无需保存，重建时用当前时间重置即可。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        picked?.let {
            outState.putString(STATE_PICKED, org.json.JSONObject().apply {
                put("alias", it.alias)
                put("deviceType", it.deviceType)
                put("ip", it.ip)
                put("port", it.port)
                put("fingerprint", it.fingerprint)
            }.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        // 探测快慢档（NectarLink 调研批）：本页在屏 = 15s 快扫 + 立即提前一轮，
        // 进页面不等旧计时；退到后台回落 90s 慢档（LocalSendService.teardown 也会清零）。
        LocalSendService.fastMode = true
        LocalSendService.wakeProber()
    }
    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        LocalSendService.fastMode = false
    }

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
        // register 请求体要带 port/protocol，对端才认得出本机的 HTTP 端口（原来只发 deviceInfo）
        val selfInfo = LocalSendProtocol.register(
            android.os.Build.MODEL, deviceType, true, LocalSendService.httpPort,
        )
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
