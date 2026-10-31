package com.allperiph.ui

import android.content.Context
import android.util.TypedValue
import com.allperiph.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配色（v1.30）：**触控板 / 键盘 / 快捷键** 三个模块各自可选一套皮肤。
 *
 * 与 [ThemePref]（亮 / 暗）正交：ThemePref 决定"底色是浅还是深"，本类决定"强调色是什么"。
 * 不选（空 id）就是 **跟随主题** —— 直接用 `@color/md_*` 令牌，等价于改动之前的样子。
 *
 * 一套皮肤只有 4 个颜色，够表达"这个模块现在是什么色调"，也不会把界面搞花：
 * · [Skin.accent]     主色：文字、描边、高亮
 * · [Skin.accentSoft] 同色浅底（12% 透明度）
 * · [Skin.onAccent]   实底上的文字色
 * · [Skin.cursor]     触控板液滴 / 波前色（键盘与快捷键不用）
 *
 * 主题文件（导出/导入）就是这套结构 + 自定义皮肤列表的 JSON，见 [exportJson] / [importJson]。
 */
object ThemeSkin {

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

    private const val PREF = "apx_theme"
    private const val KEY_CUSTOM = "custom_skins"
    private const val KEY_BG = "bg_id"
    private const val KEY_BG_IMAGE = "bg_image"
    private const val KEY_MOTION = "motion"
    private const val KEY_HAPTIC = "haptic"
    private const val KEY_SOUND = "sound"
    private fun pickKey(scope: String) = "pick_$scope"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // —————————————————————————— 取色来源（建议 3：莫奈取色） ——————————————————————————

    private const val KEY_COLOR_SOURCE = "color_source"

    /** 品牌蓝：沿用 md_* 令牌里写死的强调色（默认，与改动前完全一致） */
    const val COLOR_BRAND = 0

    /** 系统取色：强调色跟随系统壁纸取色（Android 12+ 的莫奈），中性色仍走 md_* */
    const val COLOR_SYSTEM = 1

    val COLOR_SOURCE_LABELS = listOf("品牌蓝", "跟随系统取色")

    fun colorSource(ctx: Context): Int =
        prefs(ctx).getInt(KEY_COLOR_SOURCE, COLOR_BRAND).coerceIn(COLOR_BRAND, COLOR_SYSTEM)

