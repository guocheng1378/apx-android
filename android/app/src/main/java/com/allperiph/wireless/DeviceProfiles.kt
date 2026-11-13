package com.allperiph.wireless

import android.content.Context
import org.json.JSONObject

/**
 * 设备档案（2026-11-14 成宇「能互相发现设备，对应切换键盘属性、快捷键、手柄、映射等」）：
 * 按**受控设备**分别记住各自的偏好，连上谁就用谁的档案，切换目标自动跟随 —— 不再是
 * 一份全局设置打天下（家里同时有 Windows 台式机 + Mac + 电视时，被控机系统每换一次
 * 目标就得手动改一次）。
 *
 * 键 = 受控设备展示名（[ControlTarget.label]，信标里广播的机型名；手填 IP 连接时即 IP）。
 * 同名机型共用一份档案（家用场景可接受；等后续引入更稳定的设备指纹再换键）。
 * 「本机 / PC」（[ControlTarget.DEFAULT_LABEL]）不是受控设备，**不算档案** ——
 * 此时各设置退回全局默认键，存量行为不变。
 *
 * 存储为每设备一条 JSON，字段随批次逐步扩：
 *  - 本期（第一批）：`os`（被控机系统，[com.allperiph.hid.PeerOs] 的下标）——
 *    快捷键适配 / Mac 徽标 / 手柄 Mac 分支全走 [com.allperiph.hid.PeerOs.current]，一处档案全局生效；
 *  - 后续批次：快捷键自定义存档（HotkeyStore）、键位映射、手柄布局（现为屏幕形态分档，见 3038a74）、键盘属性等。
 */
object DeviceProfiles {

    private const val PREF = "apx_device_profiles"

    /** 档案字段名前缀统一走这里，避免散落字面量 */
    const val FIELD_OS = "os"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 档案是单条 JSON 的读-改-写：并发写会整段覆盖丢字段，写侧统一串行化（审查 2026-11-14） */
    private val writeLock = Any()

    /** 展示名 → 存储键：过滤掉路径/空白类字符，只留字母数字与连字符（中文属 Letter，会原样保留） */
    private fun key(label: String): String {
        val clean = label.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        // 全符号/全 emoji/纯空白的名字过滤后为空，全部挤在 "prof_" 一个键里会互相串档，
        // 退回哈希兜底键：removePrefix("prof_") 后剩 "h"+十六进制，scopedPrefsName 照常拼文件名
        return if (clean.isNotEmpty()) "prof_$clean"
        else "prof_h" + Integer.toHexString(label.hashCode())
    }

    private fun load(ctx: Context, label: String): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(key(label), "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun save(ctx: Context, label: String, o: JSONObject) {
        prefs(ctx).edit().putString(key(label), o.toString()).apply()
    }

    /**
     * 设备作用域偏好文件名：连着受控设备时返回带设备后缀的**独立文件**，没连返回原文件名。
     *
     * 这是「整套设置按设备切换」的通用通道（2026-11-14 成宇「对应切换」批二期）：
     * 快捷键存档（HotkeyStore）、键盘属性（KeyPref）、手柄键位配置（GamepadPadConfig）
     * 都是 SharedPreferences 型的偏好 —— 把各自的 prefs() 经这里路由，连上谁
     * `getSharedPreferences` 就落在谁自己的文件里，读端自然换档，调用点零改动。
     * 没连设备 = 原文件（存量行为不变）；同名机型共用档案（与 [activePeer] 同一口径）。
     *
     * 生效时机：读端是组合期/调用期直读（如 KeyPref.density），`ControlTarget.label`
     * 不是 observable 状态，换连设备后**回到页面（导航重建）才换档** —— 连接成功
     * 都发生在连接流程页里，返回即重建，符合「连上谁用谁的」直觉，无需状态订阅。
     */
    fun scopedPrefsName(ctx: Context, base: String): String {
        val peer = activePeer() ?: return base
        return "${base}_${key(peer).removePrefix("prof_")}"
    }

    /** 当前已连入的受控设备名；没连设备返回 null（档案语义只在「连着谁」时有意义） */
    fun activePeer(): String? =
        ControlTarget.label.takeIf { it.isNotBlank() && it != ControlTarget.DEFAULT_LABEL }

    /**
     * 读当前受控设备档案里的整数字段；没连设备 / 没写过该字段返回 null，
     * 调用方退回全局默认 —— 档案是**叠加层**，不是替换层。
     */
    fun readInt(ctx: Context, field: String): Int? =
        activePeer()?.let { peer ->
            load(ctx, peer).optInt(field, -1).takeIf { v -> v >= 0 }
        }

    /** 写当前受控设备的档案字段；没连设备时静默忽略（全局默认键仍是那时的唯一写点） */
    fun writeInt(ctx: Context, field: String, value: Int) {
        val peer = activePeer() ?: return
        synchronized(writeLock) {
            val o = load(ctx, peer)
            o.put(field, value)
            save(ctx, peer, o)
        }
    }
}
