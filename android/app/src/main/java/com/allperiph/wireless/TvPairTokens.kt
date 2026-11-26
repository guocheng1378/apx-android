package com.allperiph.wireless

import android.content.Context

/**
 * TV 配对令牌的本地落盘（2026-11-26 TV 配对批）。
 *
 * 键 = 对端 IP，值 = 被控端经 0x36 下发的持久令牌。首连（配对码/扫码）成功后
 * 服务端回发令牌，这里存下来，之后连接免输。
 *
 * **刻意不存进 TvDiscovery 的设备记录**：设备列表每 1.5s 被信标刷新，
 * `existing.token = beaconToken` 会把落盘的令牌覆盖回信标第 4 段（TV 端恒为空串），
 * 存那里等于白存。按 IP 独立持久化是唯一可靠的位置。
 */
object TvPairTokens {

    private const val PREF = "apx_tv_pair"

    /** 保存某台 TV 的持久令牌（reader 线程调用安全，apply 异步落盘） */
    fun save(c: Context, ip: String, token: String) {
        if (ip.isEmpty() || token.isEmpty()) return
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(ip, token).apply()
    }

    /** 取某台 TV 的持久令牌；没配对过返回 null（调用方继续走信标兜底） */
    fun get(c: Context, ip: String): String? {
        if (ip.isEmpty()) return null
        return c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(ip, null)?.takeIf { it.isNotEmpty() }
    }
}
