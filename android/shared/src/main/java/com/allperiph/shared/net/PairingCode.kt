package com.allperiph.shared.net

import java.security.SecureRandom

/**
 * 一次性配对码（2026-11-23，随「扫码/链接交付令牌」批加，方案见 docs/REQ-无线安全配对.md §5）。
 *
 * 解决的问题：配对令牌此前靠信标**明文广播**（第 4 段），局域网里任何设备都能捡到
 * 并完成握手 —— 令牌形同虚设。现在信标可以隐藏令牌段（见 ControlledService 的
 * 「隐藏令牌广播」开关），新设备改由用户当面交付凭据：被控端配对码页生成 6 位数字码，
 * 控制端手输这 6 位完成首次握手，随后被控端经 0x36 把**持久令牌**发给对端落盘，
 * 之后对端与信标路径一样凭持久令牌握手，配对码即作废。
 *
 * 生命周期：
 * - 页面点「生成」→ [newCode]：6 位数字（SecureRandom），10 分钟有效；
 * - 握手校验 [validate]：匹配当前未用、未过期的码才放行（**不在此处作废** ——
 *   会话激活成功后由 ControlServer 调 [consume]，避免握手后接管被并发裁决拒绝
 *   时白烧一个码）；
 * - 防爆破：连续错 [MAX_FAILS] 次立即作废（局域网攻击者 10 分钟窗口内高频试
 *   6 位数字码是现实威胁，5 次封顶让爆破不可行，用户重新生成即可）。
 *
 * 进程内存态，不落盘：页面重开重新生成，重启后旧码自然失效 —— 配对码本来就是
 * 「当面交付」的短命凭据，持久化反而拉长暴露窗口。
 */
object PairingCode {
    private const val TTL_MS = 10 * 60_000L
    private const val MAX_FAILS = 5

    private val lock = Any()
    private var code: String? = null
    private var createdAtMs = 0L
    private var used = false
    private var fails = 0

    /** 生成新码（页面每次打开/点重新生成时调用）：旧码立即作废，失败计数清零 */
    fun newCode(): String = synchronized(lock) {
        val c = String.format("%06d", SecureRandom().nextInt(1_000_000))
        code = c
        createdAtMs = System.currentTimeMillis()
        used = false
        fails = 0
        c
    }

    /** 当前有效码（未用、未过期），没有则 null —— 配对码页展示用 */
    fun current(): String? = synchronized(lock) {
        val c = code ?: return null
        if (used || System.currentTimeMillis() - createdAtMs > TTL_MS) null else c
    }

    /** 剩余有效秒数（页面倒计时用）；无有效码返回 0 */
    fun remainingSeconds(): Int = synchronized(lock) {
        val c = code ?: return 0
        if (used) return 0
        val left = TTL_MS - (System.currentTimeMillis() - createdAtMs)
        if (left <= 0) 0 else (left / 1000L).toInt()
    }

    /**
     * 握手校验：对端出示的凭据匹配当前有效配对码。只读不改状态 ——
     * 作废时机在会话激活成功后（ControlServer），见类注释。
     */
    fun validate(presented: String): Boolean = synchronized(lock) {
        val c = code ?: return false
        if (used || System.currentTimeMillis() - createdAtMs > TTL_MS) return false
        if (presented != c) { fails++; if (fails >= MAX_FAILS) used = true; return false }
        true
    }

    /** 配对码已用掉（首连会话激活成功、持久令牌已发出）：立即作废 */
    fun consume() = synchronized(lock) { used = true }
}
