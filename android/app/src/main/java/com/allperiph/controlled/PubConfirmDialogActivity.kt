package com.allperiph.controlled

import android.app.Activity
import android.os.Bundle
import com.allperiph.ui.ApDialog
import com.allperiph.wireless.PeerPubGate

/**
 * R2 对端公钥首连/陌生设备确认弹窗（docs/REQ-无线安全配对.md §4.4，2026-11-17）。
 *
 * 陌生对端静态公钥请求建立加密连接（9511/9512/9502 任一通道首次出现）时，
 * PeerPubGate 在握手线程阻塞等裁决，本页承载用户交互：点「允许」= 信任该设备
 * （公钥落白名单，此后不再询问）；点「拒绝」/返回键/超时（PeerPubGate 侧 15s 兜底）
 * = 断开，60s 内同设备静默拒绝。透明主题 Activity：只为承载系统弹窗。
 */
class PubConfirmDialogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val peer = intent.getStringExtra(EXTRA_PEER) ?: "未知设备"
        val decide = PeerPubGate.pendingDecide
        if (decide == null) {
            // 裁决已被消费（超时兜底先到）或服务已销毁 —— 无事可做
            finish(); return
        }
        ApDialog.builder(this)
            .setTitle("有设备请求加密连接")
            .setMessage(
                "设备：$peer\n\n" +
                    "首次建立加密连接需要你确认。点「允许」信任该设备（它将可以控制本机）；" +
                    "点「拒绝」断开连接。如非你本人操作，请选择拒绝。"
            )
            .setCancelable(true)
            .setPositiveButton("允许") { _, _ -> consume(decide, true) }
            .setNegativeButton("拒绝") { _, _ -> consume(decide, false) }
            .setOnCancelListener { consume(decide, false) }   // 返回键 / 点外部
            .show()
    }

    /** 消费裁决：只生效一次（与 15s 超时兜底并发时靠 pending 引用判重） */
    private fun consume(decide: (Boolean) -> Unit, ok: Boolean) {
        if (PeerPubGate.pendingDecide === decide) {
            PeerPubGate.pendingDecide = null
        }
        runCatching { decide(ok) }
        finish()
    }

    override fun onDestroy() {
        // 未作出选择就销毁（系统回收等）→ 按拒绝处理，白名单不被污染
        PeerPubGate.pendingDecide?.let { d ->
            PeerPubGate.pendingDecide = null
            runCatching { d(false) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PEER = "peer"
    }
}
