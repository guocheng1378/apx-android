package com.allperiph.gadget

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.allperiph.core.GadgetConst
import com.allperiph.core.HID_MAX_REPORT_BYTES
import com.allperiph.core.HidTransport
import com.allperiph.shared.util.Log
import com.allperiph.core.SysPath
import java.io.FileDescriptor

/**
 * f_hid 设备节点读写（/dev/hidgN）。
 *
 * issue#170 #2（2026-11-14）：gadget 从单 HID 实例拆为三个（hid.usb0/1/2），
 * 一个实例一个「用途面」，各实例首 TLC 即该接口主用途——否则 macOS 按主用途
 * 匹配，主用途非 Mouse 的复合接口其鼠标 TLC 不会被指针事件系统挂上
 * （实测 pad→Mac「3 指手势有效、单指/双指全无效」）。因此本类从单 fd
 * 扩为**多节点 + 按 Report ID 路由**：
 *   hidg0 指针面：rid 2（鼠标）/ rid 4（多媒体）→ 单指、双指、中键走这里
 *   hidg1 控制面：rid 22（手柄）/ rid 5（Vendor 状态）+ 兜底（未知 rid）
 *   hidg2 键盘面：rid 21（键盘）
 * OUT 报告（PC 下发的 Vendor 命令）只在控制面节点上监听（其描述符含 Output 报告）。
 *
 * 关键实现约束（沿旧版）：
 * - 必须 **O_NONBLOCK**：f_hid 同一时刻只允许一个 IN 请求在途，主机未取走时再写会
 *   返回 EAGAIN/EBUSY。传感器/鼠标是天然可丢数据面，宁可丢一帧也不能让采集线程卡死。
 * - 读侧用轮询而非阻塞读：OUT 报告（PC 命令）频率极低，4ms 轮询完全够用，
 *   同时避免阻塞读导致 close() 无法及时打断。
 */
