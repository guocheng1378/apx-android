package com.allperiph.core

/**
 * FunctionFS：副屏的 bulk 数据面（video 下行 / touch 上行）。
 *
 * **为什么需要用户态参与**：FFS 与 `f_hid` / `f_acm` 有本质差异 —— 它在 configfs 里
 * **不自带** interface 与 endpoint 声明，接口/端点描述符必须由**用户态写 `ep0`** 提供。
 * 内核在 `ffs_func_bind()` 里会 `wait_event_interruptible` 等待描述符就绪，所以
 * [writeDescriptors] 会**阻塞直到 UDC 绑定完成**才返回。
 *
 * **因此必须在独立线程里调用**，绝不能放在挂载流程中间同步执行 —— 那会卡住整个
 * 挂载直到 UDC 绑定，而 UDC 绑定又排在挂载的最后一步，形成死锁。
 *
 * 端点分配（见 native 侧 `buildFfsDescriptors`）：
 * ```
 * ep1 = OUT（PC → 手机）：video / ctrl 下行
 * ep2 = IN （手机 → PC）：touch / telemetry 上行
 * ```
 * 各 streamId 复用同一对物理端点，按 `PROTOCOL.md §3` 帧头的 `streamId` 字段分流。
 *
 * 前置条件（由 gadget 层完成）：
 * 1. configfs 建 `functions/ffs.apx` 并链到 config
 * 2. `mount -t functionfs apx /dev/usb-ffs/apx`
 * 3. 绑定 UDC
 */
object FfsChannel {

    private const val TAG = "FfsChannel"

    private val loaded: Boolean = runCatching { System.loadLibrary("apx") }.isSuccess

    /** 写描述符到 ep0；**阻塞到 UDC 绑定完成**。 */
    private external fun nativeWriteDescriptors(ep0Path: String): Int

    /** 关闭缓存的 ep0 fd（卸载时调用）。 */
    private external fun nativeClose(): Int

    /**
     * 释放 ep0。
     *
     * **卸载/重新挂载前必须调用**：只要还有 fd 打开，functionfs 就 `umount` 不掉，
     * 于是 ffs 实例一直存活；反复挂载会耗尽内核的 FFS 上下文
     * （实测 dmesg：`Can't create any more FFS log contexts`），
     * 最终连 UDC 绑定都会失败（`failed to start apx: -19`）。
     */
    fun close(): Int {
        if (!loaded) return -1
        return runCatching { nativeClose() }.getOrDefault(-1)
    }

    /**
     * 向 FunctionFS 的 ep0 写入 interface + 2 个 bulk 端点的描述符。
     *
     * **会阻塞到 UDC 绑定完成**，调用方必须在独立线程执行。
     *
     * @return 0 成功；负数为 -errno（`-2` = ep0 不存在，即 functionfs 未挂载；
     *         `-22` = 描述符被内核拒绝）
     */
    fun writeDescriptors(ep0Path: String = SysPath.FFS_EP0): Int {
        if (!loaded) {
            Log.w(TAG, "libapx 未加载，无法写 FFS 描述符")
            return -1
        }
        val rc = runCatching { nativeWriteDescriptors(ep0Path) }.getOrDefault(-1)
        if (rc != 0) Log.w(TAG, "写 FFS 描述符失败 rc=$rc（$ep0Path）")
        return rc
    }
}
