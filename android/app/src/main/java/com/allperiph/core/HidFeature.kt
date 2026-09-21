package com.allperiph.core

/**
 * 把 Feature Report 主动提供给内核 `f_hid`。
 *
 * **为什么必须由用户态提供**：主机（Windows）的 `GET_REPORT` 是**控制传输**，
 * 不经过 INTERRUPT OUT，因此**永远不会出现在 `/dev/hidg0` 的 `read()` 里**。
 * 实测：挂载数分钟内一条 OUT 事件都没有（不是丢包，是路径根本不同）。
 * Linux 6.12 起 `f_hid` 提供 `GADGET_HID_WRITE_GET_REPORT` ioctl，由用户态
 * 登记应答内容，内核负责在该请求到来时回给主机。
 *
 * **不登记的后果**：Windows `SensorsHIDClassDriver` 在启动阶段会索取
 * Report State / Report Interval，拿不到就以 Code 10（`STATUS_INVALID_PARAMETER`）
 * 失败 —— 表现为设备管理器里每个传感器 TLC 都「无法启动」，
 * 而通用 usage 的 TLC（识别不出类型、驱动不去初始化）反而显示正常。
 *
 * 上游补丁：*USB: gadget: f_hid: Add GET_REPORT via userspace IOCTL*（v6.12）。
 */
object HidFeature {

    /** native 库加载结果。失败时不抛异常，各方法统一返回错误码。 */
    private val loaded: Boolean = runCatching { System.loadLibrary("apx") }.isSuccess

    external fun nativeWriteGetReport(path: String, reportId: Int, data: ByteArray): Int

    /**
     * 登记 [reportId] 对应的 Feature Report 应答内容；登记一次即长期有效。
     *
     * @param path 已由 f_hid 创建的 HID 节点，通常是 `/dev/hidg0`
     * @param data 与报告描述符中该 TLC 的 Feature 字段布局**逐字节对应**的载荷
     * @return 0 成功；负数为 -errno。
     *         `-25`(ENOTTY) 表示内核 `f_hid` 不支持该 ioctl（需 6.12+）；
     *         `-2`(ENOENT) 表示节点不存在（`f_hid` 未挂载或 UDC 未绑定）。
     */
    fun writeGetReport(path: String, reportId: Int, data: ByteArray): Int =
        if (loaded) nativeWriteGetReport(path, reportId, data) else -1
}
