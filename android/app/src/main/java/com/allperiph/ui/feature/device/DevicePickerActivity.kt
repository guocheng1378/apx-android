package com.allperiph.ui.feature.device

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.allperiph.R
import com.allperiph.ui.ClipboardSync
import com.allperiph.ui.AgentController
import com.allperiph.ui.ControlConnect
import com.allperiph.ui.ThemePref
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient
import com.allperiph.wireless.TvDiscovery

/**
 * 设备选择器：从遥控页等需要「选一个被控设备并连接」的地方拉起。
 *
 * 从旧 `ui.MainActivity.showDevicePicker` 抽取为独立 Activity —— 重构完成后
 * 旧 MainActivity 被删除，但「选设备」是独立能力，不能跟着一起没掉。
 * 选完 / 取消后会 finish 返回到发起方（如 RemoteActivity）。
 */
class DevicePickerActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPicker()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showPicker() {
        TvDiscovery.start()
        val items = ArrayList<CharSequence>()
        val adapter = ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, items)
        fun rebuild() {
            items.clear()
            items.add(getString(R.string.ui_main_item_local))
            TvDiscovery.list().forEach {
                items.add("${it.name}  ${it.ip}  ${it.typeLabel}")
            }
            if (items.size == 1) items.add(getString(R.string.ui_main_item_device))
            items.add(getString(R.string.ui_main_item_input_manual))
            adapter.notifyDataSetChanged()
        }
        rebuild()
        val dlg = AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_device_pick))
            .setAdapter(adapter) { _, which ->
                val tvs = TvDiscovery.list()
                when {
                    which == 0 -> { ControlTarget.clear() }
                    which == items.size - 1 -> promptTargetIp()
                    tvs.isEmpty() -> { /* 占位「搜索中」，忽略 */ }
                    else -> {
                        val tv = tvs.getOrNull(which - 1) ?: return@setAdapter
                        connectTarget(tv.ip, tv.port, tv.name, tv.type)
                    }
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                rebuild()
                handler.postDelayed(this, 1000)
            }
        }
        dlg.setOnShowListener { handler.postDelayed(tick, 1000) }
        dlg.setOnDismissListener { handler.removeCallbacks(tick); finish() }
        dlg.show()
    }

    private fun promptTargetIp() {
        val edit = EditText(this).apply {
            hint = getString(R.string.ui_main_hint_manual_ip)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_input))
            .setView(edit)
            .setPositiveButton(getString(R.string.ui_common_connect)) { _, _ ->
                val ip = edit.text.toString().trim()
                if (ip.isNotEmpty()) {
                    ControlConnect.connect(
                        context = this,
                        ip = ip,
                        port = TvControllerClient.PORT,
                        name = ip,
                        type = "tv",
                        onReverseClipboard = { recvReverseClipboard(it) },
                        onDone = { ok ->
                            runOnUiThread {
                                if (!ok) {
                                    Toast.makeText(this, R.string.ui_main_toast_done, Toast.LENGTH_SHORT).show()
                                }
                                finish()
                            }
                        },
                    )
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    private fun connectTarget(ip: String, port: Int, name: String, type: String) {
        ControlConnect.connect(
            context = this,
            ip = ip,
            port = port,
            name = name,
            type = type,
            onReverseClipboard = { recvReverseClipboard(it) },
            onDone = { ok ->
                runOnUiThread {
                    if (!ok) {
                        Toast.makeText(this, R.string.ui_main_toast_done, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            },
        )
    }

    private fun recvReverseClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // ⚠️ 必须先打抑制标记：本函数写的是「来自 PC 的剪贴板」，而 ClipboardSync 监听的是
        // 「本机剪贴板变化 → 发给 PC」。不打标记就会立刻原样回传，形成死循环。
        ClipboardSync.markProgrammaticWrite()
        cm?.setPrimaryClip(ClipData.newPlainText("APX", text))
        val preview = if (text.length > 24) text.take(24) + "…" else text
        Toast.makeText(this, getString(R.string.ui_main_toast_clipboard_done, preview), Toast.LENGTH_SHORT).show()
    }
}
