package com.allperiph.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * M7 USB 摄像头模块：Camera2 → MJPEG → V4L2 gadget → PC 免驱识别。
 *
 * 原理：
 * - 内核 f_uvc 创建 V4L2 video 输出节点（/dev/videoN）
 * - Camera2 采集 JPEG 帧，write() 到 V4L2 节点
 * - PC 通过 USB UVC 协议读取，识别为标准摄像头（零驱动）
 *
 * 前置条件：
 * - ConfigFsLayout 挂载时 UVC feature 启用（GadgetFeature.UVC）
 * - 内核已加载 usb_f_uvc 模块
 * - Camera2 API 可用（至少一个摄像头）
 */
class CameraModule(private val app: Context) : Module {

    override val id = ModuleId.CAMERA
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var uvcOutput: UvcOutput? = null
    private var cameraThread: HandlerThread? = null
    private var handler: Handler? = null

    @SuppressLint("MissingPermission")
    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING

        try {
            // 1. 打开 V4L2 gadget 设备节点
            uvcOutput = UvcOutput()
            val devicePath = uvcOutput!!.findDevice()
            if (devicePath == null) {
                fail("未找到 f_uvc V4L2 设备节点（/dev/videoN），请确认 UVC feature 已挂载")
                return
            }
            if (!uvcOutput!!.open(devicePath)) {
                fail("无法打开 $devicePath")
                return
            }
            Log.i(TAG, "V4L2 设备已打开: $devicePath")

            // 2. 启动 Camera2 后台线程
            cameraThread = HandlerThread("apx-camera").also { it.start() }
            handler = Handler(cameraThread!!.looper)

            // 3. 打开后置摄像头
            openCamera()
        } catch (t: Throwable) {
            fail(t.message ?: "unknown")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // 优先后置摄像头
        val cameraId = mgr.cameraIdList.firstOrNull { id ->
            val chars = mgr.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull()

        if (cameraId == null) {
            fail("没有可用摄像头")
            return
        }

        val chars = mgr.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.width * it.height }
            ?: android.util.Size(1280, 720)

        Log.i(TAG, "Camera $cameraId: ${size.width}x${size.height}")

        // JPEG 采集，队列深度 2（一帧采集一帧编码，流水线）
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    val ok = uvcOutput?.writeFrame(jpeg) == true
                    if (!ok) Log.w(TAG, "UVC 帧写入失败")
                } catch (e: Exception) {
                    Log.e(TAG, "帧处理异常", e)
                } finally {
                    image.close()
                }
            }, handler)
        }

        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                fail("摄像头已断开")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                fail("Camera2 错误: $error")
            }
        }, handler)
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val surface = imageReader?.surface ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                // 连续自动对焦
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                // 30fps
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                // JPEG 质量 85（质量与带宽的平衡点）
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, handler)
                        state = ModuleState.RUNNING
                        Log.i(TAG, "UVC 摄像头已启动: 720p@30fps MJPEG")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail("Camera2 会话配置失败")
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            fail("Camera2 访问异常: ${e.message}")
        }
    }

    override fun stop() {
        state = ModuleState.STOPPING
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        uvcOutput?.close(); uvcOutput = null
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null; handler = null
        state = ModuleState.STOPPED
        Log.i(TAG, "UVC 摄像头已停止")
    }

    private fun fail(reason: String) {
        state = ModuleState.ERROR
        Log.e(TAG, "error: $reason")
        runCatching { stop() }
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> "720p@30fps UVC streaming"
        ModuleState.DEGRADED -> "降级: USB 2.0 带宽受限"
        ModuleState.ERROR -> "error (see log)"
        else -> state.name.lowercase()
    }

    /** §2.9 位域 bit 7 = camera */
    override fun maskBits(): Long = if (state.isActive) (1L shl 7) else 0L

    companion object {
        private const val TAG = "CameraModule"
    }
}
