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
 * /dev/hidg0 读写（f_hid 创建的字符设备）。
 *
 * 关键实现约束：
 * - 必须 **O_NONBLOCK**：f_hid 同一时刻只允许一个 IN 请求在途，主机未取走时再写会阻塞/返回 EBUSY。
 *   传感器是天然可丢数据面，宁可丢一帧也不能让采集线程卡死。
 * - 读侧用轮询而非阻塞读：OUT 报告（PC 命令）频率极低，4ms 轮询完全够用，
 *   同时避免阻塞读导致 close() 无法及时打断。
 */
class HidDevice(
    private val path: String = SysPath.HIDG_DEVICE,
) : HidTransport, AutoCloseable {

    @Volatile
    private var fd: FileDescriptor? = null

    @Volatile
    private var reading = false

    private var readerThread: Thread? = null

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    // 写失败诊断（issue#168 2026-11-11）：连败计数只用于日志定性，成功一笔即清零；
    // lastFailLogMs/lastReopenMs 是节流游标，均在写入线程串行访问，无需原子类。
    @Volatile
    private var consecutiveWriteFailures = 0
    private var lastFailLogMs = 0L
    private var lastReopenMs = 0L

    val isOpen: Boolean get() = fd != null

    /** 挂载后内核异步创建节点，这里轮询等待并打开 */
    fun open(waitMs: Long = GadgetConst.DEVICE_NODE_WAIT_MS): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        var lastErr: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val f = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                fd = f
                Log.i(TAG, "opened $path")
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
        Log.w(TAG, "cannot open $path: $lastErr")
        return false
    }

    /** 注册 OUT 报告回调（PC 下发的 Vendor 命令） */
    fun setReportListener(l: ((ByteArray) -> Unit)?) {
        listener = l
        if (l != null) startReader()
    }

    private fun startReader() {
        if (reading) return
        reading = true
        readerThread = kotlin.concurrent.thread(start = true, name = "apx-hid-read") {
            val buf = ByteArray(HID_MAX_REPORT_BYTES)
            while (reading) {
                val f = fd
                if (f == null) {
                    sleepQuiet(READ_POLL_MS)
                    continue
                }
                val n = try {
                    Os.read(f, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    // 非阻塞无数据
                    // 非阻塞无数据：Android 的 OsConstants 不暴露 EWOULDBLOCK（Linux 上它恒等于 EAGAIN），
                    // 只判断 EAGAIN 即可，否则 Unresolved reference。
                    if (e.errno == OsConstants.EAGAIN) {
                        sleepQuiet(READ_POLL_MS)
                        continue
                    }
                    Log.w(TAG, "read error: ${e.message}")
                    sleepQuiet(READ_POLL_MS)
                    continue
                }
                if (n > 0) {
                    val report = buf.copyOf(n)
                    listener?.invoke(report)
                } else {
                    sleepQuiet(READ_POLL_MS)
                }
            }
            Log.i(TAG, "reader exit")
        }
    }

    override fun sendInputReport(report: ByteArray): Boolean {
        val f = fd ?: return false
        if (report.isEmpty() || report.size > HID_MAX_REPORT_BYTES) {
            Log.w(TAG, "invalid report size=${report.size}")
            return false
        }
        var off = 0
        while (off < report.size) {
            val n = try {
                Os.write(f, report, off, report.size - off)
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
                onWriteFailure(e.errno)
                return false
            }
            if (n <= 0) {
                onWriteFailure(0)
                return false
            }
            off += n
        }
        // 任意一笔成功即清连败计数（EAGAIN 偶发丢帧属正常，不该触发重开）
        consecutiveWriteFailures = 0
        return true
    }

    /**
     * 写失败统一入口：errno 节流落日志 + 陈旧 fd 自愈（issue#168 2026-11-11）。
     *
     * 自愈判据：ENODEV / ESHUTDOWN / EBADF / EINVAL 这类**结构性**错误说明 fd 已失效
     * （gadget 被系统重绑 / UDC 重挂后内核节点重建，旧 fd 指着已死的 inode），写多少次
     * 都不会好 —— 关掉旧 fd 后**重开**一次节点即可恢复（节点名固定，重开成功即满血）。
     * EAGAIN / EALREADY 是「主机暂时没取」的正常丢帧，只记日志不动 fd。
     * 重开有 2s 限频：连续失败时避免在输入线程里高频开关节点。
     */
    private fun onWriteFailure(errno: Int) {
        consecutiveWriteFailures++
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailLogMs >= FAIL_LOG_INTERVAL_MS) {
            lastFailLogMs = now
            // 连败数一起记：对端看日志就能判断「偶发丢帧」还是「全挂」
            Log.w(TAG, "HID 写入失败 errno=$errno（连续 $consecutiveWriteFailures 次）")
        }
        if (!isStaleFdErrno(errno)) return
        if (now - lastReopenMs < REOPEN_MIN_INTERVAL_MS) return
        lastReopenMs = now
        Log.w(TAG, "HID fd 已失效（errno=$errno）：关闭并重开 $path 自愈")
        runCatching { fd?.let { Os.close(it) } }
        fd = null
        if (open(waitMs = 0)) {
            Log.i(TAG, "HID 自愈成功：$path 已重开")
        } else {
            // 节点也没了（gadget 已被拆）：isReady() 转 false，Uplink 如实报「无出口」，
            // 恢复由 GadgetManager 的挂载/自愈流程负责，这里不越权重建 gadget。
            Log.w(TAG, "HID 自愈失败：$path 暂不可开（等 gadget 模块重新挂载）")
        }
    }

    /** 结构性错误 = fd 已失效，重开才有救；EAGAIN/EALREADY 属正常丢帧 */
    private fun isStaleFdErrno(errno: Int): Boolean = when (errno) {
        OsConstants.ENODEV, OsConstants.ESHUTDOWN, OsConstants.EBADF, OsConstants.EINVAL -> true
        else -> false
    }

    override fun isReady(): Boolean = fd != null

    override fun close() {
        reading = false
        readerThread?.interrupt()
        readerThread = null
        listener = null
        fd?.let {
            runCatching { Os.close(it) }
                .onFailure { Log.w(TAG, "close: ${it.message}") }
        }
        fd = null
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
    }
}
