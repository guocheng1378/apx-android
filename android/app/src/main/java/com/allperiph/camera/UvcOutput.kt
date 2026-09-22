package com.allperiph.camera

import android.util.Log

/**
 * V4L2 gadget 帧输出：打开 /dev/videoN → write JPEG 帧 → PC 通过 UVC 读取。
 *
 * JNI 实现在 `android/app/src/main/cpp/apx_jni.cpp` 的 camera.UvcOutput 段
 * （APX_HAVE_POSIX_IO && APX_HAVE_V4L2_UAPI 条件编译），随 libapx.so 一起编译。
 * 底层调用 V4L2 API（QUERYCAP / S_FMT + write）与内核 f_uvc 通信。
 *
 * 本类的 try-catch 兜底保留：f_uvc 未挂载时 [findDevice] 返回 null，
 * [CameraModule] 照旧进 ERROR 而不是崩进程。
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
        return runCatching { nativeFindDevice() }.onFailure {
            Log.w(TAG, "nativeFindDevice 失败（native V4L2 未实现？）: ${it.message}")
        }.getOrNull()
    }

    /**
     * 打开 V4L2 设备节点。
     * @return true=成功，false=失败（见 logcat）
     */
    fun open(path: String): Boolean {
        if (opened) return true
        val rc = runCatching { nativeOpen(path) }.getOrDefault(-1)
        if (rc == 0) {
            opened = true
            Log.i(TAG, "V4L2 设备已打开: $path")
        } else {
            Log.e(TAG, "V4L2 打开失败: rc=$rc（native 未实现时此为正常现象）")
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
        val rc = runCatching { nativeWriteFrame(jpegData) }.getOrDefault(-1)
        if (rc != 0) {
            Log.w(TAG, "writeFrame 失败: rc=$rc")
        }
        return rc == 0
    }

    /** 关闭 V4L2 设备节点 */
    fun close() {
        if (!opened) return
        runCatching { nativeClose() }
        opened = false
        Log.i(TAG, "V4L2 设备已关闭")
    }

    // ——— JNI 声明（实现在 shared/src/uvc_output_jni.cpp，目前缺）——

    private external fun nativeOpen(path: String): Int
    private external fun nativeFindDevice(): String?
    private external fun nativeWriteFrame(jpegData: ByteArray): Int
    private external fun nativeClose()
}
