package com.allperiph.core

/**
 * UAC2 gadget 的 PCM 通道（native 裸 ALSA ioctl 的薄封装）。
 *
 * 为什么裸写而不走 alsa-lib：NDK 不提供 `libasound`，Android 系统里也没有；
 * 但内核 uapi 的 `<sound/asound.h>` 由 NDK sysroot 自带，足够完成
 * open / hw_params / sw_params / prepare / start 与 read/write。
 *
 * 方向语义（UAC2 gadget 侧，实测 `/proc/asound/pcm` 报告
 * `01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1`）：
 * - **playback**（`pcmC?D0p`）：应用写入 → 经 USB 发给主机，PC 侧表现为**麦克风**
 * - **capture**（`pcmC?D0c`）：主机发来 → 应用读取，PC 侧表现为**扬声器**
 *
 * 节点权限：`/dev/snd/pcmC*` 默认仅 `audio` 组可读写，而 App 属 `untrusted_app`，
 * 需由 gadget 挂载阶段以 root `chmod 666`（见 [com.allperiph.gadget.ConfigFsLayout]）。
 */
object AlsaPcm {

    private const val TAG = "AlsaPcm"

    private val loaded: Boolean = runCatching { System.loadLibrary("apx") }.isSuccess

    private external fun nativeOpen(path: String, playback: Boolean, rate: Int, channels: Int): Int
    private external fun nativeWrite(fd: Int, src: ByteArray): Int
    private external fun nativeRead(fd: Int, dst: ByteArray): Int
    private external fun nativeClose(fd: Int)

    /** 一条已打开并已 start 的 PCM 流。 */
    class Stream internal constructor(private val fd: Int) {

        val isOpen: Boolean get() = fd >= 0

        /** @return 实际写入字节数；负数为 -errno */
        fun write(data: ByteArray): Int = if (fd >= 0) nativeWrite(fd, data) else -1

        /** @return 实际读到的字节数（0 表示暂无数据）；负数为 -errno */
        fun read(dst: ByteArray): Int = if (fd >= 0) nativeRead(fd, dst) else -1

        fun close() {
            if (fd >= 0) nativeClose(fd)
        }
    }

    /**
     * 打开一条 PCM 流；失败返回 null 并把错误码写进日志。
     *
     * @param playback true = playback 流（`pcmC?D0p`），false = capture 流（`pcmC?D0c`）
     * @param rate 采样率，必须与 `f_uac2` 的声明一致（见 [AudioConst.SAMPLE_RATE]）
     * @param channels 通道数，必须与通道掩码一致（见 [AudioConst.CHANNELS]）
     */
    fun open(
        path: String,
        playback: Boolean,
        rate: Int = AudioConst.SAMPLE_RATE,
        channels: Int = AudioConst.CHANNELS,
    ): Stream? {
        if (!loaded) {
            Log.w(TAG, "libapx 未加载，无法打开 PCM")
            return null
        }
        val fd = runCatching { nativeOpen(path, playback, rate, channels) }.getOrDefault(-1)
        if (fd < 0) {
            // -2 ENOENT 节点不存在（UAC2 未挂载）；-13 EACCES 权限未放开；
            // -22 EINVAL 参数不被支持（多半是格式/采样率与 f_uac2 声明不一致）
            Log.w(TAG, "open $path 失败 rc=$fd")
            return null
        }
        Log.i(TAG, "opened $path（playback=$playback rate=$rate ch=$channels）")
        return Stream(fd)
    }
}
