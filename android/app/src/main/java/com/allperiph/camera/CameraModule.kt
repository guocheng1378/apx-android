package com.allperiph.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import com.allperiph.core.ApxFrame
import com.allperiph.core.Log
import com.allperiph.core.MediaOut
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 摄像头模块（**Wi‑Fi 路线，H264 硬编**）：
 * Camera2 → YUV（Surface 直送）→ MediaCodec 硬编码 H264 → MediaOut（streamId=6 上行）。
 *
 * ## 为什么从 JPEG 改成 H264 硬编（真机实测踩坑）
 * 旧路线 Camera2 → ImageReader(JPEG)：相机的 JPEG 引擎编码大图**阻塞采集线程**，
 * 1MP@12fps 都能把 CPU 打满 → 摄像头卡 + **共用同一条媒体连接的副屏一起卡**。
 * 新路线走芯片 DSP 编码（Surface 零拷贝，CPU 趋近于零），帧率稳、发热降、副屏不受拖累。
 *
 * ## 协议
 * streamId=6 载荷从 JPEG 改为 **H264 AnnexB AccessUnit**（一 buffer 一帧）。
 * SPS/PPS 作为首包单独下发（flags=KEY_FRAME）；后续关键帧 flags=KEY_FRAME。
 * PC 端按载荷判别：FFD8 开头 = 旧版 JPEG，否则 H264（新旧版本互不崩）。
 */
class CameraModule(private val app: Context) : Module {

    override val id = ModuleId.CAMERA
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var handler: Handler? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    private val frames = java.util.concurrent.atomic.AtomicLong(0)
    private val sentFrames = java.util.concurrent.atomic.AtomicLong(0)
    private var ctxRef: ModuleContext? = null

    /** 参数（镜头/分辨率/帧率）变更后的热重启：运行中先停再按新配置拉起。 */
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

        val w = cfg.width
        val h = cfg.height
        Log.i(TAG, "Camera $cameraId: ${w}x${h} @${cfg.fps}fps H264硬编 " +
            if (wantFront) "(前置)" else "(后置)")

        // —— H264 硬编码器（Surface 输入：Camera2 直送，零拷贝零 CPU 转换）——
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)   // 2.5Mbps：1MP 内的预览足够清晰
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)   // 2s 一个关键帧
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        inputSurface = surface

        // —— H264 出站 drain 线程：一 buffer 一帧（AccessUnit）——
        draining = true
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var configSent = false
            while (draining) {
                val c = encoder ?: break
                val idx = try {
                    c.dequeueOutputBuffer(info, 20_000)
                } catch (t: Throwable) {
                    break   // codec 已释放（stop 竞态）
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    idx >= 0 -> {
                        val out = c.getOutputBuffer(idx)
                        if (out != null && info.size > 0) {
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val au = ByteArray(info.size)
                            out.get(au)
                            frames.incrementAndGet()
                            // SPS/PPS（codec config）必须先发：PC 端解码器初始化用
                            val ok = MediaOut.camera(
                                au,
                                if (isConfig || isKey) ApxFrame.FLAG_KEY_FRAME else 0
                            )
                            if (ok) sentFrames.incrementAndGet()
                            if (isConfig) configSent = true
                            if (isKey && !isConfig && configSent) { /* 关键帧正常流 */ }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                }
            }
        }, "apx-cam-drain").apply { isDaemon = true; start() }

        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(surface, cfg)
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

    private fun startPreview(surface: Surface, cfg: CameraPrefs.Config) {
        val camera = cameraDevice ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)   // 直送硬编输入 Surface（YUV 零拷贝）
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, handler)
                        state = ModuleState.RUNNING
                        Log.i(TAG, "Wi‑Fi 摄像头已启动: ${cfg.width}x${cfg.height}@${cfg.fps}fps H264硬编")
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
        draining = false
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        inputSurface?.release(); inputSurface = null
        drainThread?.join(500); drainThread = null
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
                "${cfg.width}x${cfg.height}@${cfg.fps}fps H264 · 已采 ${frames.get()} 帧 / 上行 ${sentFrames.get()}"
            ModuleState.ERROR -> "摄像头错误（见日志；常见为相机权限未授予）"
            else -> state.name.lowercase()
        }
    }

    /** 不占协议功能位（与副屏同）：能力由实际 H264 流体现 */
    override fun maskBits(): Long = 0L

    companion object {
        private const val TAG = "CameraModule"
    }
}