    fun setColorSource(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_COLOR_SOURCE, i.coerceIn(COLOR_BRAND, COLOR_SYSTEM)).apply()
        monetCache = i.coerceIn(COLOR_BRAND, COLOR_SYSTEM) == COLOR_SYSTEM
    }

    /**
     * 当前是否「跟随系统取色」。
     *
     * 顺手把结果缓存到 [monetCache]：对话框样式要在**没有 Context 可查**的地方（
     * `AlertDialog.Builder(ctx, themeRes)` 的第二个参数）也能挑对，见 [dialogStyle]。
     * 缓存由 Activity onCreate / ThemePref.wrap 每次进入界面时刷新，不会长期失真。
     */
    fun monet(ctx: Context): Boolean {
        val on = colorSource(ctx) == COLOR_SYSTEM
        monetCache = on
        return on
    }

    @Volatile
    private var monetCache = false

    /**
     * 把当前选择叠加到 [ctx] 的主题上（只叠加，不替换）。
     *
     * 为什么必须在 Activity.onCreate 里显式调：`android:theme` 是清单里写死的，
     * Activity 初始化时用 `resources.newTheme()` **新建**一个主题再套清单样式，
     * 所以父上下文上做过的 applyStyle 一律丢 —— 只有叠到正在用的那个主题对象上才算数。
     */
    fun applyRuntimeTheme(ctx: Context) {
        if (!monet(ctx)) return
        runCatching { ctx.theme.applyStyle(R.style.APMonetOverlay, true) }
            .onFailure { android.util.Log.w("ThemeSkin", "莫奈叠加失败：${it.message}") }
    }

    /** 对话框主题：开着莫奈就用带系统取色的变体（对话框主题不继承 Activity 的叠加层） */
    fun dialogStyle(): Int = if (monetCache) R.style.Theme_AllPeriph_Miuix_Dialog_Monet
                             else R.style.Theme_AllPeriph_Miuix_Dialog

    /**
     * 读一个「强调色主题属性」，读不到就退回 [fallback] 颜色资源。
     *
     * `?attr/apPrimary` 这类属性由主题提供：默认主题指到 md_* 令牌，莫奈叠加层指到
     * system_accent1_*。Kotlin 侧原来直接 `getColor(R.color.md_primary)` 拿到的是
     * 编译期常量，换主题不会变 —— 所以三处强调色一律走这里。
     */
    fun tok(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val ok = runCatching { ctx.theme.resolveAttribute(attr, tv, true) }.getOrDefault(false)
        val isColor = tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        return if (ok && isColor) tv.data else ctx.getColor(fallback)
    }

    fun primary(ctx: Context): Int = tok(ctx, R.attr.apPrimary, R.color.md_primary)

    fun primaryPressed(ctx: Context): Int = tok(ctx, R.attr.apPrimaryPressed, R.color.md_primary_pressed)

    fun primaryContainer(ctx: Context): Int = tok(ctx, R.attr.apPrimaryContainer, R.color.md_primary_container)

    fun onPrimary(ctx: Context): Int = tok(ctx, R.attr.apOnPrimary, R.color.md_on_primary)

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
        accentSoft = softOf(accent),
        onAccent = 0xFFFFFFFF.toInt(),
        cursor = accent,
    )

    /** 同色调浅底（12% 不透明度） */
    fun softOf(color: Int): Int = (color and 0x00FFFFFF) or (0x1F shl 24)

    // —————————————————————————— 读取 ——————————————————————————

    /** 跟随主题：走 md_* 令牌 —— 开了「跟随系统取色」时这几项由主题叠加层指到系统取色 */
    fun followTheme(ctx: Context) = Skin(
        id = "",
        name = "跟随主题",
        accent = primary(ctx),
        accentSoft = primaryContainer(ctx),
        onAccent = onPrimary(ctx),
        cursor = primary(ctx),
    )

    /** 当前作用域生效的皮肤（没选 / 找不到 → 跟随主题） */
    fun current(ctx: Context, scope: String): Skin {
        val id = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(pickKey(scope), null) ?: return followTheme(ctx)
        return (PRESETS + customs(ctx)).firstOrNull { it.id == id } ?: followTheme(ctx)
    }

    /** 用户当前选中的皮肤 id（空 = 跟随主题），设置页展示用 */
    fun picked(ctx: Context, scope: String): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(pickKey(scope), null).orEmpty()

    /** 设定作用域皮肤；传空 = 跟随主题 */
    fun set(ctx: Context, scope: String, id: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            if (id.isNullOrBlank()) remove(pickKey(scope)) else putString(pickKey(scope), id)
            apply()
        }
    }

    // —————————————————————————— 自定义皮肤 ——————————————————————————

    fun customs(ctx: Context): MutableList<Skin> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { mutableListOf() }
    }

    fun saveCustoms(ctx: Context, list: List<Skin>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    // —————————————————————————— 全局外观：背景 ——————————————————————————

    /** 背景预设；[Bg.color] = 0 表示"跟随主题"（用布局自带的资源底色） */
    data class Bg(val id: String, val name: String, val color: Int)

    val BG_PRESETS: List<Bg> = listOf(
        Bg("follow", "跟随主题", 0),
        Bg("cloud", "云白", 0xFFF2F3F5.toInt()),
        Bg("warm", "暖沙", 0xFFF6F0E6.toInt()),
        Bg("mist", "雾青", 0xFFEAF1F4.toInt()),
        Bg("graphite", "石墨", 0xFF17181A.toInt()),
        Bg("deep", "深空", 0xFF0C0F14.toInt()),
    )

    fun bgId(ctx: Context): String = prefs(ctx).getString(KEY_BG, "follow").orEmpty()

    fun setBg(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_BG, id).apply()
    }

    /** 自定义背景图（相册选来的 URI）；设了图就优先于纯色底 */
    fun bgImage(ctx: Context): String? = prefs(ctx).getString(KEY_BG_IMAGE, null)

    fun setBgImage(ctx: Context, uri: String?) {
        prefs(ctx).edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_BG_IMAGE) else putString(KEY_BG_IMAGE, uri)
        }.apply()
    }

    // —————————————————————————— 全局外观：动效 ——————————————————————————

    /** 动效档位：0 关闭 / 1 更快 / 2 流畅 / 3 更慢 */
    val MOTION_LABELS = listOf("关闭", "更快", "流畅", "更慢")
    private val MOTION_SCALES = floatArrayOf(0f, 0.7f, 1f, 1.4f)

    fun motionIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MOTION, 2).coerceIn(0, MOTION_LABELS.size - 1)

    fun setMotion(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_MOTION, i).apply()
    }

    /** 动效时长倍率；**0 = 关闭**，调用方据此直接跳过动画 */
    fun motionScale(ctx: Context): Float = MOTION_SCALES[motionIndex(ctx)]

    /** 按倍率折算动画时长（关闭时返回 0） */
    fun motionMs(ctx: Context, base: Long): Long {
        val scale = motionScale(ctx)
        return if (scale <= 0f) 0L else (base * scale).toLong().coerceAtLeast(1L)
    }

    // —————————————————————————— 全局手感：震动 / 音效 ——————————————————————————

    /** 震动档位：0 关 / 1 轻 / 2 标准 / 3 强 */
    val HAPTIC_LABELS = listOf("关", "轻", "标准", "强")

    /** 音效：0 关 / 1 点击 / 2 键盘敲击 */
    val SOUND_LABELS = listOf("关", "点击", "键盘", "机械轴")

    fun hapticIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_HAPTIC, 2).coerceIn(0, 3)

    fun setHaptic(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_HAPTIC, i).apply()
    }

    fun soundIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_SOUND, 1).coerceIn(0, SOUND_LABELS.size - 1)

    fun setSound(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_SOUND, i).apply()
    }

    // —————————————————————————— 导入 / 导出 ——————————————————————————

    /** 导出成主题文件（JSON）：三个模块的选择 + 全部自定义皮肤 */
    fun exportJson(ctx: Context): String {
        val obj = JSONObject()
            .put("format", "allperiph-theme")
            .put("version", 1)
        SCOPES.forEach { (scope, _) -> obj.put(scope, picked(ctx, scope)) }
        val arr = JSONArray()
        customs(ctx).forEach { arr.put(toJson(it)) }
        obj.put("customs", arr)
        // 背景 / 动效 / 手感一并带走（背景图存的是本机 URI，换机后需重新选图）
        obj.put("bg", bgId(ctx))
            .put("bgImage", bgImage(ctx).orEmpty())
            .put("motion", motionIndex(ctx))
            .put("haptic", hapticIndex(ctx))
            .put("sound", soundIndex(ctx))
            .put("colorSource", colorSource(ctx))
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
        if (obj.has("bg")) setBg(ctx, obj.optString("bg", "follow"))
        if (obj.has("bgImage")) setBgImage(ctx, obj.optString("bgImage").ifBlank { null })
        if (obj.has("motion")) setMotion(ctx, obj.optInt("motion", 2))
        if (obj.has("haptic")) setHaptic(ctx, obj.optInt("haptic", 2))
        if (obj.has("sound")) setSound(ctx, obj.optInt("sound", 1))
        if (obj.has("colorSource")) setColorSource(ctx, obj.optInt("colorSource", COLOR_BRAND))
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
            accentSoft = o.optInt("accentSoft", softOf(accent)),
            onAccent = o.optInt("onAccent", 0xFFFFFFFF.toInt()),
            cursor = o.optInt("cursor", accent),
            custom = true,
        )
    }
}