class HidDevice(
    /** 节点表（顺序无关；路由按 [Node.reportIds] + [Node.fallback]） */
    private val nodes: List<Node> = listOf(Node(SysPath.HIDG_DEVICE)),
) : HidTransport, AutoCloseable {

    /** 一个 f_hid 实例（节点） */
    data class Node(
        val path: String,
        /** 本节点承载的 Report ID；空 = 无专属报告 */
        val reportIds: Set<Int> = emptySet(),
        /** 未命中路由的 Report ID 兜底走本节点（旧单节点语义的归宿） */
        val fallback: Boolean = false,
        /** 是否监听 OUT 报告（Vendor 控制面） */
        val readOut: Boolean = false,
    )

    /** 单节点运行态：fd、读写失败诊断、OUT 读线程（issue#168/170 的既有逻辑按节点分摊） */
    private inner class NodeFd(val spec: Node) {
        @Volatile
        var fd: FileDescriptor? = null

        // 写失败诊断（issue#168 2026-11-11）：连败计数只用于日志定性，成功一笔即清零；
        // lastFailLogMs/lastReopenMs 是节流游标，均在写入线程串行访问，无需原子类。
        @Volatile
        var consecutiveWriteFailures = 0
        var lastFailLogMs = 0L
        var lastReopenMs = 0L

        val isOpen: Boolean get() = fd != null

        /** 挂载后内核异步创建节点，这里轮询等待并打开 */
        fun open(waitMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + waitMs
            var lastErr: String? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    fd = Os.open(spec.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                    Log.i(TAG, "opened ${spec.path}")
                    return true
                } catch (e: ErrnoException) {
                    lastErr = e.message
                    try {
                        Thread.sleep(GadgetConst.DEVICE_NODE_POLL_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            Log.w(TAG, "cannot open ${spec.path}: $lastErr")
            return false
        }

        fun write(buf: ByteArray, off: Int, len: Int): Int =
            writeWithEagainRetry(fd ?: throw IllegalStateException("closed"), buf, off, len)

        fun onWriteFailure(errno: Int) {
            consecutiveWriteFailures++
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFailLogMs >= FAIL_LOG_INTERVAL_MS) {
                lastFailLogMs = now
                // 连败数一起记：对端看日志就能判断「偶发丢帧」还是「全挂」
                Log.w(TAG, "${spec.path} 写入失败 errno=$errno（连续 $consecutiveWriteFailures 次）")
            }
            if (!isStaleFdErrno(errno)) return
            if (now - lastReopenMs < REOPEN_MIN_INTERVAL_MS) return
            lastReopenMs = now
            Log.w(TAG, "${spec.path} fd 已失效（errno=$errno）：关闭并重开自愈")
            runCatching { fd?.let { Os.close(it) } }
            fd = null
            if (open(waitMs = 0)) {
                Log.i(TAG, "${spec.path} 自愈成功：已重开")
            } else {
                // 节点也没了（gadget 已被拆）：isReady() 转 false，Uplink 如实报「无出口」，
                // 恢复由 GadgetManager 的挂载/自愈流程负责，这里不越权重建 gadget。
                Log.w(TAG, "${spec.path} 自愈失败：暂不可开（等 gadget 模块重新挂载）")
            }
        }

        fun close() {
            fd?.let {
                runCatching { Os.close(it) }
                    .onFailure { Log.w(TAG, "close ${spec.path}: ${it.message}") }
            }
            fd = null
        }
    }

    private val nodeFds: List<NodeFd> = nodes.map { NodeFd(it) }

    /** Report ID → 节点下标；未命中的 rid 走 fallback 节点 */
    private val routeByRid: Map<Int, Int> = buildMap {
        nodeFds.forEachIndexed { idx, n ->
            for (rid in n.spec.reportIds) put(rid, idx)
        }
    }

    private val fallbackIdx: Int =
        nodeFds.indexOfFirst { it.spec.fallback }.takeIf { it >= 0 }
            ?: (if (nodeFds.size == 1) 0 else nodeFds.size - 1)

    private val outNodes: List<NodeFd> = nodeFds.filter { it.spec.readOut }

    @Volatile
    private var reading = false

    private var readerThreads: List<Thread> = emptyList()

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    val isOpen: Boolean get() = nodeFds.isNotEmpty() && nodeFds.all { it.isOpen }

    /** 至少有一个节点已打开（多节点下「全死」才算未就绪，单节点语义不变） */
    fun anyOpen(): Boolean = nodeFds.any { it.isOpen }

    /** 挂载后内核异步创建节点，这里轮询等待并逐节点打开。
     *  返回成功打开的节点数；调用方（GadgetManager）据 `全部失败` 决定 fail。 */
    fun open(waitMs: Long = GadgetConst.DEVICE_NODE_WAIT_MS): Int {
        var ok = 0
        for (n in nodeFds) {
            if (n.open(waitMs)) ok++
        }
        return ok
    }

    /** 注册 OUT 报告回调（PC 下发的 Vendor 命令）；在所有 readOut 节点上起读线程 */
    fun setReportListener(l: ((ByteArray) -> Unit)?) {
        listener = l
        if (l != null) startReaders()
    }

    private fun startReaders() {
        if (reading) return
        reading = true
        readerThreads = outNodes.map { n ->
            kotlin.concurrent.thread(start = true, name = "apx-hid-read-${n.spec.path}") {
                val buf = ByteArray(HID_MAX_REPORT_BYTES)
                while (reading) {
                    val f = n.fd
                    if (f == null) {
                        sleepQuiet(READ_POLL_MS)
                        continue
                    }
                    val len = try {
                        Os.read(f, buf, 0, buf.size)
                    } catch (e: ErrnoException) {
                        // 非阻塞无数据：Android 的 OsConstants 不暴露 EWOULDBLOCK
                        //（Linux 上它恒等于 EAGAIN），只判断 EAGAIN 即可
                        if (e.errno == OsConstants.EAGAIN) {
                            sleepQuiet(READ_POLL_MS)
                            continue
                        }
                        Log.w(TAG, "read error ${n.spec.path}: ${e.message}")
                        sleepQuiet(READ_POLL_MS)
                        continue
                    }
                    if (len > 0) {
                        val report = buf.copyOf(len)
                        listener?.invoke(report)
                    } else {
                        sleepQuiet(READ_POLL_MS)
                    }
                }
                Log.i(TAG, "reader exit ${n.spec.path}")
            }
        }
    }

    /** 按 Report ID 首字节路由到对应 f_hid 实例（issue#170 #2） */
    private fun routeIdx(report: ByteArray): Int {
        val rid = report[0].toInt() and 0xFF
        val idx = routeByRid[rid]
        if (idx != null) return idx
        // 兜底：未知 rid（历史遗留 / 调试写入）交给控制面节点，行为与旧单节点一致
        return fallbackIdx
    }

    override fun sendInputReport(report: ByteArray): Boolean {
        if (report.isEmpty() || report.size > HID_MAX_REPORT_BYTES) {
            Log.w(TAG, "invalid report size=${report.size}")
            return false
        }
        val node = nodeFds.getOrNull(routeIdx(report)) ?: return false
        var off = 0
        while (off < report.size) {
            val n = try {
                node.write(report, off, report.size - off)
            } catch (e: ErrnoException) {
                // 主机未取走上一段：丢帧，绝不阻塞。
                //
                // issue#168 2026-11-11 15:12 反馈（pad 日志通读）：有线模式 15 分钟里
                // 键盘 HID **每一笔**写入都失败（Uplink 在 USB/无出口之间来回翻），
                // 而这里长期把 errno 吞掉不记 —— 复盘时只能看到「写入失败」，分不清是
                //   EAGAIN/EALREADY（主机没在取，保持续写即可）还是
                //   ENODEV/ESHUTDOWN/EBADF（fd 已随 gadget 重绑失效 / 端点已关，
                //   继续写一万次也不会好，必须重开设备节点）。
                // 两类的处置完全不同，errno 就是分水岭 —— 从这里开始必须留下。
                node.onWriteFailure(e.errno)
                return false
            } catch (_: IllegalStateException) {
                return false
            }
            if (n <= 0) {
                node.onWriteFailure(0)
                return false
            }
            off += n
        }
        // 任意一笔成功即清连败计数（EAGAIN 偶发丢帧属正常，不该触发重开）
        node.consecutiveWriteFailures = 0
        return true
    }

    /**
     * EAGAIN 短程重试（issue#170 2026-11-13 pad 日志定性）：f_hid 对 O_NONBLOCK 写，
     * 主机未取走上一份 IN 报告时返回 EAGAIN —— 此前直接丢帧，pad 实测 15 分钟 96 笔
     * 失败、Uplink 出口在「USB / 无出口」之间横跳，用户观感「只能单发、连发全灭」。
     * 主机取报文的间隔是毫秒级，这里对 EAGAIN 做短程重试（上限约 32ms）：短暂停顿
     * 绝大多数能等回来；持续失败（主机真不在读）仍按丢帧上抛，维持「绝不阻塞输入线程」
     * 的既有约束 —— 连发节奏由上层节流兜底，这里不引入长阻塞。
     */
    private fun writeWithEagainRetry(f: FileDescriptor, buf: ByteArray, off: Int, len: Int): Int {
        var tries = 0
        while (true) {
            try {
                return Os.write(f, buf, off, len)
            } catch (e: ErrnoException) {
                // OsConstants 不暴露 EWOULDBLOCK，Linux 上它恒等于 EAGAIN，只判 EAGAIN
                if (e.errno == OsConstants.EAGAIN && tries < EAGAIN_MAX_RETRIES) {
                    tries++
                    sleepQuiet(EAGAIN_RETRY_MS)
                    continue
                }
                throw e
            }
        }
    }

    /** 结构性错误 = fd 已失效，重开才有救；EAGAIN/EALREADY 属正常丢帧。
     *  ESHUTDOWN(108) 在 Android 的 OsConstants 不暴露（CI 实测 Unresolved reference），
     *  按 Linux 通用数值直接写死，其余三个平台有常量。 */
    private fun isStaleFdErrno(errno: Int): Boolean = when (errno) {
        OsConstants.ENODEV, 108 /* ESHUTDOWN */, OsConstants.EBADF, OsConstants.EINVAL -> true
        else -> false
    }

    override fun isReady(): Boolean = anyOpen()

    override fun close() {
        reading = false
        for (t in readerThreads) t.interrupt()
        readerThreads = emptyList()
        listener = null
        for (n in nodeFds) n.close()
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            reading = false
        }
    }

    companion object {
        private const val TAG = "HidDevice"
        private const val READ_POLL_MS = 4L

        // 写失败日志节流与 fd 重开限频（issue#168 2026-11-11，见 onWriteFailure 注释）
        private const val FAIL_LOG_INTERVAL_MS = 5_000L
        private const val REOPEN_MIN_INTERVAL_MS = 2_000L

        // EAGAIN 短程重试参数（issue#170 2026-11-13，见 writeWithEagainRetry 注释）：
        // 8 次 × 4ms ≈ 32ms 上限，覆盖主机取报文的典型毫秒级间隔，又不至于拖死输入线程
        private const val EAGAIN_MAX_RETRIES = 8
        private const val EAGAIN_RETRY_MS = 4L

        /** USB 拆分后的默认节点表（issue#170 #2）：
         *  hidg0=指针(rid2/4)、hidg1=控制(rid22/5, 兜底, 监听 OUT)、hidg2=键盘(rid21) */
        fun usbSplitNodes(): List<Node> = listOf(
            Node(
                SysPath.HIDG_DEVICE,
                reportIds = setOf(RID_MOUSE, RID_CONSUMER),
            ),
            Node(
                SysPath.HIDG_CONTROL_DEVICE,
                reportIds = setOf(RID_GAMEPAD, RID_VENDOR),
                fallback = true,
                readOut = true,
            ),
            Node(
                SysPath.HIDG_KEYBOARD_DEVICE,
                reportIds = setOf(RID_KEYBOARD),
            ),
        )

        // Report ID（与 hid_layout.h / PROTOCOL §2 一致；蓝牙侧另有编号，勿混）
        private const val RID_MOUSE = 0x02      // §2.10 相对鼠标
        private const val RID_CONSUMER = 0x04   // §2.6 多媒体键（三指中键等）
        private const val RID_VENDOR = 0x05     // §2.7 Vendor 状态/命令
        private const val RID_KEYBOARD = 0x15   // §2.12 键盘（21）
        private const val RID_GAMEPAD = 0x16    // §2.13 手柄（22）
    }
}
