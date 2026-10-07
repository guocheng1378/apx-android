package com.allperiph.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.Intent
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates
import com.allperiph.ui.kit.ApKit
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient
import com.allperiph.wireless.TvDiscovery
import com.allperiph.shared.net.FileSender
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 设置页 Fragment：快捷键模板、主题方案、模块配色、TV/PC 控制、文件传输、遥控键映射。
 *
 * 从 [MainActivity] 拆出，通过 [Host] 接口与宿主通信。
 */
class SettingsFragment : Fragment() {

    /** 宿主 Activity 必须实现此接口 */
    interface Host {
        fun getSettingsHandler(): android.os.Handler
        fun getHotkeyBoard(): HotkeyBoard
        fun renderChips()
        fun applyBackdrop()
        fun recreateActivity()
        fun dp(v: Int): Int
        fun softTint(color: Int): Int
        fun pill(color: Int): GradientDrawable
        fun card(color: Int, radius: Int): GradientDrawable
        fun strokeCard(color: Int, radius: Int, stroke: Int): GradientDrawable
        fun showDevicePicker()

        /** 连入指定 TV/PC（复用宿主的 connectTarget —— 握手与回调都在那一处） */
        fun connectTarget(ip: String, port: Int, name: String, type: String)
        fun onTargetChanged()
        fun renderUplink()
        fun displayUplinkPath(): String
        fun displayUplinkHint(): String
        fun isControlling(): Boolean
        fun getControlTargetLabel(): String
        fun pickSkin(scope: String, title: String)
        fun pickBg()
        fun pickChoice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit)
        fun exportTheme()
        fun importTheme()
        fun shareTheme()
        fun handleThemeResult(uri: Uri)
        fun handleBgResult(uri: Uri)
    }

    private var host: Host? = null

    /**
     * 设置页视图是否已绑定（v196 真机教训，与 StatusFragment 同一坑）：
     * Activity 的 onCreate 会调用 renderTemplates() 等方法，而 Fragment 的视图绑定
     * 未必已经执行 —— 直接访问 lateinit 字段就是 `UninitializedPropertyAccessException`
     * 闪退（真机 21:46 复现）。所有渲染入口先过 ensureViews()。
     */
    private var viewsBound = false

    // Settings page views (in page_settings.xml)
    private lateinit var templatesBox: LinearLayout
    private lateinit var tvTemplateCurrent: TextView
    private lateinit var btnToggleTemplates: TextView
    private lateinit var themeBox: LinearLayout
    private lateinit var skinBox: LinearLayout
    private lateinit var fxBox: LinearLayout
    private lateinit var tvBox: LinearLayout
    private lateinit var fileBox: LinearLayout
    private lateinit var keymapBox: LinearLayout
    private var tvStatusView: TextView? = null
    private lateinit var swHotkeySort: Switch

    private val customPrefix = "custom:"
    private val templatePalette = intArrayOf(
        0xFF3482FF.toInt(), 0xFF7C5CFF.toInt(), 0xFF00A870.toInt(), 0xFFFF7A00.toInt(),
        0xFFE0457B.toInt(), 0xFF12B0C8.toInt(), 0xFF5B6470.toInt(), 0xFF1E9E5A.toInt(),
    )
    private var customTemplates: MutableList<HotkeyTemplates.Template> = mutableListOf()

    companion object {
        const val TAG = "SettingsFragment"

        /** 文件选择请求码。
         *  **必须与 MainActivity 的私有 REQ_PICK_FILE 同值（9002）**：宿主是普通
         *  android.app.Activity（没有 Fragment 结果分发机制），谁发起 startActivityForResult，
         *  结果都回到 MainActivity.onActivityResult，由那里的 9002 分支统一 sendFile。
         *  改这个值要同步改 MainActivity，否则文件选择结果会静默丢失。 */
        const val REQ_PICK_FILE = 9002
    }

    override fun onAttach(activity: Activity?) {
        super.onAttach(activity)
        host = activity as? Host
            ?: throw IllegalStateException("${activity?.javaClass?.simpleName} must implement SettingsFragment.Host")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return
        val act = activity ?: return

        // ⚠️ 横屏布局（layout-land/activity_main.xml）里**没有**下面这些容器（只有触控板/键盘页的 id）。
        // v196 之前是把可能为 null 的 findViewById 结果直接赋给 lateinit var —— 之后任意访问
        // 都抛 UninitializedPropertyAccessException，横屏启动必崩。现在统一探测：
        // 缺任一容器就整体不渲染（Activity 自己那套渲染逻辑仍然可用，横屏不至于崩）。
        val requiredIds = listOf(
            R.id.templatesBox, R.id.tvTemplateCurrent, R.id.btnToggleTemplates,
            R.id.themeBox, R.id.skinBox, R.id.fxBox, R.id.tvBox,
            R.id.fileBox, R.id.keymapBox, R.id.swHotkeySort, R.id.rowTemplateCurrent,
        )
        val missing = requiredIds.filter { act.findViewById<View>(it) == null }
        if (missing.isNotEmpty()) {
            com.allperiph.shared.util.Log.w(
                TAG,
                "宿主布局缺少 ${missing.size}/${requiredIds.size} 个设置页容器（横屏布局？），本次跳过渲染",
            )
            return
        }
        // 全部容器就位：放开渲染入口的守卫（此后 renderXxx 才真正干活）
        viewsBound = true

        templatesBox = act.findViewById(R.id.templatesBox)
        tvTemplateCurrent = act.findViewById(R.id.tvTemplateCurrent)
        btnToggleTemplates = act.findViewById(R.id.btnToggleTemplates)
        themeBox = act.findViewById(R.id.themeBox)
        skinBox = act.findViewById(R.id.skinBox)
        fxBox = act.findViewById(R.id.fxBox)
        tvBox = act.findViewById(R.id.tvBox)
        fileBox = act.findViewById(R.id.fileBox)
        keymapBox = act.findViewById(R.id.keymapBox)
        swHotkeySort = act.findViewById(R.id.swHotkeySort)

        act.findViewById<View>(R.id.rowTemplateCurrent).setOnClickListener { toggleTemplates() }
        btnToggleTemplates.setOnClickListener { toggleTemplates() }

        setupSettingsTabs()
        renderFile()
        renderKeymap()
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    // ==================== Settings tabs ====================

    fun setupSettingsTabs() {
        if (!viewsBound) return
        val act = activity ?: return
        val tabs = listOf(
            act.findViewById<TextView>(R.id.tabSegGeneral),
            act.findViewById<TextView>(R.id.tabSegLook),
            act.findViewById<TextView>(R.id.tabSegRemote),
            act.findViewById<TextView>(R.id.tabSegAbout),
        )
        val groups = listOf(
            act.findViewById<LinearLayout>(R.id.groupGeneral),
            act.findViewById<LinearLayout>(R.id.groupLook),
            act.findViewById<LinearLayout>(R.id.groupRemote),
            act.findViewById<LinearLayout>(R.id.groupAbout),
        )
        fun select(i: Int) {
            tabs.forEachIndexed { j, t ->
                val on = i == j
                t.setBackgroundResource(if (on) R.drawable.bg_btn_primary else 0)
                t.setTextColor(
                    if (on) resources.getColor(R.color.md_on_primary)
                    else resources.getColor(R.color.md_on_surface_variant)
                )
            }
            groups.forEachIndexed { j, g -> g.visibility = if (i == j) View.VISIBLE else View.GONE }
        }
        tabs.forEachIndexed { j, t -> t.setOnClickListener { select(j) } }
        select(0)
    }

    // ==================== Templates ====================

    fun toggleTemplates() {
        if (!viewsBound) return
        val show = templatesBox.visibility != View.VISIBLE
        templatesBox.visibility = if (show) View.VISIBLE else View.GONE
        btnToggleTemplates.text = if (show) getString(R.string.ui_main_template_collapse) else getString(R.string.ui_main_template_expand)
    }

    fun renderTemplates() {
        if (!viewsBound) return
        val h = host ?: return
        templatesBox.removeAllViews()
        customTemplates = HotkeyStore.loadCustom(activity)
        val current = HotkeyStore.loadTemplate(activity)
        val board = h.getHotkeyBoard()
        tvTemplateCurrent.text = HotkeyTemplates.byKey(current)
            ?.let { "${it.name} · ${board.shortcuts.size} 项" }
            ?: getString(R.string.ui_main_template_custom_count, board.shortcuts.size)

        (HotkeyTemplates.ALL + customTemplates).forEach { t ->
            val on = t.key == current
            val custom = t.key.startsWith(customPrefix)
            val tc = HotkeyTemplates.tint(activity, t.color)
            val act = activity ?: return@forEach
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(h.dp(12), h.dp(12), h.dp(8), h.dp(12))
                background = if (on) h.pill(h.softTint(tc))
                else h.pill(resources.getColor(R.color.md_surface_variant))
                setOnClickListener { applyTemplate(t) }
            }
            row.addView(View(act).apply {
                background = GradientDrawable().apply {
                    setColor(tc)
                    cornerRadius = h.dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(h.dp(4), h.dp(30)).apply { rightMargin = h.dp(12) })

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = if (custom) getString(R.string.ui_main_template_name_custom, t.name) else t.name
                textSize = 15f
                setTextColor(tc)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(act).apply {
                text = t.desc
                textSize = 12f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = h.dp(3) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            row.addView(TextView(act).apply {
                text = if (on) getString(R.string.ui_main_text_done) else getString(R.string.ui_main_template_combo_count, t.combos.size)
                textSize = 12f
                setTextColor(if (on) tc else resources.getColor(R.color.md_on_surface_variant))
            })
            if (custom) {
                row.addView(TextView(act).apply {
                    text = getString(R.string.ui_main_text_delete)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(h.dp(12), h.dp(6), h.dp(6), h.dp(6))
                    setTextColor(resources.getColor(R.color.state_error))
                    isClickable = true
                    setOnClickListener { confirmDeleteTemplate(t) }
                })
            }
            templatesBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = h.dp(8) })
        }

        templatesBox.addView(TextView(activity).apply {
            text = getString(R.string.ui_main_text_hotkey_template)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.md_primary))
            background = h.strokeCard(
                resources.getColor(R.color.md_primary_container),
                h.dp(999),
                (resources.getColor(R.color.md_primary) and 0x00FFFFFF) or (0x40 shl 24),
            )
            setPadding(h.dp(16), h.dp(14), h.dp(16), h.dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { createTemplateDialog() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.dp(4) })
    }

    private fun createTemplateDialog() {
        val h = host ?: return
        val act = activity ?: return
        val board = h.getHotkeyBoard()
        if (board.shortcuts.isEmpty()) {
            Toast.makeText(act, "快捷键条还是空的，先在触控板页加几条", Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(h.dp(20), h.dp(6), h.dp(20), h.dp(6))
        }
        box.addView(fieldLabel(getString(R.string.ui_main_label_template)))
        val nameEt = EditText(act).apply {
            hint = getString(R.string.ui_main_hint_hotkey)
            textSize = 15f
            setSingleLine()
            setTextColor(resources.getColor(R.color.md_on_surface))
        }
        box.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.dp(4) })

        box.addView(fieldLabel(getString(R.string.ui_main_label_theme)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = h.dp(14) })
        var pickedIndex = 0
        val dots = mutableListOf<TextView>()
        val colorRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        templatePalette.forEachIndexed { idx, color ->
            val dot = TextView(act).apply {
                background = dotBg(color, idx == 0)
                isClickable = true
                setOnClickListener {
                    pickedIndex = idx
                    dots.forEachIndexed { k, d -> d.background = dotBg(templatePalette[k], k == idx) }
                }
            }
            dots += dot
            colorRow.addView(dot, LinearLayout.LayoutParams(h.dp(28), h.dp(28)).apply { rightMargin = h.dp(10) })
        }
        box.addView(colorRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = h.dp(8) })
        box.addView(TextView(act).apply {
            text = getString(R.string.ui_main_text_hotkey_touchpad, board.shortcuts.size)
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.dp(14) })

        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_template_new))
            .setView(box)
            .setPositiveButton(getString(R.string.ui_common_save), null)
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val t = HotkeyTemplates.Template(
                            key = customPrefix + System.currentTimeMillis(),
                            name = nameEt.text.toString().trim().ifEmpty { getString(R.string.ui_main_text_template) },
                            desc = "${board.shortcuts.size} 项 · 自建",
                            color = templatePalette[pickedIndex],
                            combos = board.shortcuts.toList(),
                        )
                        customTemplates.add(t)
                        HotkeyStore.saveCustom(activity, customTemplates)
                        applyTemplate(t)
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun dotBg(color: Int, selected: Boolean): GradientDrawable = ApKit.oval(
        color,
        strokeColor = if (selected) 0xFF191919.toInt() else 0x33000000,
        strokePx = if (selected) (host?.dp(3) ?: 0) else (host?.dp(1) ?: 0),
    )

    private fun confirmDeleteTemplate(t: HotkeyTemplates.Template) {
        val act = activity ?: return
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_template_delete))
            .setMessage(getString(R.string.ui_main_dialog_hotkey_delete, t.name))
            .setPositiveButton(getString(R.string.ui_common_delete)) { _, _ ->
                customTemplates.removeAll { it.key == t.key }
                HotkeyStore.saveCustom(act, customTemplates)
                if (HotkeyStore.loadTemplate(act) == t.key) HotkeyStore.markCustom(act)
                renderTemplates()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    private fun applyTemplate(t: HotkeyTemplates.Template) {
        HotkeyStore.applyTemplate(activity, t)
        host?.getHotkeyBoard()?.reload()
        renderTemplates()
    }

    // ==================== Theme ====================

    fun renderTheme() {
        if (!viewsBound) return
        val h = host ?: return
        themeBox.removeAllViews()
        val current = ThemePref.get(activity)
        ThemePref.LABELS.forEachIndexed { i, label ->
            val on = i == current
            val act = activity ?: return@forEachIndexed
            val t = TextView(act).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, h.dp(11), 0, h.dp(11))
                isClickable = true
                isFocusable = true
                background = h.pill(
                    resources.getColor(if (on) R.color.md_primary else R.color.md_primary_container)
                )
                setTextColor(resources.getColor(if (on) R.color.md_on_primary else R.color.md_primary))
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setOnClickListener {
                    if (i != ThemePref.get(act)) {
                        ThemePref.set(act, i)
                        h.recreateActivity()
                    }
                }
            }
            themeBox.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(h.dp(3), 0, h.dp(3), 0)
            })
        }
    }

    // ==================== Skins & FX ====================

    fun renderSkins() {
        if (!viewsBound) return
        val h = host ?: return
        skinBox.removeAllViews()
        ThemeSkin.SCOPES.forEach { (scope, title) ->
            val skin = ThemeSkin.current(activity, scope)
            skinBox.addView(settingRow(title, skin.name, skin.accent) { h.pickSkin(scope, title) })
        }
    }

    fun renderFx() {
        if (!viewsBound) return
        val h = host ?: return
        fxBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)

        val bg = ThemeSkin.BG_PRESETS.firstOrNull { it.id == ThemeSkin.bgId(activity) } ?: ThemeSkin.BG_PRESETS[0]
        val img = ThemeSkin.bgImage(activity)
        fxBox.addView(settingRow(
            getString(R.string.ui_main_label_background), if (!img.isNullOrBlank()) getString(R.string.ui_main_text_image) else bg.name, accent
        ) { h.pickBg() })
        fxBox.addView(settingRow(getString(R.string.ui_main_label_motion), ThemeSkin.MOTION_LABELS[ThemeSkin.motionIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_motion_2), ThemeSkin.MOTION_LABELS, ThemeSkin.motionIndex(activity)) { i ->
                ThemeSkin.setMotion(activity, i)
                renderFx()
            }
        })
        fxBox.addView(settingRow(getString(R.string.ui_main_label_haptic), ThemeSkin.HAPTIC_LABELS[ThemeSkin.hapticIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_haptic_2), ThemeSkin.HAPTIC_LABELS, ThemeSkin.hapticIndex(activity)) { i ->
                ThemeSkin.setHaptic(activity, i)
                renderFx()
            }
        })
        fxBox.addView(settingRow(getString(R.string.ui_main_label_sound), ThemeSkin.SOUND_LABELS[ThemeSkin.soundIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_sound_2), ThemeSkin.SOUND_LABELS, ThemeSkin.soundIndex(activity)) { i ->
                ThemeSkin.setSound(activity, i)
                renderFx()
            }
        })
        fxBox.addView(settingRow(getString(R.string.ui_main_label_keyrow_arrange), KeyPref.LAYOUT_LABELS[KeyPref.layoutIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_keyrow_arrange), KeyPref.LAYOUT_LABELS, KeyPref.layoutIndex(activity)) { i ->
                KeyPref.setLayout(activity, i)
                h.recreateActivity()
            }
        })
        fxBox.addView(settingRow(getString(R.string.ui_main_label_keycap_density), KeyPref.DENSITY_LABELS[KeyPref.densityIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_keycap_density), KeyPref.DENSITY_LABELS, KeyPref.densityIndex(activity)) { i ->
                KeyPref.setDensity(activity, i)
                h.recreateActivity()
            }
        })
        fxBox.addView(settingRow(
            getString(R.string.ui_main_label_keyboard), if (KeyPref.customRows(activity) == null) getString(R.string.ui_main_text_not_2) else getString(R.string.ui_main_text_done_3), accent
        ) {
            CustomKeyboardDialog.show(activity) { h.recreateActivity() }
        })
    }

    private fun settingRow(title: String, value: String, tint: Int, onClick: () -> Unit): View {
        val h = host ?: return View(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            background = h.pill(ThemeSkin.softOf(tint))
            setPadding(h.dp(12), h.dp(5), h.dp(12), h.dp(5))
        })
        return row
    }

    private fun fieldLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(resources.getColor(R.color.md_on_surface_variant))
    }

    // ==================== TV / PC control ====================

    fun renderTv() {
        if (!viewsBound) return
        val h = host ?: return
        tvBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)
        tvBox.addView(settingRow(getString(R.string.ui_main_label_device_connect), getString(R.string.ui_main_btn_pick), accent) { h.showDevicePicker() })
        if (h.isControlling()) {
            tvBox.addView(settingRow(getString(R.string.ui_main_label_connect_disconnect), getString(R.string.ui_main_btn_to_local_local), accent) { ControlTarget.clear(); h.onTargetChanged() })
        }
        tvBox.addView(settingRow(getString(R.string.ui_main_label_remote_power_volume), getString(R.string.ui_common_open), accent) {
            startActivity(Intent(activity, RemoteActivity::class.java))
        })
        tvBox.addView(settingRow(getString(R.string.ui_main_label_screen_touchpad_keyboard_fullscreen), getString(R.string.ui_common_open), accent) {
            startActivity(Intent(activity, com.allperiph.touchpad.TouchpadActivity::class.java))
        })
        tvBox.addView(settingRow(getString(R.string.ui_main_label_state_channel_inject), getString(R.string.ui_main_btn_selftest), accent) { showControlledCaps() })
        tvBox.addView(settingRow(getString(R.string.ui_main_label_log_view), getString(R.string.ui_common_open), accent) { showLogs() })
        // 最近连过的设备（ConnectHistory 持久化，v196）：点一下直接重连，不用重新挑设备。
        //   这段曾加在 MainActivity.renderTv() 里，而那个方法后来改成转发到本 Fragment ——
        //   随之被一起丢掉了（ConnectHistory 只写不读、文案零引用），现在补在真正的渲染方。
        val act = activity ?: return
        val recent = ConnectHistory.getRecent(act, 3)
        tvBox.addView(fieldLabel(getString(R.string.ui_main_label_recent_devices)))
        if (recent.isEmpty()) {
            tvBox.addView(TextView(act).apply {
                text = getString(R.string.ui_main_text_no_recent)
                textSize = 12f
                setTextColor(act.resources.getColor(R.color.md_on_surface_variant))
                setPadding(h.dp(4), h.dp(2), h.dp(4), h.dp(6))
            })
        } else {
            for (item in recent) {
                tvBox.addView(settingRow(item.name, "${item.host}:${item.port}", accent) {
                    // 复用宿主的连入逻辑（Activity.connectTarget）：设置器、握手、
                    // 反向剪贴板回调都在那一处，这里不重复实现。
                    host?.connectTarget(item.host, item.port, item.name, "tv")
                })
            }
        }
        tvStatusView = TextView(activity).apply {
            text = if (h.isControlling()) {
                "当前控制：${h.getControlTargetLabel()} · ${h.displayUplinkHint()}"
            } else {
                "未连接（触摸板控本机）· ${h.displayUplinkHint()}"
            }
            textSize = 13f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
            setPadding(host?.dp(4) ?: 0, host?.dp(8) ?: 0, host?.dp(4) ?: 0, host?.dp(2) ?: 0)
        }
        tvBox.addView(tvStatusView)
    }

    private fun showControlledCaps() {
        val inj = com.allperiph.shared.inject.TvInjector
        val lines = inj.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        val running = com.allperiph.controlled.ControlledService.isRunning()
        AlertDialog.Builder(activity, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_label_state_channel_inject))
            .setMessage(
                "当前通道：${inj.channelText()}\n" +
                        "被控服务：${if (running) "运行中" else "未运行（在「全屏操控面」里点「被控」开启）"}\n\n" +
                        lines
            )
            .setPositiveButton(getString(R.string.ui_main_btn_a11y_settings)) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .setNegativeButton(getString(R.string.ui_common_close), null)
            .show()
    }

    private fun showLogs() {
        val snap = com.allperiph.shared.util.Log.snapshot()
        val text = if (snap.isEmpty()) {
            "暂无日志 —— 只有达到「日志级别」的记录才会留下"
        } else {
            snap.takeLast(120).joinToString("\n") { e -> "${e.level} ${e.tag}: ${e.msg}" }
        }
        AlertDialog.Builder(activity, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_log, minOf(snap.size, 120)))
            .setMessage(text)
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
    }

    // ==================== File ====================

    fun renderFile() {
        if (!viewsBound) return
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
            setPadding(host?.dp(4) ?: 0, host?.dp(8) ?: 0, host?.dp(4) ?: 0, host?.dp(2) ?: 0)
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

    // ==================== Keymap ====================

    fun renderKeymap() {
        if (!viewsBound) return
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

    // ==================== Hotkey sort ====================

    fun bindHotkeySort() {
        val h = host ?: return
        swHotkeySort.isChecked = HotkeyStore.isAutoSort(activity)
        swHotkeySort.setOnCheckedChangeListener { _, on ->
            HotkeyStore.setAutoSort(activity, on)
            h.renderChips()
        }
    }

    fun syncSortSwitch() {
        val on = HotkeyStore.isAutoSort(activity)
        if (swHotkeySort.isChecked != on) swHotkeySort.isChecked = on
    }
}