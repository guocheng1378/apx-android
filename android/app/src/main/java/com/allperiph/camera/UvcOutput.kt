package com.allperiph.camera

import android.util.Log

/**
 * V4L2 gadget 帧输出：打开 /dev/videoN → write JPEG 帧 → PC 通过 UVC 读取。
 *
 * JNI 实现在 uvc_output_jni.cpp，通过 libapx.so 一起编译。
 * 底层调用 V4L2 API（ioctl VIDIOC_QUERYCAP + write）与内核 f_uvc 通信。
 */
class UvcOutput {

    private var opened = false

    companion object {
        private const val TAG = "UvcOutput"

        init {
            try {
                System.loadLibrary("apx")
            } catch (t: Throwable) {
                Log.e(TAG, "libapx.so 加载失败: ${t.message}")
            }
        }
    }

    /**
     * 自动探测 f_uvc 创建的 V4L2 设备节点。
     * 遍历 /dev/video0..15，找到 driver 为 "uvcvideo" 的节点。
     * @return 设备路径（如 "/dev/video4"），未找到返回 null
     */
    fun findDevice(): String? {
        val path = nativeFindDevice()
        if (path != null) {
            Log.i(TAG, "找到 UVC 设备: $path")
        } else {
            Log.w(TAG, "未找到 uvcvideo 设备节点")
        }
        return path
    }

    /**
     * 打开 V4L2 设备节点。
     * @return true=成功，false=失败（见 logcat）
     */
    fun open(path: String): Boolean {
        if (opened) return true
        val rc = nativeOpen(path)
        if (rc == 0) {
            opened = true
            Log.i(TAG, "V4L2 设备已打开: $path")
        } else {
            Log.e(TAG, "V4L2 打开失败: rc=$rc")
        }
        return rc == 0
    }

    /**
     * 写入一帧 JPEG 数据。
     * @param jpegData JPEG 编码后的帧数据
     * @return true=写入成功，false=失败
     */
    fun writeFrame(jpegData: ByteArray): Boolean {
        if (!opened) return false
        val rc = nativeWriteFrame(jpegData)
        if (rc != 0) {
            Log.w(TAG, "writeFrame 失败: rc=$rc")
        }
        return rc == 0
    }

    /** 关闭 V4L2 设备节点 */
    fun close() {
        if (!opened) return
        nativeClose()
        opened = false
        Log.i(TAG, "V4L2 设备已关闭")
    }

    // ——— JNI 声明（实现在 uvc_output_jni.cpp）———

    private external fun nativeOpen(path: String): Int
    private external fun nativeFindDevice(): String?
    private external fun nativeWriteFrame(jpegData: ByteArray): Int
    private external fun nativeClose()
}
