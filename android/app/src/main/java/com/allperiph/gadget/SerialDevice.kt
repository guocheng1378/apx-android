package com.allperiph.gadget

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.allperiph.core.GadgetConst
import com.allperiph.core.Log
import com.allperiph.core.SerialSink
import com.allperiph.core.SysPath
import java.io.FileDescriptor

/**
 * /dev/ttyGS0（f_acm 创建的 CDC ACM 设备节点）写入端。
 * Windows 侧枚举为 COM 口，Linux 侧可被 gpsd 直接读取。
 *
 * 同样使用 O_NONBLOCK：主机侧未打开串口时内核缓冲写满会返回 EAGAIN，
 * NMEA 每秒一帧，丢弃一帧无影响。
 */
class SerialDevice(
    private val path: String = SysPath.ACM_DEVICE,
) : SerialSink, AutoCloseable {

    @Volatile
    private var fd: FileDescriptor? = null

    val isOpen: Boolean get() = fd != null

    fun open(waitMs: Long = GadgetConst.DEVICE_NODE_WAIT_MS): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        var lastErr: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                fd = Os.open(path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
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

    override fun write(bytes: ByteArray): Boolean {
        val f = fd ?: return false
        if (bytes.isEmpty()) return true
        var off = 0
        while (off < bytes.size) {
            val n = try {
                Os.write(f, bytes, off, bytes.size - off)
            } catch (e: ErrnoException) {
                return false
            }
            if (n <= 0) return false
            off += n
        }
        return true
    }

    override fun isReady(): Boolean = fd != null

    override fun close() {
        fd?.let { runCatching { Os.close(it) } }
        fd = null
    }

    companion object {
        private const val TAG = "SerialDevice"
    }
}
