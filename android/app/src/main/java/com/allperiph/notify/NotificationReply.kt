package com.allperiph.notify

import android.app.RemoteInput
import android.content.Intent
import com.allperiph.App
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.proto.ApxFrame.CMD_NOTIFY_REPLY
import com.allperiph.shared.proto.ApxFrame.NOTIFY_REPLY_ERR_DISABLED
import com.allperiph.shared.proto.ApxFrame.NOTIFY_REPLY_ERR_KEY_GONE
import com.allperiph.shared.proto.ApxFrame.NOTIFY_REPLY_ERR_NO_ACTION
import com.allperiph.shared.proto.ApxFrame.NOTIFY_REPLY_ERR_SEND_FAILED
import com.allperiph.shared.proto.ApxFrame.NOTIFY_REPLY_ERR_SUBMITTED
import com.allperiph.shared.util.Log

/**
 * 通知回复处理器（2026-11-14，REQ-通知回复.md 第一批，能力位 bit1）：
 * PC 发来 0x41 通知回复请求 → 按通知 key 找回 [ApxNotificationListener] 缓存的
 * 可回复 action → 程序性填充 RemoteInput 并 `actionIntent.send()` —— 等效用户在
 * 通知栏点了「回复」打字，对目标 App 完全透明（平台 API 直用，无第三方依赖）。
 *
 * 收帧路径与 PhoneFiles/PhoneAlbum 同一套注入口径（REQ 汇合点）：
 *  · [ControlServer.onNotifyReplyCmd]（本机被控，ControlledService 注入）；
 *  · TvControlServer 同名回调（TV 端不注入 —— 共享层收帧后有 err=1 兜底应答）；
 *  · TvControllerClient 直调（手机控 PC / TV 的链路上，PC 服务端反向发来 0x41）。
 *
 * 回执口径：**0x43 全路径必发**（成功也发）—— 用户在 PC 端等终态。err 语义见
 * [ApxFrame.NOTIFY_REPLY_ERR_SUBMITTED] 组常量；「已提交」≠「对方收到」——回复经系统
 * 通知通道进目标 App，送达态它自己不回执，这是 RemoteInput 通道的天然边界。
 */
object NotificationReply {
    private const val TAG = "NotifyReply"

    /**
     * 处理一条 0x41 通知回复请求并回 0x43。cmd 固定为 [CMD_NOTIFY_REPLY]
     *（与 PhoneFiles.handle 的 cmd 参数同款式，保留扩展位）。
     * @param body  0x41 载荷（含 cmd 字节）
     * @param reply 回执通道 = 当前链路的 sendControl（0x43 原路返回）
     */
    fun handle(cmd: Int, body: ByteArray, reply: (ByteArray) -> Boolean) {
        val ctx = App.context ?: run {
            // App 还没起来（极端边缘）：收了帧没法干活，如实回「发送失败」不装死
            Log.w(TAG, "App.context 未就绪，通知回复无从提交，回 err=4")
            reply(ApxFrame.packNotifyReplyResult(NOTIFY_REPLY_ERR_SEND_FAILED))
            return
        }
        val req = if (cmd == CMD_NOTIFY_REPLY) ApxFrame.parseNotifyReply(body) else null
        if (req == null) {
            // 坏载荷：整帧丢弃，但回执仍要给 —— PC 端在等终态，err=4（发送失败）
            Log.w(TAG, "通知回复请求载荷不合法（长度 ${body.size}），回 err=4 兜底")
            reply(ApxFrame.packNotifyReplyResult(NOTIFY_REPLY_ERR_SEND_FAILED))
            return
        }
        val err = try {
            submitReply(ctx, req.key, req.text)
        } catch (t: Throwable) {
            // PendingIntent.send 抛异常（目标 App 拒绝 / 远端异常）：归 err=4 发送失败
            Log.w(TAG, "RemoteInput 提交失败（key=${req.key}）：${t.message}")
            NOTIFY_REPLY_ERR_SEND_FAILED
        }
        Log.i(TAG, "通知回复：key=${req.key} → err=$err")
        reply(ApxFrame.packNotifyReplyResult(err))
    }

    /**
     * 真正的提交路径，返回 0x43 err 码。监听器侧先判开关再查缓存：
     * 开关关 = 整条回复能力没开（err=1）；缓存没有 = 通知已不在（err=2）；
     * action 没有 RemoteInput = 纯展示通知（err=3，正常缓存规则下到不了这里，兜底保留）。
     */
    private fun submitReply(ctx: android.content.Context, key: String, text: String): Int {
        // 开关第一道：通知使用权没开（或用户只开了推送没授权）时明确回 err=1，
        // PC 端气泡据此提示「请先在手机上开启通知同步」。
        if (!NotificationSyncPref.isEnabled(ctx)) return NOTIFY_REPLY_ERR_DISABLED
        val action = ApxNotificationListener.replyAction(key)
            ?: return NOTIFY_REPLY_ERR_KEY_GONE
        val inputs = action.remoteInputs
        if (inputs.isNullOrEmpty()) return NOTIFY_REPLY_ERR_NO_ACTION
        // 程序性填充 RemoteInput：把 PC 端打的字按 resultKey 塞进结果 Bundle，
        // 再走 action 自带的 PendingIntent 发出 —— 与手点「回复」后系统走的路径完全一致。
        // 注意别把 bundle 直接 putExtras 进 intent：addResultsToIntent 自己会把结果装进
        // RemoteInput 约定的 extras 键下，双份塞反而可能让目标 App 读到两遍。
        val results = android.os.Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(ctx, 0, intent)
        return NOTIFY_REPLY_ERR_SUBMITTED
    }
}
