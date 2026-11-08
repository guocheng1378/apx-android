package com.allperiph.controlled

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/**
 * 多设备接入确认弹窗（v223.33）——「两台设备连接时要弹窗点确定，不然打架」。
 *
 * 被控服务已有设备在线时，另一台设备握手通过请求接管；ControlServer 不再静默
 * 顶掉旧连接（旧行为：两台都自动重连就互相接管来回踢 = 注入乱跳），而是回调
 * ControlledService 拉起本页。用户点「确定」= 切换到新设备；点「取消」/返回键/
 * 页面被销毁 = 保持当前设备，新连接被拒且之后静默拒绝不再重复弹窗。
 * 超时兜底在 ControlServer（15s 未裁决默认拒绝），双重保险。
 * 透明主题 Activity：只为承载系统弹窗，没有自己的界面。
 */
class TakeoverDialogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val newPeer = intent.getStringExtra(EXTRA_NEW_PEER) ?: "?"
        val curPeer = intent.getStringExtra(EXTRA_CUR_PEER) ?: "?"
        val decide = ControlledService.pendingTakeoverDecide
        if (decide == null) {
            // 裁决已被消费（超时兜底先到）或服务已销毁 —— 无事可做
            finish(); return
        }
        AlertDialog.Builder(this)
            .setTitle("有设备请求连接")
            .setMessage(
                "当前连接：$curPeer\n\n新设备：$newPeer\n\n" +
                    "两台设备同时控制会互相打架。点「确定」切换到新设备；" +
                    "点「取消」保持当前设备。"
            )
            .setCancelable(true)
            .setPositiveButton("确定") { _, _ -> consume(decide, true) }
            .setNegativeButton("取消") { _, _ -> consume(decide, false) }
            .setOnCancelListener { consume(decide, false) }   // 返回键 / 点外部
            .show()
    }

    /** 消费裁决：只生效一次（与 15s 超时兜底并发时靠 pending 引用判重） */
    private fun consume(decide: (Boolean) -> Unit, ok: Boolean) {
        if (ControlledService.pendingTakeoverDecide === decide) {
            ControlledService.pendingTakeoverDecide = null
        }
        runCatching { decide(ok) }
        finish()
    }

    override fun onDestroy() {
        // 未作出选择就销毁（系统回收等）→ 按拒绝处理，保持当前连接不断
        ControlledService.pendingTakeoverDecide?.let { d ->
            ControlledService.pendingTakeoverDecide = null
            runCatching { d(false) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NEW_PEER = "new_peer"
        const val EXTRA_CUR_PEER = "cur_peer"
    }
}
