package com.allperiph.ui

import android.app.Fragment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.shared.net.FileSender
import com.allperiph.wireless.ControlTarget

/**
 * 文件面板 Fragment：文件发送 + 遥控键映射说明。
 *
 * 从 [MainActivity] 拆出，通过 [Host] 接口与宿主通信。
 */
class FileFragment : Fragment() {

    /** 宿主 Activity 必须实现此接口 */
    interface Host {
        fun dp(v: Int): Int
        fun sendFileToTarget(uri: Uri)
    }

    private var host: Host? = null
    private lateinit var fileBox: LinearLayout
    private lateinit var keymapBox: LinearLayout

    companion object {
        const val TAG = "FileFragment"
        const val REQ_PICK_FILE = 9002
    }

    override fun onAttach(activity: android.app.Activity?) {
        super.onAttach(activity)
        host = activity as? Host
            ?: throw IllegalStateException("${activity?.javaClass?.simpleName} must implement FileFragment.Host")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = activity ?: return
        fileBox = act.findViewById(R.id.fileBox)
        keymapBox = act.findViewById(R.id.keymapBox)
        renderFile()
        renderKeymap()
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    private fun renderFile() {
        val h = host ?: return
        fileBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)
        fileBox.addView(settingRow(getString(R.string.ui_main_label_file_send), getString(R.string.ui_main_btn_file), accent) { pickFileToSend() })
        fileBox.addView(settingRow(getString(R.string.ui_main_label_file_panel), getString(R.string.ui_main_btn_forward_view), accent) {
            startActivity(Intent(activity, FilePanelActivity::class.java))
        })
        fileBox.addView(TextView(activity).apply {
            text = getString(R.string.ui_main_text_file_connect_first)
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
            setPadding(h.dp(4), h.dp(8), h.dp(4), h.dp(2))
        })
    }

    private fun pickFileToSend() {
        if (ControlTarget.host.isEmpty() || !ControlTarget.isControlling()) {
            Toast.makeText(activity, getString(R.string.ui_main_toast_device_not), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    /** Activity 调用此方法处理文件选择结果 */
    fun onFilePicked(uri: Uri) {
        host?.sendFileToTarget(uri)
    }

    private fun renderKeymap() {
        val h = host ?: return
        keymapBox.removeAllViews()
        val lines = listOf(
            getString(R.string.ui_main_item_hotkey_touchpad_home),
            getString(R.string.ui_main_item_remote_device_only_cursor),
            getString(R.string.ui_main_item_perm_no_home),
            getString(R.string.ui_main_item_remote_keyboard_reboot_poweroff),
            "切到 TV 目标时，触摸板下方的快捷键条自动切换为「TV 遥控」预设，可长按编辑、改动单独保存",
        )
        lines.forEach { t ->
            keymapBox.addView(TextView(activity).apply {
                text = "· $t"
                textSize = 13f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
                setPadding(h.dp(4), h.dp(6), h.dp(4), h.dp(6))
            })
        }
    }

    private fun settingRow(title: String, value: String, tint: Int, onClick: () -> Unit): View {
        val h = host ?: return View(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(h.dp(4), h.dp(9), h.dp(4), h.dp(9))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(resources.getColor(R.color.md_on_surface))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(tint)
            background = ThemeSkin.softOf(tint).let { c ->
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = h.dp(999).toFloat()
                }
            }
            setPadding(h.dp(12), h.dp(5), h.dp(12), h.dp(5))
        })
        return row
    }
}