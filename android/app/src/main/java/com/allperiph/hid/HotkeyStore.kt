package com.allperiph.hid

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 快捷键持久化（v1.14 快捷键页可编辑的数据层）。
 *
 * 优化 #4：usageCache 改为 ConcurrentHashMap，
 * 保证 bump()（多线程高频调用）和 loadUsage()（UI 线程）并发安全。
 */
object HotkeyStore {
    private const val PREF = "apx_hotkeys"
    private const val KEY_ITEMS = "items"
    private const val KEY_TEMPLATE = "template"

    fun loadTemplate(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TEMPLATE, null)

    fun applyTemplate(ctx: Context, t: HotkeyTemplates.Template) {
        save(ctx, t.combos)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEMPLATE, t.key).apply()
    }

    // —————————————————————————— 使用计数与自动排序（v1.29） ——————————————————————————

    private const val KEY_USAGE = "usage_count"
    private const val KEY_SORT = "auto_sort"

    /**
     * 优化 #4：ConcurrentHashMap 替代普通 MutableMap，
     * bump() 可从 HID 读线程高频调用，loadUsage() 在 UI 线程读取，
     * 两者并发时旧实现可能丢失计数或触发 ConcurrentModificationException。
     */
    private var usageCache: ConcurrentHashMap<String, Int>? = null
    private var usageCtx: Context? = null
    private val usageHandler = Handler(Looper.getMainLooper())
    private val usageFlush = Runnable { flushUsage() }

    fun isAutoSort(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_SORT, true)

    fun setAutoSort(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SORT, on).apply()
    }

    fun comboKey(c: HidKeys.Combo): String = "${c.mod}:${c.usage}"

    fun bump(ctx: Context, c: HidKeys.Combo) {
        val cache = usageCache ?: loadUsage(ctx).let { m ->
            ConcurrentHashMap<String, Int>(m).also { usageCache = it }
        }
        val k = comboKey(c)
        cache[k] = (cache[k] ?: 0) + 1
        usageCtx = ctx.applicationContext
        usageHandler.removeCallbacks(usageFlush)
        usageHandler.postDelayed(usageFlush, 1500)
    }

    fun flushUsage() {
        val ctx = usageCtx ?: return
        val cache = usageCache ?: return
        val obj = JSONObject()
        cache.forEach { (key, v) -> obj.put(key, v) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_USAGE, obj.toString()).apply()
    }

    fun loadUsage(ctx: Context): Map<String, Int> {
        usageCache?.let { return it }
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_USAGE, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = HashMap<String, Int>()
            o.keys().forEach { k -> out[k] = o.optInt(k) }
            out
        }.getOrElse { emptyMap() }
    }

    fun markCustom(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_TEMPLATE).apply()
    }

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

    fun save(ctx: Context, list: List<HidKeys.Combo>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, encode(list).toString()).apply()
    }

    // —————————————————————————— 用户自建模板（v1.28） ——————————————————————————

    private const val KEY_CUSTOM = "custom_templates"

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