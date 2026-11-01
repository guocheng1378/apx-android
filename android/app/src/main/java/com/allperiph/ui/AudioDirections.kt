package com.allperiph.ui

import android.content.Context
import com.allperiph.audio.AudioModule
import com.allperiph.audio.WirelessAudioModule
import com.allperiph.core.ModuleId

/**
 * 音频方向开关的**设置页门面**（USB UAC2 与 Wi‑Fi 两套各两个方向）。
 *
 * 为什么需要这一层，而不是让设置页直接调 `AudioModule.applyMic`：
 *
 *  · `applyMic` / `applySpeaker` 是**实例方法**（要改的是正在跑的流），而模块实例只在
 *    `AgentController` 的注册表里、服务没起时是 null。设置页若自己判空，四条开关各写一遍
 *    「有实例就热切换、没实例就落盘」的分支，漏一条就是「关了没用」（issue #52 的教训）。
 *  · 门面把这条规则收在一处，并且给能力入口关卡一个**稳定可核对的名字**：
 *    `scripts/check_entrypoints.py` 按 `AudioDirections.setUsbMic` 这样的限定名核对调用，
 *    比按方法名核对精确得多（`applyMic` 在两个模块里同名）。
 *
 * 两个方向都关掉时模块不报错：`recomputeState` 会落到 DEGRADED「挂起待命」。
 */
object AudioDirections {

    private fun usb(): AudioModule? = AgentController.module(ModuleId.AUDIO) as? AudioModule

    private fun wifi(): WirelessAudioModule? =
        AgentController.module(ModuleId.WIFI_AUDIO) as? WirelessAudioModule

    /** USB：手机麦克风 → 电脑 */
    fun setUsbMic(ctx: Context, on: Boolean) {
        AudioModule.setMicPref(ctx, on)
        usb()?.applyMic(ctx, on)
    }

    /** USB：电脑 → 手机扬声器 */
    fun setUsbSpeaker(ctx: Context, on: Boolean) {
        AudioModule.setSpeakerPref(ctx, on)
        usb()?.applySpeaker(ctx, on)
    }

    /** Wi‑Fi：手机麦克风 → 电脑 */
    fun setWifiMic(ctx: Context, on: Boolean) {
        WirelessAudioModule.setMicPref(ctx, on)
        wifi()?.applyMic(ctx, on)
    }

    /** Wi‑Fi：电脑 → 手机扬声器 */
    fun setWifiSpeaker(ctx: Context, on: Boolean) {
        WirelessAudioModule.setSpeakerPref(ctx, on)
        wifi()?.applySpeaker(ctx, on)
    }
}
