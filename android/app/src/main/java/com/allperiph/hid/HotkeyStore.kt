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

    /**
     * 快捷键存档读统一走**设备作用域**文件（2026-11-14「对应切换」批二期，[DeviceProfiles.scopedPrefsName]）：
     * 连着受控设备时落在该设备自己的文件里 —— 每台设备各存一套快捷键，连上谁用谁的；
     * 没连设备 = 原文件（存量行为不变）。⚠️ 使用计数（KEY_USAGE）故意不走这里：
     * 那是跨设备的使用习惯统计，全局一份才符合「高频动作自动排前」的本意。
     */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(
        com.allperiph.wireless.DeviceProfiles.scopedPrefsName(ctx, PREF),
        Context.MODE_PRIVATE,
    )

    /** 使用计数专用：全局文件，不随设备分档（见 [prefs] 注释） */
    private fun usagePrefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadTemplate(ctx: Context): String? =
        prefs(ctx).getString(KEY_TEMPLATE, null)

    fun applyTemplate(ctx: Context, t: HotkeyTemplates.Template) {
        // issue#168 第 14 条：落库前按当前被控机系统翻译——存档里存的是**本机认的组合**，
        // 换系统后改选「被控机系统」再重套模板即可（存量存档不动，只影响新套的模板）。
        // 被控机系统自第一批起按设备档案读写（PeerOs.current/setCurrent），这里翻译
        // 与落盘自然都落在当前设备的作用域里，模板也各存各的。
        save(ctx, PeerOs.current(ctx).adapt(t.combos))
        prefs(ctx).edit().putString(KEY_TEMPLATE, t.key).apply()
    }

    // —————————————————————————— 使用计数与自动排序（v1.29） ——————————————————————————

    private const val KEY_USAGE = "usage_count"
    private const val KEY_SORT = "auto_sort"

    /**
     * 优化 #4：ConcurrentHashMap 替代普通 MutableMap，
     * bump() 可从 HID 读线程高频调用，loadUsage() 在 UI 线程读取，
     * 两者并发时旧实现可能丢失计数或触发 ConcurrentModificationException。
     *
     * v1.7 修复：usageCache/usageCtx 加 @Volatile 保证多线程可见；
     * 初始化用 double-check locking 避免多线程同时 loadUsage 导致覆盖丢失。
     */
    @Volatile
    private var usageCache: ConcurrentHashMap<String, Int>? = null
    @Volatile
    private var usageCtx: Context? = null
    private val usageHandler = Handler(Looper.getMainLooper())
    private val usageFlush = Runnable { flushUsage() }
    private val usageLock = Any()

    fun isAutoSort(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SORT, true)

    fun setAutoSort(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SORT, on).apply()
    }

    fun comboKey(c: HidKeys.Combo): String = "${c.mod}:${c.usage}"

    fun bump(ctx: Context, c: HidKeys.Combo) {
        // v1.7：double-check locking 保证只有一个线程执行初始化，
        // 避免多线程同时看到 usageCache==null → 各自 loadUsage → 后赋值覆盖前一个的修改。
        val cache = usageCache ?: synchronized(usageLock) {
            usageCache ?: ConcurrentHashMap<String, Int>(loadUsage(ctx)).also { usageCache = it }
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
        usagePrefs(ctx)
            .edit().putString(KEY_USAGE, obj.toString()).apply()
    }

    fun loadUsage(ctx: Context): Map<String, Int> {
        usageCache?.let { return it }
        val raw = usagePrefs(ctx)
            .getString(KEY_USAGE, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = HashMap<String, Int>()
            o.keys().forEach { k -> out[k] = o.optInt(k) }
            out
        }.getOrElse { emptyMap() }
    }

    fun markCustom(ctx: Context) {
        prefs(ctx)
            .edit().remove(KEY_TEMPLATE).apply()
    }

    /**
     * 套用 [HidKeys.USAGE_REPAIRS]：把存量坏 usage（多字符标签被 usageOf 落到首字母的那批，
     * 全屏→f、音量→v…）就地修成正确值。幂等：不匹配 (label, 坏 usage) 的条目原样保留。
     */
    private fun repair(list: MutableList<HidKeys.Combo>): MutableList<HidKeys.Combo> {
        for (i in list.indices) {
            val c = list[i]
            val r = HidKeys.USAGE_REPAIRS[c.label]
            if (r != null && c.usage == r.first) list[i] = c.copy(usage = r.second)
        }
        return list
    }

    fun load(ctx: Context): MutableList<HidKeys.Combo> {
        val sp = prefs(ctx)
        // issue#168 第 14 条：无存档回退走 PeerOs——被控机选了 macOS 时出厂行也是 ⌘ 系（Windows 基线不变）
        val raw = sp.getString(KEY_ITEMS, null) ?: return PeerOs.defaults(ctx).toMutableList()
        return repair(
            runCatching {
                val arr = JSONArray(raw)
                MutableList(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    HidKeys.Combo(o.optString("label"), o.optInt("mod"), o.optInt("usage"))
                }
            }.getOrElse { PeerOs.defaults(ctx).toMutableList() },
        )
    }

    fun save(ctx: Context, list: List<HidKeys.Combo>) {
        prefs(ctx)
            .edit().putString(KEY_ITEMS, encode(list).toString()).apply()
    }

    // —————————————————————————— TV 目标专属快捷键（v1.32） ——————————————————————————
    // 主页触摸板切到 TV/PC 目标时，快捷键条自动显示这套，编辑单独落盘，不污染本机那套。

    private const val KEY_ITEMS_TV = "items_tv"

    private fun tvDefault(): List<HidKeys.Combo> =
        HotkeyTemplates.byKey("tv")?.combos ?: HidKeys.DEFAULTS

    fun loadTv(ctx: Context): MutableList<HidKeys.Combo> {
        val sp = prefs(ctx)
        val raw = sp.getString(KEY_ITEMS_TV, null) ?: return tvDefault().toMutableList()
        return repair(
            runCatching {
                val arr = JSONArray(raw)
                MutableList(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    HidKeys.Combo(o.optString("label"), o.optInt("mod"), o.optInt("usage"))
                }
            }.getOrElse { tvDefault().toMutableList() },
        )
    }

    fun saveTv(ctx: Context, list: List<HidKeys.Combo>) {
        prefs(ctx)
            .edit().putString(KEY_ITEMS_TV, encode(list).toString()).apply()
    }

    // —————————————————————————— 用户自建模板（v1.28） ——————————————————————————

    private const val KEY_CUSTOM = "custom_templates"

    /**
     * 自建模板**故意全局**（不走 [prefs] 的设备作用域）：模板是用户攒的可复用素材，
     * 换台设备就看不见自己的模板会让人以为全丢了。分设备的是「当前套用的那份组合」
     * （items/template），不是模板库本身。
     */
    private fun customPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadCustom(ctx: Context): MutableList<HotkeyTemplates.Template> {
        val raw = customPrefs(ctx)
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
        customPrefs(ctx)
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