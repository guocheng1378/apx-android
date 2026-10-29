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
import android.text.TextUtils
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
    /**
     * 自建模板的色板：**与内置模板同一套颜色令牌**（`R.color.hotkey_tpl_*`），
     * 不再在 Kotlin 里内联 ARGB —— 深浅色两套取值由 res/values 与 values-night 决定。
     */
    private val templatePaletteRes = intArrayOf(
        R.color.hotkey_tpl_office, R.color.hotkey_tpl_ai, R.color.hotkey_tpl_code,
        R.color.hotkey_tpl_video, R.color.hotkey_tpl_slide, R.color.hotkey_tpl_design,
        R.color.hotkey_tpl_term, R.color.hotkey_tpl_sheet,
    )

    /** 色板取当前主题下的实际色值（自定义模板存的是色值，不是资源 id） */
    private fun templatePalette(): IntArray =
        templatePaletteRes.map { resources.getColor(it) }.toIntArray()
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

    /**
     * 绑定并校验设置页容器（**懒绑定，可重试**，与 StatusFragment.ensureViews() 同款）。
     *
     * ⚠️ 绑定**不能只放在 onViewCreated 里**：本 Fragment 的 [onCreateView] 返回 null（复用宿主
     * Activity 的视图层级，不独立 inflate），而平台 android.app.Fragment 在 view == null 时
     * **根本不会调用 onViewCreated**（FragmentManagerImpl 里 `if (f.mView != null)` 才调）。
     * 实测真机后果：设置页除「功能开关」（由 StatusFragment 代渲染）外**全部空白** ——
     * 模板显示"—"、主题/配色/背景/TV控制/文件/键位映射全空、四个分段点了没反应，
     * 因为每个渲染入口的 `if (!viewsBound) return` 都被吞掉，且此后没有任何重试机会。
     * 改成懒绑定后，任一渲染入口进来都能补绑一次，横屏/竖屏都不再依赖生命周期时机。
     */
    private fun ensureViews(): Boolean {
        if (viewsBound) return true
        if (host == null) return false
        val act = activity ?: return false

        // 缺任一容器就整体不渲染（宿主布局不同朝向可能有差异，宁可不画也不要崩）
        val requiredIds = listOf(
            R.id.templatesBox, R.id.tvTemplateCurrent, R.id.btnToggleTemplates,
            R.id.themeBox, R.id.skinBox, R.id.fxBox, R.id.tvBox,
            R.id.fileBox, R.id.keymapBox, R.id.swHotkeySort, R.id.rowTemplateCurrent,
        )
        val missing = requiredIds.filter { act.findViewById<View>(it) == null }
        if (missing.isNotEmpty()) {
            com.allperiph.shared.util.Log.w(
                TAG,
                "宿主布局缺少 ${missing.size}/${requiredIds.size} 个设置页容器，本次跳过渲染",
            )
            return false
        }

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

        // 先置位再渲染：下面这几个方法自身也会过 ensureViews()，避免递归重入
        viewsBound = true
        setupSettingsTabs()
        renderFile()
        renderKeymap()
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureViews()
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    // ==================== Settings tabs ====================

    fun setupSettingsTabs() {
        if (!ensureViews()) return
        val act = activity ?: return
        // 批E：触摸板光标锁定开关（issue#44）；默认开，写 apx prefs 供触控板链路读取
        val cursorLockSwitch = act.findViewById<android.widget.Switch>(R.id.switchCursorLock)
        val apxPrefs = act.getSharedPreferences("apx", android.content.Context.MODE_PRIVATE)
        cursorLockSwitch?.isChecked = apxPrefs.getBoolean("cursor_lock", true)
        cursorLockSwitch?.setOnCheckedChangeListener { _, isChecked ->
            apxPrefs.edit().putBoolean("cursor_lock", isChecked).apply()
        }
        // 三指手势遮蔽（root 动态）：开关只写偏好，真正的系统键值切换在进/出触控板时做
        // （见 GestureShield）；点行主体 = 说明 + 无 root 的手动兜底路径。
        val shieldSwitch = act.findViewById<android.widget.Switch>(R.id.switchGestureShield)
        shieldSwitch?.isChecked = com.allperiph.touchpad.GestureShield.isEnabled(act)
        shieldSwitch?.setOnCheckedChangeListener { _, isChecked ->
            com.allperiph.touchpad.GestureShield.setEnabled(act, isChecked)
        }
        act.findViewById<android.view.View>(R.id.rowGestureShield)?.setOnClickListener {
            showGestureShieldHelp()
        }
        // 批G：LocalSend 互传开关（issue#45）——开=启动接收前台服务，关=停止
        val localSendSwitch = act.findViewById<android.widget.Switch>(R.id.switchLocalSend)
        localSendSwitch?.isChecked = apxPrefs.getBoolean("localsend_enabled", false)
        localSendSwitch?.setOnCheckedChangeListener { _, isChecked ->
            apxPrefs.edit().putBoolean("localsend_enabled", isChecked).apply()
            if (isChecked) com.allperiph.localsend.LocalSendService.start(act)
            else com.allperiph.localsend.LocalSendService.stop(act)
        }
        // 批H：点行主体 → 发送页（与 Switch 开关互不干扰）
        act.findViewById<android.view.View>(R.id.rowLocalSend)?.setOnClickListener { v ->
            v.context.startActivity(android.content.Intent(v.context, com.allperiph.localsend.SendActivity::class.java))
        }
        // 批O：手动导出运行日志（issue 建议）——自进程 logcat → Download/AllPeriph/。
        // 落盘与提示复用 exportLog 那条口径（同一份 saveToDownloads / 头部 / 文案），
        // 与运行日志、Logcat 两个对话框的「导出」完全一致。
        act.findViewById<android.view.View>(R.id.rowExportLog)?.setOnClickListener {
            Toast.makeText(act, getString(R.string.ui_log_collecting), Toast.LENGTH_SHORT).show()
            Thread {
                val raw = try {
                    val pid = android.os.Process.myPid()
                    val p = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-v", "time", "-t", "5000", "--pid=$pid"),
                    )
                    p.inputStream.bufferedReader().readText()
                } catch (e: Throwable) {
                    ""
                }
                val msg = try {
                    if (raw.isBlank()) {
                        getString(R.string.ui_log_empty)
                    } else {
                        val fileName = "log_${System.currentTimeMillis()}.txt"
                        saveToDownloads(
                            act.applicationContext, fileName, logHead() + raw.takeLast(890_000),
                        )
                        getString(R.string.ui_log_exported, fileName)
                    }
                } catch (e: Throwable) {
                    getString(R.string.ui_log_export_failed, e.message ?: "")
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                }
            }.apply { isDaemon = true; setName("apx-log-export") }.start()
        }
        bindCategoryNav()
        showHome()
    }

    // ==================== 分类导航 ====================

    /** 首页分类行 → 分组容器（点行进二级页）。 */
    private var categoryRows: List<Pair<Int, Int>> = emptyList()

    /** 所有「页」的分组容器 id：切页时先全部收起（含首页没有入口的传输分类页）。 */
    private var categoryPages: List<Int> = emptyList()

    private var navHome: View? = null
    private var navBack: View? = null

    private fun bindCategoryNav() {
        val act = activity ?: return
        navHome = act.findViewById(R.id.categoryHome)
        navBack = act.findViewById(R.id.btnBackHome)
        // 反馈#10 批 B（方案 B）：首页 = 分类列表，点行进二级页，返回行回首屏；
        // 分组容器复用原四个 group*，另加帮助组 groupHelp。原四分段 tab 已删。
        categoryRows = listOf(
            R.id.catGeneral to R.id.groupGeneral,
            R.id.catLook to R.id.groupLook,
            R.id.catRemote to R.id.groupRemote,
            R.id.catAbout to R.id.groupAbout,
            R.id.catHelp to R.id.groupHelp,
        )
        // 传输分类页（无线 / 蓝牙 / 有线）**没有首页分类行** —— 入口是设置页左栏那三个传输
        // 按钮，见 [showCategory]。它们同样要参与「切页先全收」，所以一并登记。
        categoryPages = categoryRows.map { it.second } +
            listOf(R.id.groupWifi, R.id.groupBt, R.id.groupUsb)
        categoryRows.forEach { (row, gid) ->
            act.findViewById<View>(row)?.setOnClickListener { showGroup(gid) }
        }
        navBack?.setOnClickListener { showHome() }
    }

    /** 进二级页：藏首页、显示返回行、只留目标分组，并回到右栏顶部。 */
    private fun showGroup(groupId: Int) {
        val act = activity ?: return
        navHome?.visibility = View.GONE
        navBack?.visibility = View.VISIBLE
        categoryPages.forEach { act.findViewById<View>(it)?.visibility = View.GONE }
        act.findViewById<View>(groupId)?.visibility = View.VISIBLE
        scrollTop()
    }

    /** 回首页（分类列表）。 */
    private fun showHome() {
        val act = activity ?: return
        navHome?.visibility = View.VISIBLE
        navBack?.visibility = View.GONE
        categoryPages.forEach { act.findViewById<View>(it)?.visibility = View.GONE }
        scrollTop()
    }

    /**
     * 按名字进分类页：`"wifi" / "bt" / "usb"`（左栏三个传输按钮）与 `"help"`（遥控 tab 的
     * 键映射行跳说明）。未知名字什么也不做 —— 宁可不动，也不要停在一个不相干的分类里。
     */
    fun showCategory(category: String) {
        if (!ensureViews()) return
        val gid = when (category) {
            "wifi" -> R.id.groupWifi
            "bt" -> R.id.groupBt
            "usb" -> R.id.groupUsb
            "help" -> R.id.groupHelp
            "general" -> R.id.groupGeneral
            "look" -> R.id.groupLook
            "remote" -> R.id.groupRemote
            "about" -> R.id.groupAbout
            else -> return
        }
        showGroup(gid)
    }

    /** 回到右栏顶部（设置页两栏后没有外层 ScrollView，要滚的是右栏那个容器）。 */
    private fun scrollTop() {
        (activity?.findViewById<View>(R.id.paneGroupsScroll) as? android.widget.ScrollView)
            ?.fullScroll(View.FOCUS_UP)
    }

    // ==================== Templates ====================

    fun toggleTemplates() {
        if (!ensureViews()) return
        val show = templatesBox.visibility != View.VISIBLE
        templatesBox.visibility = if (show) View.VISIBLE else View.GONE
        btnToggleTemplates.text = if (show) getString(R.string.ui_main_template_collapse) else getString(R.string.ui_main_template_expand)
    }

    fun renderTemplates() {
        if (!ensureViews()) return
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
            // 取色统一走 resolveColor：内置模板是颜色资源，自定义模板是持久化的字面量
            val tc = HotkeyTemplates.tint(activity, HotkeyTemplates.resolveColor(activity, t))
            val act = activity ?: return@forEach
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12))
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
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = h.dp(4) })
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
                    setPadding(h.dp(12), h.dp(6), 0, h.dp(6))
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
            Toast.makeText(act, act.getString(R.string.ui_template_bar_empty), Toast.LENGTH_SHORT).show()
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
        val palette = templatePalette()
        palette.forEachIndexed { idx, color ->
            val dot = TextView(act).apply {
                background = dotBg(color, idx == 0)
                isClickable = true
                setOnClickListener {
                    pickedIndex = idx
                    dots.forEachIndexed { k, d -> d.background = dotBg(palette[k], k == idx) }
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
                            color = palette[pickedIndex],
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
        strokeColor = context?.getColor(if (selected) R.color.dot_stroke_selected else R.color.dot_stroke_normal) ?: 0,
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
        if (!ensureViews()) return
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
        if (!ensureViews()) return
        val h = host ?: return
        skinBox.removeAllViews()
        ThemeSkin.SCOPES.forEach { (scope, title) ->
            val skin = ThemeSkin.current(activity, scope)
            skinBox.addView(settingRow(title, skin.name, skin.accent) { h.pickSkin(scope, title) })
        }
    }

    fun renderFx() {
        if (!ensureViews()) return
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
        // 分组（v206）：上面 4 行是外观与手感（背景/动效/震动/按键音），从这里是键盘外观 ——
        // 卡标题只有一个，故在卡内加小标题分段（与 renderTv 的「连接/工具」同款）。
        fxBox.addView(fieldLabel(getString(R.string.lay_settings_group_keyboard_look)))
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
        fxBox.addView(settingRow(getString(R.string.ui_main_label_keycap_style), KeyPref.KEYCAP_STYLE_LABELS[KeyPref.keycapStyleIndex(activity)], accent) {
            h.pickChoice(getString(R.string.ui_main_label_keycap_style), KeyPref.KEYCAP_STYLE_LABELS, KeyPref.keycapStyleIndex(activity)) { i ->
                KeyPref.setKeycapStyle(activity, i)
                h.recreateActivity()
            }
        })
        fxBox.addView(settingRow(
            getString(R.string.ui_main_label_keyboard), if (KeyPref.customRows(activity) == null) getString(R.string.ui_main_text_not_2) else getString(R.string.ui_main_text_done_3), accent
        ) {
            CustomKeyboardDialog.show(activity) { h.recreateActivity() }
        })
    }

    /**
     * 一行设置项（v223 版式）：`标题（单行，超长省略）+ 右侧取值胶囊 / 箭头` +「说明」整行。
     *
     * 为什么改：设置页右栏只占屏幕 2/3（竖屏 ≈190dp，去掉卡片内边距只剩 ≈159dp）。
     * 原先把「遥控器（方向 / OK / 音量 / 电源）」这类带括号的长标题直接当作标题，
     * 在窄列里折成两三行，还要和右侧胶囊抢宽度 —— 真机反馈「右边的分组文字太长」。
     * 现在：标题只留词组（单行），解释下沉到 12sp 说明行；说明行独占整行宽，
     * 「方向 / OK / 音量 / 电源」这种内容才排得开。
     *
     * @param value 右侧取值胶囊文案；传 null 表示「进入下级」的行，右侧只画一个箭头
     * @param hint  标题下的说明行；传 null 表示这一行不需要说明
     */
    private fun settingRow(
        title: String,
        value: String?,
        tint: Int,
        hint: String? = null,
        onClick: () -> Unit,
    ): View {
        val h = host ?: return View(activity)
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(resources.getColor(R.color.md_on_surface))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) {
            line.addView(TextView(activity).apply {
                text = value
                textSize = 13f
                setTextColor(tint)
                background = h.pill(ThemeSkin.softOf(tint))
                setPadding(h.dp(12), h.dp(5), h.dp(12), h.dp(5))
            })
        } else {
            line.addView(TextView(activity).apply {
                text = getString(R.string.ui_common_chevron)
                textSize = 18f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
            })
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            // 左右不留内缩：卡片自身的 16dp 内边距已经是行的边距，
            // 再缩 4dp 会让标题、说明、分组表头三处竖线各差一截。
            setPadding(0, h.dp(9), 0, h.dp(9))
            setOnClickListener { onClick() }
        }
        row.addView(line, LinearLayout.LayoutParams(-1, -2))
        if (hint != null) {
            row.addView(TextView(activity).apply {
                text = hint
                textSize = 12f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.dp(2) })
        }
        return row
    }

    /** 卡内小节标题（连接 / 工具 / 键盘外观…）：比分组表头低一级，比说明行高一级。 */
    private fun fieldLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.md_on_surface_variant))
    }

    // ==================== TV / PC control ====================

    fun renderTv() {
        if (!ensureViews()) return
        val h = host ?: return
        tvBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)
        // 分组（v206）：「连接」小节 = 选设备/断开/最近设备/状态；「工具」小节 = 遥控面板/全屏/自检/日志
        // v223：进入下级（打开弹窗/跳页）的行改用行尾箭头，取值行才用胶囊 ——
        // 「打开」「自检」这类胶囊在 190dp 的窄列里要吃掉 60dp，标题只能折行。
        tvBox.addView(fieldLabel(getString(R.string.lay_settings_group_connect)))
        tvBox.addView(settingRow(getString(R.string.ui_main_label_device_connect), null, accent) { h.showDevicePicker() })
        if (h.isControlling()) {
            tvBox.addView(settingRow(getString(R.string.ui_main_label_connect_disconnect), getString(R.string.ui_main_btn_to_local_local), accent) { ControlTarget.clear(); h.onTargetChanged() })
        }
        tvBox.addView(fieldLabel(getString(R.string.lay_settings_group_tools)))
        tvBox.addView(settingRow(
            getString(R.string.ui_main_label_remote_power_volume), null, accent,
            hint = getString(R.string.ui_main_hint_remote_keys),
        ) {
            startActivity(Intent(activity, RemoteActivity::class.java))
        })
        tvBox.addView(settingRow(
            getString(R.string.ui_main_label_screen_touchpad_keyboard_fullscreen), null, accent,
            hint = getString(R.string.ui_main_hint_console),
        ) {
            startActivity(Intent(activity, com.allperiph.touchpad.TouchpadActivity::class.java))
        })
        tvBox.addView(settingRow(getString(R.string.ui_main_label_state_channel_inject), null, accent) { showControlledCaps() })
        tvBox.addView(settingRow(getString(R.string.ui_main_label_log_view), null, accent) { showLogs() })
        // 反馈#4：环形缓冲只留「日志级别」，信息量不够 —— 这里给全进程/内核 tag 的真 logcat
        tvBox.addView(settingRow(getString(R.string.ui_main_label_logcat), null, accent) { showLogcat() })
        // 反馈#7：悬浮球 —— 独立 overlay 触控板（绕开 HyperOS 小窗失焦不能输入）；无权限先跳授权
        tvBox.addView(
            settingRow(
                getString(R.string.ui_main_label_floating_ball),
                getString(
                    if (FloatingBallService.isRunning) R.string.ui_main_floating_ball_on
                    else R.string.ui_main_floating_ball_off,
                ),
                accent,
            ) {
                activity?.let { ctx -> FloatingBallService.requestToggle(ctx) }
                renderTv()
            },
        )
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
                tvBox.addView(settingRow(item.name, null, accent, hint = "${item.host}:${item.port}") {
                    // 复用宿主的连入逻辑（Activity.connectTarget）：设置器、握手、
                    // 反向剪贴板回调都在那一处，这里不重复实现。
                    host?.connectTarget(item.host, item.port, item.name, "tv")
                })
            }
        }
        tvStatusView = TextView(activity).apply {
            // 文案迁资源（v206）：原先两串硬编码中文，与本页其他界面的 getString 约定不一致
            text = if (h.isControlling()) {
                getString(R.string.lay_settings_status_target, h.getControlTargetLabel(), h.displayUplinkHint())
            } else {
                getString(R.string.lay_settings_status_local, h.displayUplinkHint())
            }
            textSize = 13f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
            setPadding(host?.dp(4) ?: 0, host?.dp(8) ?: 0, host?.dp(4) ?: 0, host?.dp(2) ?: 0)
        }
        tvBox.addView(tvStatusView)
    }

    /**
     * 三指手势遮蔽说明：root 动态方案 + 无 root 时的手动兜底路径。
     * 兜底不做自动跳转 —— MIUI 快捷手势页的 Activity 名不公开，跳错页面更迷惑，
     * 只给准确路径 + 一个「打开系统设置」。
     */
    private fun showGestureShieldHelp() {
        val act = activity ?: return
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_gesture_shield_dialog_title))
            .setMessage(getString(R.string.ui_gesture_shield_dialog_msg))
            .setPositiveButton(getString(R.string.ui_gesture_shield_btn_settings)) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton(getString(R.string.ui_common_close), null)
            .show()
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
            getString(R.string.ui_log_empty_hint)
        } else {
            snap.takeLast(120).joinToString("\n") { e -> "${e.level} ${e.tag}: ${e.msg}" }
        }
        AlertDialog.Builder(activity, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_dialog_log, minOf(snap.size, 120)))
            .setMessage(text)
            // 真机反馈「两个日志都没有导出和复制的功能」：日志对话框此前只能看、带不走。
            // 中立位 = 复制，否定位 = 导出，肯定位 = 关闭（AlertDialog 三键就三处）。
            .setNeutralButton(getString(R.string.ui_common_copy)) { _, _ -> copyLog(text) }
            .setNegativeButton(getString(R.string.ui_log_export)) { _, _ -> exportLog("log", text) }
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
    }

    /**
     * Logcat 系统日志（反馈#4）：showLogs() 的环形缓冲只到「日志级别」，
     * 这里经 root 抓**全进程全 tag** 的真 logcat；无 root 时降级为本进程日志
     * （App 读自己的日志不需要 READ_LOGS）。后台线程抓取，抓到回填对话框。
     */
    private fun showLogcat() {
        val act = activity ?: return
        // 抓取在后台（root logcat 可能要几秒），而「复制 / 导出」按钮**点开时就绑定**了内容 ——
        // 所以最新文本放字段里，按钮读字段而不是闭包捕获的初值（否则永远只能复制到「读取中…」）。
        logcatText = ""
        val dlg = AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(getString(R.string.ui_main_label_logcat))
            .setMessage(getString(R.string.ui_loading))
            .setNeutralButton(getString(R.string.ui_common_copy)) { _, _ -> copyLog(logcatText) }
            .setNegativeButton(getString(R.string.ui_log_export)) { _, _ -> exportLog("logcat", logcatText) }
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
        // 抓到之前没有内容可拿：两个动作先禁用，抓到再放开（按钮在对话框显示后才会存在）。
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        kotlin.concurrent.thread(name = "apx-logcat") {
            val out = readLogcat()
            activity?.runOnUiThread {
                logcatText = out
                if (dlg.isShowing) {
                    dlg.setMessage(out.takeLast(12000))
                    dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                    dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                }
            }
        }
    }

    // ==================== 日志带走（复制 / 导出） ====================

    /** Logcat 对话框当前文本：后台抓完才填，复制 / 导出从这里读。 */
    private var logcatText: String = ""

    /** 复制整段日志到系统剪贴板（顺带会被「手机复制 → 电脑粘贴」的剪贴板同步带去电脑）。 */
    private fun copyLog(text: String) {
        val ctx = activity ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("APX-log", text))
        Toast.makeText(ctx, getString(R.string.ui_common_copied), Toast.LENGTH_SHORT).show()
    }

    /** 导出文件头（时间 / 设备 / SDK）：三个导出口径一致，用户一眼看出是哪台机、什么时候。 */
    private fun logHead(): String = getString(
        R.string.ui_log_head,
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date()),
        android.os.Build.MODEL,
        android.os.Build.VERSION.SDK_INT.toString(),
    )

    /**
     * 后台把日志写到 Download/AllPeriph/&lt;name&gt;.txt，完成 / 失败都 Toast 结果。
     *
     * 设置页三条导出口径（「导出运行日志」行、运行日志对话框、Logcat 对话框）共用这里，
     * MediaStore 那段只此一份（无需存储权限；同名自动加后缀，不会互相覆盖）。
     */
    private fun exportLog(logName: String, text: String) {
        val ctx = activity?.applicationContext ?: return
        Toast.makeText(ctx, getString(R.string.ui_log_collecting), Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                if (text.isBlank()) {
                    ctx.getString(R.string.ui_log_empty)
                } else {
                    val fileName = "${logName}_${System.currentTimeMillis()}.txt"
                    saveToDownloads(ctx, fileName, logHead() + text.takeLast(890_000))
                    ctx.getString(R.string.ui_log_exported, fileName)
                }
            } catch (e: Throwable) {
                ctx.getString(R.string.ui_log_export_failed, e.message ?: "")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }.apply { isDaemon = true; setName("apx-log-export") }.start()
    }

    /** 正文写进 Download/AllPeriph/&lt;fileName&gt;（MediaStore，免存储权限），返回 uri。 */
    private fun saveToDownloads(
        ctx: Context,
        fileName: String,
        body: String,
    ): android.net.Uri {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/AllPeriph",
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            android.provider.MediaStore.Downloads
                .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: throw IllegalStateException("insert failed")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        ctx.contentResolver.update(
            uri,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null, null,
        )
        return uri
    }

    private fun readLogcat(): String {
        // ① root 全量（USB 模式本就需要 root，能看到全部进程与内核 tag）
        val shell = com.allperiph.gadget.RootShell.open()
        if (shell != null) {
            val r = shell.exec("logcat -d -v time -t 2000 2>/dev/null", 8_000L)
            shell.close()
            if (r.out.isNotBlank()) return r.out
        }
        // ② 降级：本进程日志（无需 READ_LOGS 权限）
        return runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid", android.os.Process.myPid().toString(), "-t", "1500"),
            )
            p.inputStream.bufferedReader().readText()
        }.getOrElse { e -> "logcat 读取失败：${e.message}" }
    }

    // ==================== File ====================

    fun renderFile() {
        if (!ensureViews()) return
        fileBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)
        fileBox.addView(settingRow(getString(R.string.ui_main_label_file_send), getString(R.string.ui_main_btn_file), accent) { pickFileToSend() })
        fileBox.addView(settingRow(
            getString(R.string.ui_main_label_file_panel), null, accent,
            hint = getString(R.string.ui_main_hint_file_panel),
        ) {
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
        if (!ensureViews()) return
        keymapBox.removeAllViews()
        // 三段长文（方向 / OK / 返回 / 主页、方向键语义、音量 / 电源与遥控预设）已搬进
        // **帮助页的「遥控键位」卡**（原文零改动，见 page_settings.xml 的 groupHelp）：
        // 真机反馈「设置分组里还有很多长文没移到帮助页」，这里只留一行跳转。
        keymapBox.addView(settingRow(
            getString(R.string.lay_settings_keymap_to_help),
            null,
            resources.getColor(R.color.md_primary),
        ) { showCategory("help") })
    }

    // ==================== Hotkey sort ====================

    fun bindHotkeySort() {
        if (!ensureViews()) return
        val h = host ?: return
        swHotkeySort.isChecked = HotkeyStore.isAutoSort(activity)
        swHotkeySort.setOnCheckedChangeListener { _, on ->
            Feedback.tap(swHotkeySort)
            HotkeyStore.setAutoSort(activity, on)
            h.renderChips()
        }
    }

    fun syncSortSwitch() {
        if (!ensureViews()) return
        val on = HotkeyStore.isAutoSort(activity)
        if (swHotkeySort.isChecked != on) swHotkeySort.isChecked = on
    }
}