package com.allperiph.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleMask
import com.allperiph.core.ModuleState

/**
 * M7 USB 摄像头模块：Camera2 → JPEG → V4L2 gadget → PC 免驱识别为 webcam。
 *
 * v1.35 修正：native V4L2 实现一直在 libapx.so 中
 * （apx_jni.cpp 的 camera.UvcOutput 段），此前注释误称缺失。
 * 真正的前置条件：GadgetFeature.UVC 需挂载（ConfigFsLayout 中默认关闭——
 * 本 ROM 的 configfs 软链会挂死内核，见 GadgetFeature.UVC 注释；支持的 ROM 上可开）。
 *
 * 原理：
 * - 内核 f_uvc 创建 V4L2 video 输出节点（/dev/videoN）
 * - Camera2 采集 JPEG 帧，write() 到 V4L2 节点
 * - PC 通过 USB UVC 协议读取，识别为标准摄像头（零驱动）
 *
 * 前置条件：
 * - [com.allperiph.gadget.GadgetManager] 挂载时 UVC feature 启用（GadgetFeature.UVC）
 *   注：v1.11 实测 UVC configfs 在真机不被内核接受，需先调通（dmesg 抓被拒环节）
 * - 内核已加载 usb_f_uvc 模块
 * - Camera2 API 可用（至少一个摄像头）+ CAMERA 权限已授予
 * - libapx.so 中含 uvc_output_jni 实现
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

        if (app.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            fail("需要「相机」权限：系统设置 → 应用 → 全能外设 → 权限 → 相机，授权后重开开关")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    app,
                    "摄像头需要「相机」权限：系统设置 → 应用 → 全能外设 → 权限 → 相机",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        try {
            uvcOutput = UvcOutput()
            val devicePath = uvcOutput!!.findDevice()
            if (devicePath == null) {
                fail("未找到 V4L2 设备节点（/dev/videoN）——UVC feature 未挂载")
                return
            }
            if (!uvcOutput!!.open(devicePath)) {
                fail("无法打开 $devicePath（V4L2 输出未实现）")
                return
            }
            Log.i(TAG, "V4L2 设备已打开: $devicePath")

            cameraThread = HandlerThread("apx-camera").also { it.start() }
            handler = Handler(cameraThread!!.looper)

            openCamera()
        } catch (t: Throwable) {
            fail(t.message ?: "unknown")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
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
        ModuleState.RUNNING -> "720p@30fps UVC 摄像头推流中"
        ModuleState.DEGRADED -> "摄像头降级：USB 2.0 带宽受限"
        ModuleState.ERROR -> "摄像头错误：V4L2 设备未就绪（详见日志）"
        else -> when (state) {
            ModuleState.STARTING -> "摄像头启动中"
            ModuleState.STOPPED, ModuleState.IDLE -> "摄像头已停止"
            else -> state.name
        }
    }

    /** §2.9 bit39 摄像头 */
    override fun maskBits(): Long = if (state.isActive) ModuleMask.CAMERA else 0L

    companion object {
        private const val TAG = "CameraModule"
    }
}