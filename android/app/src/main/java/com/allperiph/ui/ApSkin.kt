package com.allperiph.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块皮肤（v1.30）：**触控板 / 键盘 / 快捷键** 三个模块各自可选一套强调色。
 *
 * 与 [ThemePref]（亮 / 暗）正交：ThemePref 决定「底色是浅还是深」，本对象决定「强调色是什么」。
 * 不选（空 id）就是 **跟随主题** —— 直接用 `md_*` 令牌 + [ApPalette] 的属性解析，等价于没有皮肤。
 *
 * 一套皮肤只有 4 个颜色，够表达「这个模块现在是什么色调」，也不会把界面搞花：
 * · [Skin.accent]     主色：文字、描边、高亮
 * · [Skin.accentSoft] 同色浅底（12% 透明度）
 * · [Skin.onAccent]   实底上的文字色
 * · [Skin.cursor]     触控板液滴 / 波前色（键盘与快捷键不用）
 *
 * 职责边界（2026-11-02 从 `ThemeSkin` 拆出）：这里只**管皮肤本身**（选择 / 自定义 / 导入导出）。
 * 强调色怎么算 → [ApPalette]；背景 → [ApBackdrop]；动效 → [ApTiming]；震动音效 → [ApFeel]。
 * 早先 380 行的 `ThemeSkin` 同时干这五件事，改一处配色要在 18 个文件里找引用。
 */
object ApSkin {

    // —————————————————————————— 作用域 ——————————————————————————

    const val TOUCHPAD = "touchpad"
    const val KEYBOARD = "keyboard"
    const val HOTKEY = "hotkey"

    /** 设置页三行的顺序与标题 */
    val SCOPES: List<Pair<String, String>> = listOf(
        TOUCHPAD to "触控板",
        KEYBOARD to "键盘",
        HOTKEY to "快捷键",
    )

    data class Skin(
        val id: String,
        val name: String,
        val accent: Int,
        val accentSoft: Int,
        val onAccent: Int,
        val cursor: Int,
        val custom: Boolean = false,
    )

    /** 预设 8 套（与快捷键模板同一批色系，保证整机观感一致） */
    val PRESETS: List<Skin> = listOf(
        preset("blue", "海蓝", 0xFF3482FF.toInt()),
        preset("cyan", "青碧", 0xFF12B0C8.toInt()),
        preset("violet", "暮紫", 0xFF7C5CFF.toInt()),
        preset("green", "松绿", 0xFF00A870.toInt()),
        preset("amber", "琥珀", 0xFFFF7A00.toInt()),
        preset("rose", "玫红", 0xFFE0457B.toInt()),
        preset("indigo", "靛蓝", 0xFF3B6FF5.toInt()),
        preset("graphite", "石墨", 0xFF5B6470.toInt()),
    )

    private fun preset(id: String, name: String, accent: Int) = Skin(
        id = id,
        name = name,
        accent = accent,
        accentSoft = ApPalette.softOf(accent),
        onAccent = 0xFFFFFFFF.toInt(),
        cursor = accent,
    )

    // —————————————————————————— 读取 ——————————————————————————

    /** 跟随主题：走 `md_*` 令牌 —— 开了「跟随系统取色」时这几项由主题叠加层指到系统取色 */
    fun followTheme(ctx: Context) = Skin(
        id = "",
        name = "跟随主题",
        accent = ApPalette.primary(ctx),
        accentSoft = ApPalette.primaryContainer(ctx),
        onAccent = ApPalette.onPrimary(ctx),
        cursor = ApPalette.primary(ctx),
    )

