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
import com.allperiph.core.MediaOut
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleMask
import com.allperiph.core.ModuleState

/**
 * 摄像头模块（**Wi‑Fi 路线**）：Camera2 → JPEG → MediaOut.camera()（streamId=6 上行）。
 *
 * 与旧版（`app/src/disabled/camera/`，USB UVC 路线）的区别：旧版把 JPEG 写进
 * V4L2 gadget 让 PC 免驱识别为 USB 摄像头 —— 本 ROM 的 configfs 软链会挂死内核
 * （GadgetFeature.UVC 默认关），该路线已被否。本版经媒体通道上行 JPEG，
 * PC 端解码显示/再桥接成虚拟摄像头（见 pc/host 的 camera 处理）。
 *
 * 带宽账：720p JPEG（质量 85）≈ 60–120KB/帧，20fps ≈ 1.2–2.4MB/s —— 局域网无压力。
 */
class CameraModule(private val app: Context) : Module {

    override val id = ModuleId.CAMERA
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var handler: Handler? = null
    private val frames = java.util.concurrent.atomic.AtomicLong(0)
    private val sentFrames = java.util.concurrent.atomic.AtomicLong(0)
    private var ctxRef: ModuleContext? = null

    /**
     * 参数（镜头/分辨率/帧率）变更后的热重启：运行中先停再按新配置拉起。
     * 在后台线程执行，UI 可直接调用。
     */
    fun hotRestart() {
        val ctx = ctxRef ?: return
        if (!state.isActive) return
        kotlin.concurrent.thread(start = true, name = "apx-cam-restart") {
            runCatching { stop() }
            runCatching { start(ctx) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING
        ctxRef = ctx

        // CAMERA 是运行时权限，必须由 Activity 发起请求（主页已随启动批量申请）。
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
        if (!MediaOut.ready()) {
            Log.i(TAG, "媒体通道未连入：摄像头先开采集，PC 连上后即出画面")
        }

        try {
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
        val cfg = CameraPrefs.load(app)

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

        // JPEG 采集，队列深度 2（一帧采集一帧上行，流水线）
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    frames.incrementAndGet()
                    if (MediaOut.camera(jpeg)) sentFrames.incrementAndGet()
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
                startPreview(size, cfg)
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

    private fun startPreview(size: android.util.Size, cfg: CameraPrefs.Config) {
        val camera = cameraDevice ?: return
        val surface = imageReader?.surface ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
                // JPEG 质量 72：与副屏视频共带宽的折中（85 一张 ~110KB，72 ~65KB）
                set(CaptureRequest.JPEG_QUALITY, 72.toByte())
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, handler)
                        state = ModuleState.RUNNING
                        Log.i(TAG, "Wi‑Fi 摄像头已启动: ${size.width}x${size.height}@${cfg.fps}fps JPEG")
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

    /**
     * 按偏好选最接近的输出尺寸：精确命中 > 同比例不超过目标的最大 > **面积最接近目标**。
     * 旧兜底是「任意最大尺寸」—— 部分机型 JPEG 档位稀疏（只有 4:3 大尺寸）时
     * 会选中 4096x3072，4K JPEG@20fps 直接把手机 CPU 和带宽打满（副屏跟着卡）。
     */
    private fun chooseSize(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
        cfg: CameraPrefs.Config,
    ): android.util.Size {
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList()
        val target = android.util.Size(cfg.width, cfg.height)
        if (sizes.isNullOrEmpty()) return target
        val tr = target.width.toDouble() / target.height
        val sameRatio = sizes.filter {
            kotlin.math.abs(it.width.toDouble() / it.height - tr) < 0.12
        }
        sameRatio.filter { it.width <= target.width && it.height <= target.height }
            .maxByOrNull { it.width * it.height }
            ?.let { return it }
        sameRatio.minByOrNull { it.width * it.height }?.let { return it }
        // 比例都对不上（机型怪异）：选**总像素最接近目标**的 —— 负载可控才是硬道理
        val targetPx = target.width.toLong() * target.height
        return sizes.minByOrNull {
            kotlin.math.abs(it.width.toLong() * it.height - targetPx)
        } ?: target
    }

    override fun stop() {
        state = ModuleState.STOPPING
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null; handler = null
        state = ModuleState.STOPPED
        Log.i(TAG, "Wi‑Fi 摄像头已停止")
    }

    private fun fail(reason: String) {
        state = ModuleState.ERROR
        Log.e(TAG, "error: $reason")
        runCatching { stop() }
    }

    override fun statusText(): String {
        val cfg = CameraPrefs.load(app)
        return when (state) {
            ModuleState.RUNNING ->
                "${cfg.width}x${cfg.height}@${cfg.fps}fps · 已采 ${frames.get()} 帧 / 上行 ${sentFrames.get()}"
            ModuleState.ERROR -> "摄像头错误（见日志；常见为相机权限未授予）"
            else -> state.name.lowercase()
        }
    }

    /** 不占协议功能位（与副屏同）：能力由实际 JPEG 流体现 */
    override fun maskBits(): Long = 0L

    companion object {
        private const val TAG = "CameraModule"
    }
}
