package com.allperiph.core

import android.content.Context
import org.json.JSONObject

/**
 * 媒体档位存取（issue#171 #2，0x4E 媒体档位配置）：PC 面板改投屏/摄像头的
 * 分辨率与画质档位后，手机端落盘于此，推流服务按档位采集。
 *
 * 档位语义（与 PC 端面板一致）：
 * - w：目标短边（像素，720p/1080p 的 720/1080）。0 = 原生不缩放（投屏按屏幕原样；
 *   摄像头用设备支持的最高）。旧版误按「宽」解释，切档反而更糊（issue#171 实锤）。
 * - q：JPEG 画质（1..100）。
 * 默认值沿用旧版硬编码：投屏 720/q65，摄像头 1280/q80 —— 未收到 0x4E 时行为不变。
 */
object MediaConfigPref {
    private const val FILE = "apx_media_config"
    private const val KEY_W = "media_%s_w"
    private const val KEY_Q = "media_%s_q"
    private const val KEY_FACING = "media_%s_facing"

    /** 合法档位 id：投屏（stream 7）与摄像头（stream 6）。 */
    val IDS = listOf("screenmirror", "camera")

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 读某路媒体的档位；缺省回旧版硬编码值。 */
    fun get(c: Context, id: String): Pair<Int, Int> {
        val d = defaults(id)
        val w = prefs(c).getInt(KEY_W.format(id), d.first)
        val q = prefs(c).getInt(KEY_Q.format(id), d.second)
        return w to q
    }

    /**
     * 读某路媒体的前后摄像头选择（issue#171 反馈补前后切换）：0=后置 1=前置。
     * 仅摄像头档位有意义；缺省 0（后置，历史行为）。
     */
    fun getFacing(c: Context, id: String): Int =
        prefs(c).getInt(KEY_FACING.format(id), 0)

    /**
     * 解析并落盘 0x4E 载荷。id 不认识或 json 非法时返回 false（不落盘、不发事件）；
     * w/q 越界值夹回合法区间（w>=0，q 1..100），避免 PC 端异常载荷把推流打挂。
     * facing 可选（0=后置 1=前置）：载荷里没有就不动已存值，只改 w/q。
     */
    fun apply(c: Context, id: String, json: String): Boolean {
        if (id !in IDS) return false
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val w = runCatching { obj.optInt("w", -1) }.getOrNull() ?: return false
        val q = runCatching { obj.optInt("q", -1) }.getOrNull() ?: return false
        if (w < 0 || q !in 1..100) return false
        val facing = obj.optInt("facing", -1)
        if (facing !in 0..1 && facing != -1) return false
        prefs(c).edit()
            .putInt(KEY_W.format(id), w)
            .putInt(KEY_Q.format(id), q.coerceIn(1, 100))
            // facing 只在载荷带了合法值时覆盖（-1 = 本次不改前后）
            .apply { if (facing in 0..1) putInt(KEY_FACING.format(id), facing) }
            .apply()
        return true
    }

    /** 旧版硬编码默认档位（未配置时的回退）。 */
    private fun defaults(id: String): Pair<Int, Int> = when (id) {
        "screenmirror" -> 720 to 65
        else -> 1280 to 80
    }
}
