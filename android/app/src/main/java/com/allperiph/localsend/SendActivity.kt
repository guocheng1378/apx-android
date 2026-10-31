package com.allperiph.localsend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R

/**
 * LocalSend 发送页（MVP2 · issue#45）：
 *  列出 60s 内 announce 过的设备（每 2s 刷新）→ 点设备 → 系统文件选择（多选）→ 后台三步推送。
 *  依赖互传开关打开（LocalSendService 运行中才能收到 announce）。
 *  文件 size 优先 query OpenableColumns.SIZE；拿不到则拷 cache 精确测量（Content-Length 必须准）。
 */
class SendActivity : Activity() {

    /**
     * 主题方案（issue#44）：这些页面此前**没接** ThemePref，用户选「深色」也只按系统走 ——
     * 于是深色系统下页面还是亮色底、亮色字，或者反过来，看着"字体取色异常"。
     * 与 MainActivity / TouchpadActivity / RemoteActivity 同一套实现。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.allperiph.ui.ThemePref.wrap(newBase))
    }

    companion object { private const val PICK_CODE = 0x105F }

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var devices: List<LocalSendClient.Discovered> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var picked: LocalSendClient.Discovered? = null
    private val tmpFiles = mutableListOf<java.io.File>()

    private val refresh = object : Runnable {
        override fun run() {
            devices = LocalSendClient.activeDevices()
            listView.adapter = ArrayAdapter(
                this@SendActivity, android.R.layout.simple_list_item_1,
                devices.map { "${it.alias}（${it.deviceType} · ${it.ip}）" },
            )
            val empty = devices.isEmpty()
            emptyText.visibility = if (empty) View.VISIBLE else View.GONE
            listView.visibility = if (empty) View.GONE else View.VISIBLE
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强调色要叠在「正在用的这个主题」上才算数（清单主题是新建的，父上下文叠加会丢）
        com.allperiph.ui.ThemeSkin.applyRuntimeTheme(this)
        title = "发送到设备"
        setContentView(R.layout.page_localsend_send)
        listView = findViewById(R.id.lsDeviceList)
        emptyText = findViewById(R.id.lsEmpty)
        listView.setOnItemClickListener { _, _, pos, _ ->
            picked = devices.getOrNull(pos) ?: return@setOnItemClickListener
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), PICK_CODE)
        }
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
