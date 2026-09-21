package com.allperiph.core

/**
 * USB bulk 端点读写通道（对 `shared/` 原生实现的薄封装）。
 *
 * 存在意义：L3 的 `screen/transport/JniBulkTransport` 通过**反射**绑定本对象，
 * 以便在 native 库缺失时优雅降级（直接引用会导致 UnresolvedLinkageError 而不是可捕获的异常）。
 *
 * 端点路径默认 `/dev/usb-ffs/apx/ep{n}`（FunctionFS 命名），可用 [setPath] 覆盖；
 * 具体约定以 L2 的 ConfigFS 挂载方案为准。
 *
 * 返回值约定（与 native 一致）：0 表示成功，负数表示失败（通常为 -errno）。
 *
 * 注：本文件由 main 补入（L1 曾在 contractDeviations 中报此缺口，L2 未实现）。
 */
object UsbBulkChannel {

    /** native 库加载结果。失败时不抛异常，各方法统一返回错误码，避免拖垮调用方。 */
    private val loaded: Boolean = runCatching { System.loadLibrary("apx") }.isSuccess

    external fun nativeSetPaths(streamId: Int, rxPath: String?, txPath: String?): Int
    external fun nativeOpen(streamId: Int): Int
    external fun nativeRead(streamId: Int, dst: ByteArray, maxLen: Int): Int
    external fun nativeWrite(streamId: Int, src: ByteArray, off: Int, len: Int): Int
    external fun nativeClose(streamId: Int): Int
    external fun nativeIsOpen(streamId: Int): Boolean

    // ---- 供 JniBulkTransport 反射调用的稳定接口（实例方法，配合 INSTANCE 字段使用）----

    /**
     * 覆盖读/写端点路径，需在 [open] 之前调用。某个方向传 null 表示保持默认。
     *
     * **为什么读和写是两个路径**：FunctionFS 的端点方向在描述符里就固定了（IN / OUT），
     * 单个 `epN` 文件只能单向使用；而 [com.allperiph.screen.transport.BulkTransport]
     * 要求同一通道既能收 video（读）又能发 touch（写）。默认：
     * - rx = `/dev/usb-ffs/apx/ep1`（OUT，PC → 手机）
     * - tx = `/dev/usb-ffs/apx/ep2`（IN，手机 → PC）
     */
    fun setPaths(streamId: Int, rxPath: String? = null, txPath: String? = null): Boolean =
        if (loaded) nativeSetPaths(streamId, rxPath, txPath) == 0 else false

    fun open(streamId: Int): Int = if (loaded) nativeOpen(streamId) else -1

    fun read(streamId: Int, dst: ByteArray, maxLen: Int): Int =
        if (loaded) nativeRead(streamId, dst, maxLen) else -1

    fun write(streamId: Int, src: ByteArray, off: Int, len: Int): Int =
        if (loaded) nativeWrite(streamId, src, off, len) else -1

    fun close(streamId: Int): Int = if (loaded) nativeClose(streamId) else -1

    fun isOpen(streamId: Int): Boolean = loaded && nativeIsOpen(streamId)
}
