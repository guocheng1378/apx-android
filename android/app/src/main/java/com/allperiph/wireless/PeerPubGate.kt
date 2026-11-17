package com.allperiph.wireless

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.allperiph.controlled.PubConfirmDialogActivity
import com.allperiph.shared.net.NoiseSession
import com.allperiph.shared.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R2 对端静态公钥白名单裁决（docs/REQ-无线安全配对.md §4.4，2026-11-17）。
 *
 * NoiseSession.pubAuthorizer 的 app 模块实现（App.onCreate 装配，进程级生效 ——
 * 9511 控制面 / 9512 文件 / 9502 媒体三条通道的 Responder 与 9511 Initiator 共用）。
 * 契约见 [NoiseSession.pubAuthorizer]：
 *  · 白名单命中 → 直接放行（幂等，不重复落盘）；
 *  · 未收录（首连 / 陌生设备同一口径）→ 弹 [PubConfirmDialogActivity] 要用户裁决，
 *    点「允许」才信任并落盘白名单（[NoiseSession.trustPeerPub]），点「拒绝」断开；
 *  · 拒绝后 60s 内同一公钥静默拒绝（自动重连风暴不再刷弹窗），15s 未裁决按拒绝处理。
 *
 * 阻塞调用（内含弹窗等待）—— 调用点都在握手/读线程，绝不能是主线程。
 */
object PeerPubGate {
    private const val TAG = "PeerPubGate"

    /** 弹窗裁决超时：与 v223.33 接管确认同款 15s 兜底，超时按拒绝（保住安全默认） */
    private const val DECIDE_TIMEOUT_MS = 15_000L
    /** 拒绝记忆窗：同公钥被拒后该时长内不再弹窗，直接拒绝 */
    private const val DENY_MEMORY_MS = 60_000L

    /** 待用户裁决的弹窗回调（PubConfirmDialogActivity 消费；引用判重防超时/点击竞态） */
    @Volatile var pendingDecide: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decideLock = Any()
    /** 被拒过的公钥（Base64）→ 静默拒绝截止时刻（elapsedRealtime） */
    private val deniedUntil = HashMap<String, Long>()

    /**
     * 裁决入口（[NoiseSession.pubAuthorizer] 契约）。pub=对端静态公钥（32 字节），
     * host=对端 IP（弹窗展示用，可 null）。放行时负责信任落盘（含按 host 记录，
     * 9512 发起端据此按对端取公钥）。
     */
    fun authorize(pub: ByteArray, host: String?): Boolean {
        val ctx = com.allperiph.App.context
        if (ctx == null) {
            // 上下文未就绪（极少见的早期路径）：回退 TOFU 旧行为并留痕，避免把合法
            // 首连锁死在「无 UI 可弹」的状态；攻击面与升级前一致，不因装配时序变差。
            Log.w(TAG, "App 上下文未就绪，公钥裁决回退 TOFU（host=$host）")
            return true
        }
        val b64 = Base64.encodeToString(pub, Base64.NO_WRAP)
        if (NoiseSession.trustedPeerPubs(ctx).contains(b64)) return true
        val ok = synchronized(decideLock) {
            val now = SystemClock.elapsedRealtime()
            synchronized(deniedUntil) {
                val until = deniedUntil[b64]
                if (until != null && now < until) {
                    Log.i(TAG, "同设备刚被拒绝过，静默拒绝（host=$host）")
                    return@synchronized false
                }
            }
            // v-fix（2026-11-18，并发裁决竞态）：手机互控时两端会各起一路握手，同一条
            // 白名单可能同时被两路查——第二路在第一路「等用户点允许」期间已越过了上面
            // 的白名单检查（那时还没落盘），等它拿到锁时第一路刚点完允许、信任已入白名单。
            // 若这里不复查，会再弹第二个确认窗：用户已在第一窗点过「允许」不会理会，
            // 15s 超时按拒绝 → 第二路把刚握上手的连接断开（真机日志：允许并信任→拒绝→断开）。
            // 故持锁后复查一次白名单，已收录即按放行收口，与第一路同判。
            if (NoiseSession.trustedPeerPubs(ctx).contains(b64)) return@synchronized true
            // 同一时刻只裁决一个：并发接入（第二路握手）直接拒绝，等下一轮重试
            if (pendingDecide != null) {
                Log.i(TAG, "已有确认弹窗在等用户裁决，本次直接拒绝（host=$host）")
                return@synchronized false
            }
            confirm(ctx, pub, host)
        }
        Log.i(TAG, "对端公钥裁决：${if (ok) "允许并信任" else "拒绝"}（host=$host）")
        return ok
    }

    /** 弹确认窗并阻塞等结果（须持 [decideLock]）。超时 / 拉起失败均按拒绝。 */
    private fun confirm(ctx: Context, pub: ByteArray, host: String?): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        val decide = { ok: Boolean -> result = ok; latch.countDown() }
        pendingDecide = decide
        val b64 = Base64.encodeToString(pub, Base64.NO_WRAP)
        val timeout = Runnable {
            // 15s 兜底：弹窗没拉起来 / 用户没操作 → 按拒绝收口（与接管确认同思路）
            if (pendingDecide === decide) {
                pendingDecide = null
                latch.countDown()
            }
        }
        mainHandler.post {
            runCatching {
                val i = Intent(ctx, PubConfirmDialogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PubConfirmDialogActivity.EXTRA_PEER, host ?: "未知设备")
                }
                ctx.startActivity(i)
            }.onFailure { t ->
                Log.w(TAG, "确认弹窗启动失败（${t.message}），按拒绝处理")
                if (pendingDecide === decide) {
                    pendingDecide = null
                    latch.countDown()
                }
            }
        }
        mainHandler.postDelayed(timeout, DECIDE_TIMEOUT_MS + 1_000)
        try {
            // 略长于超时兜底，保证超时分支先把 pendingDecide 清掉
            latch.await(DECIDE_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
        } finally {
            mainHandler.removeCallbacks(timeout)
            if (pendingDecide === decide) pendingDecide = null
        }
        synchronized(deniedUntil) {
            if (result) deniedUntil.remove(b64)
            else deniedUntil[b64] = SystemClock.elapsedRealtime() + DENY_MEMORY_MS
        }
        if (result) NoiseSession.trustPeerPub(ctx, pub, host)
        return result
    }
}
