package com.allperiph.gadget

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.allperiph.core.GadgetConst
import com.allperiph.core.HID_MAX_REPORT_BYTES
import com.allperiph.core.HidTransport
import com.allperiph.core.Log
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
                // 主机未取走上一段：丢帧，绝不阻塞
                return false
            }
            if (n <= 0) return false
            off += n
        }
        return true
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
    }
}
