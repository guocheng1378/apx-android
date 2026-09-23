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
 * - libapx.so 中含 uvc_output_jni 实现（**目前缺**，运行时模块会 ERROR）
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

        // CAMERA 是运行时权限，且必须由 Activity 发起请求 —— 本模块无界面，
        // 授权入口在主页（打开主开关时弹出）。这里只做兜底检查 + 用户可见提示。
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
            // 1. 打开 V4L2 gadget 设备节点（native 未实现时这里会失败）
            uvcOutput = UvcOutput()
            val devicePath = uvcOutput!!.findDevice()
            if (devicePath == null) {
                fail("未找到 f_uvc V4L2 设备节点（/dev/videoN）——UVC feature 未挂载或 native 未实现")
                return
            }
            if (!uvcOutput!!.open(devicePath)) {
                fail("无法打开 $devicePath（native V4L2 输出未实现？见 shared/src/uvc_output_jni.cpp）")
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
        val cfg = CameraPrefs.load(app)

        // 前后摄按实验偏好选择（0=后置, 1=前置）
        val wantFront = cfg.facing == 1
        val cameraId = mgr.cameraIdList.firstOrNull { id ->
            val facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (wantFront) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull()

        if (cameraId == null) {
            fail("没有可用摄像头")
            return
        }

        val chars = mgr.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = chooseSize(map, cfg)

        Log.i(TAG, "Camera $cameraId: ${size.width}x${size.height} @${cfg.fps}fps " +
            if (wantFront) "(前置)" else "(后置)")
    }

    /** 按实验偏好选最接近的输出尺寸：精确命中 > 不超过目标的最大尺寸 > 任意最大尺寸 */
    private fun chooseSize(
        map: android.hardware.camera2.CameraCharacteristics.CameraCharacteristics?,
        cfg: CameraPrefs.Config,
    ): android.util.Size {
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        val target = android.util.Size(cfg.width, cfg.height)
        return sizes?.firstOrNull { it == target }
            ?: sizes?.filter { it.width <= target.width && it.height <= target.height }
                ?.maxByOrNull { it.width * it.height }
            ?: sizes?.maxByOrNull { it.width * it.height }
            ?: target
    }

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
                // 帧率按实验偏好
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfgFps(), cfgFps()))
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

    /** 帧率偏好（startPreview 用） */
    private fun cfgFps(): Int = CameraPrefs.load(app).fps

    override fun statusText(): String {
        val cfg = CameraPrefs.load(app)
        return when (state) {
            ModuleState.RUNNING -> "${cfg.width}x${cfg.height}@${cfg.fps}fps UVC streaming"
            ModuleState.DEGRADED -> "降级: USB 2.0 带宽受限"
            ModuleState.ERROR -> "error (见 log；多半是 native V4L2 未实现)"
            else -> state.name.lowercase()
        }
    }

    /** §2.9 bit39 摄像头（修正 v21 错误用的 bit 7） */
    override fun maskBits(): Long = if (state.isActive) ModuleMask.CAMERA else 0L

    companion object {
        private const val TAG = "CameraModule"
    }
}