    /** 当前作用域生效的皮肤（没选 / 找不到 → 跟随主题） */
    fun current(ctx: Context, scope: String): Skin {
        val id = ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.pickKey(scope), null)
            ?: return followTheme(ctx)
        return (PRESETS + customs(ctx)).firstOrNull { it.id == id } ?: followTheme(ctx)
    }

    /** 用户当前选中的皮肤 id（空 = 跟随主题），设置页展示用 */
    fun picked(ctx: Context, scope: String): String =
        ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.pickKey(scope), null).orEmpty()

    /** 设定作用域皮肤；传空 = 跟随主题 */
    fun set(ctx: Context, scope: String, id: String?) {
        ApAppearancePrefs.of(ctx).edit().apply {
            if (id.isNullOrBlank()) remove(ApAppearancePrefs.pickKey(scope))
            else putString(ApAppearancePrefs.pickKey(scope), id)
            apply()
        }
    }

    // —————————————————————————— 自定义皮肤 ——————————————————————————

    fun customs(ctx: Context): MutableList<Skin> {
        val raw = ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.KEY_CUSTOM, null)
            ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { mutableListOf() }
    }

    fun saveCustoms(ctx: Context, list: List<Skin>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        ApAppearancePrefs.of(ctx).edit().putString(ApAppearancePrefs.KEY_CUSTOM, arr.toString()).apply()
    }

    // —————————————————————————— 导入 / 导出 ——————————————————————————

    /** 导出成主题文件（JSON）：三个模块的选择 + 全部自定义皮肤 + 背景 / 动效 / 手感 */
    fun exportJson(ctx: Context): String {
        val obj = JSONObject()
            .put("format", "allperiph-theme")
            .put("version", 1)
        SCOPES.forEach { (scope, _) -> obj.put(scope, picked(ctx, scope)) }
        val arr = JSONArray()
        customs(ctx).forEach { arr.put(toJson(it)) }
        obj.put("customs", arr)
        // 背景 / 动效 / 手感一并带走（背景图存的是本机 URI，换机后需重新选图）
        obj.put("bg", ApBackdrop.bgId(ctx))
            .put("bgImage", ApBackdrop.bgImage(ctx).orEmpty())
            .put("motion", ApTiming.motionIndex(ctx))
            .put("haptic", ApFeel.hapticIndex(ctx))
            .put("sound", ApFeel.soundIndex(ctx))
            .put("colorSource", ApPalette.colorSource(ctx))
        return obj.toString(2)
    }

    /**
     * 导入主题文件：合并自定义皮肤（同名 id 覆盖）并套用三个模块的选择。
     * @return 导入的皮肤条数；**-1 = 不是主题文件**
     */
    fun importJson(ctx: Context, text: String): Int {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return -1
        if (obj.optString("format") != "allperiph-theme") return -1
        val incoming = mutableListOf<Skin>()
        obj.optJSONArray("customs")?.let { arr ->
            for (i in 0 until arr.length()) incoming += fromJson(arr.getJSONObject(i))
        }
        if (incoming.isNotEmpty()) {
            val merged = customs(ctx)
            incoming.forEach { skin ->
                val at = merged.indexOfFirst { it.id == skin.id }
                if (at >= 0) merged[at] = skin else merged += skin
            }
            saveCustoms(ctx, merged)
        }
        // 选择也一并带过来（空串 = 跟随主题，属于合法值）
        SCOPES.forEach { (scope, _) ->
            if (obj.has(scope)) set(ctx, scope, obj.optString(scope).ifBlank { null })
        }
        // 背景 / 动效 / 手感
        if (obj.has("bg")) ApBackdrop.setBg(ctx, obj.optString("bg", "follow"))
        if (obj.has("bgImage")) ApBackdrop.setBgImage(ctx, obj.optString("bgImage").ifBlank { null })
        if (obj.has("motion")) ApTiming.setMotion(ctx, obj.optInt("motion", 2))
        if (obj.has("haptic")) ApFeel.setHaptic(ctx, obj.optInt("haptic", 2))
        if (obj.has("sound")) ApFeel.setSound(ctx, obj.optInt("sound", 1))
        if (obj.has("colorSource")) ApPalette.setColorSource(ctx, obj.optInt("colorSource", ApPalette.COLOR_BRAND))
        return incoming.size
    }

    private fun toJson(s: Skin) = JSONObject()
        .put("id", s.id)
        .put("name", s.name)
        .put("accent", s.accent)
        .put("accentSoft", s.accentSoft)
        .put("onAccent", s.onAccent)
        .put("cursor", s.cursor)

    private fun fromJson(o: JSONObject): Skin {
        val accent = o.optInt("accent", 0xFF3482FF.toInt())
        return Skin(
            id = o.optString("id", "custom-" + System.currentTimeMillis()),
            name = o.optString("name", "自定义"),
            accent = accent,
            accentSoft = o.optInt("accentSoft", ApPalette.softOf(accent)),
            onAccent = o.optInt("onAccent", 0xFFFFFFFF.toInt()),
            cursor = o.optInt("cursor", accent),
            custom = true,
        )
    }
}
