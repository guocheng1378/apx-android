package com.allperiph.hid

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快捷键持久化（v1.14 快捷键页可编辑的数据层）。
 *
 * 存储：SharedPreferences `apx_hotkeys` 单键 `items`，JSON 数组
 * `[{"label":"复制","mod":1,"usage":6}, ...]`。
 *
 * 语义：**首次启动即写入出厂预置**（[HidKeys.DEFAULTS]）；一旦用户改过，
 * 空列表也是合法状态（用户主动清空），不再回填默认值——故以 key 是否存在
 * 判断"是否已初始化"，而不是"列表是否为空"。
 */
object HotkeyStore {
    private const val PREF = "apx_hotkeys"
    private const val KEY_ITEMS = "items"
    private const val KEY_TEMPLATE = "template"

    /**
     * 当前生效的模板 key（[HotkeyTemplates.Template.key]）。
     * null / 空表示"自定义"——用户逐条改过，或从未套用模板。
     */
    fun loadTemplate(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TEMPLATE, null)

    /**
     * 套用场景模板：整组替换快捷键并记住模板来源。
     * 只做落盘，UI 刷新由调用方负责。
     */
    fun applyTemplate(ctx: Context, t: HotkeyTemplates.Template) {
        save(ctx, t.combos)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEMPLATE, t.key).apply()
    }

    // —————————————————————————— 使用计数与自动排序（v1.29） ——————————————————————————

    private const val KEY_USAGE = "usage_count"
    private const val KEY_SORT = "auto_sort"

    /** 内存计数缓存（延迟落盘，见 [bump]）；读取优先走它，保证不丢未落盘的增量 */
    private var usageCache: MutableMap<String, Int>? = null
    private var usageCtx: Context? = null
    private val usageHandler = Handler(Looper.getMainLooper())
    private val usageFlush = Runnable { flushUsage() }

    /** 自动排序开关（默认开）：最常用的排到快捷键条最前面 */
    fun isAutoSort(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_SORT, true)

    fun setAutoSort(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SORT, on).apply()
    }

    /** 组合键身份 key：用「修饰位 + usage」而非标签 —— 改名不会丢掉历史计数 */
    fun comboKey(c: HidKeys.Combo): String = "${c.mod}:${c.usage}"

    /** 记一次使用（chip 每次发送 +1），供自动排序用 */
    fun bump(ctx: Context, c: HidKeys.Combo) {
        val cache = usageCache ?: loadUsage(ctx).toMutableMap().also { usageCache = it }
        val k = comboKey(c)
        cache[k] = (cache[k] ?: 0) + 1
        usageCtx = ctx.applicationContext
        // v1.31：连点几十次就是几十次整表序列化 —— 改成内存累积 + 1.5s 合并落盘
        usageHandler.removeCallbacks(usageFlush)
        usageHandler.postDelayed(usageFlush, 1500)
    }

    /** 立即把内存里的计数写回盘（进程退出/读取前兜底） */
    fun flushUsage() {
        val ctx = usageCtx ?: return
        val cache = usageCache ?: return
        val obj = JSONObject()
        cache.forEach { (key, v) -> obj.put(key, v) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_USAGE, obj.toString()).apply()
    }

    /** 使用次数表（键见 [comboKey]） */
    fun loadUsage(ctx: Context): Map<String, Int> {
        usageCache?.let { return it } // 内存里已有（含尚未落盘的增量）
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_USAGE, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = HashMap<String, Int>()
            o.keys().forEach { k -> out[k] = o.optInt(k) }
            out
        }.getOrElse { emptyMap() }
    }

    /** 逐条编辑后调用：标记为自定义（模板来源失效） */
    fun markCustom(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_TEMPLATE).apply()
    }

    /** 读取用户快捷键；未初始化过则返回出厂预置（不落盘，交由首次编辑时保存） */
    fun load(ctx: Context): MutableList<HidKeys.Combo> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ITEMS, null) ?: return HidKeys.DEFAULTS.toMutableList()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HidKeys.Combo(o.optString("label"), o.optInt("mod"), o.optInt("usage"))
            }
        }.getOrElse { HidKeys.DEFAULTS.toMutableList() }
    }

    /** 覆盖写入（新增 / 编辑 / 删除 / 恢复默认都走这里） */
    fun save(ctx: Context, list: List<HidKeys.Combo>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, encode(list).toString()).apply()
    }

    // —————————————————————————— 用户自建模板（v1.28） ——————————————————————————

    private const val KEY_CUSTOM = "custom_templates"

    /**
     * 读取用户自建模板（设置页「把当前快捷键条存为模板」）。
     * 与预置模板同构，只是在列表里另存一份、key 带 `custom:` 前缀。
     */
    fun loadCustom(ctx: Context): MutableList<HotkeyTemplates.Template> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HotkeyTemplates.Template(
                    key = o.optString("key"),
                    name = o.optString("name"),
                    desc = o.optString("desc"),
                    color = o.optInt("color", 0xFF3482FF.toInt()),
                    combos = decode(o.optJSONArray("items")),
                )
            }
        }.getOrElse { mutableListOf() }
    }

    /** 覆盖写入自建模板 */
    fun saveCustom(ctx: Context, list: List<HotkeyTemplates.Template>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("key", t.key)
                    .put("name", t.name)
                    .put("desc", t.desc)
                    .put("color", t.color)
                    .put("items", encode(t.combos))
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    /** 组合键列表 → JSON 数组 */
    private fun encode(list: List<HidKeys.Combo>): JSONArray {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("label", c.label)
                    .put("mod", c.mod)
                    .put("usage", c.usage)
            )
        }
        return arr
    }

    private fun decode(arr: JSONArray?): MutableList<HidKeys.Combo> {
        if (arr == null) return mutableListOf()
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HidKeys.Combo(o.optString("label"), o.optInt("mod"), o.optInt("usage"))
        }
    }

}
