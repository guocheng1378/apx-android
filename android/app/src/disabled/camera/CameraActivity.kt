package com.allperiph.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import com.allperiph.R
import com.allperiph.core.Log

/**
 * 摄像头实验页：手机端实时预览 + 前后摄翻转 + 分辨率/帧率选择。
 *
 * 存在意义：原生 UVC 输出（f_uvc V4L2）在本 ROM 实测不可用（见 [CameraModule] 注释），
 * 之前「开摄像头」在手机上完全看不到画面、也无法确认采集是否正常。本页把 Camera2 直接
 * 渲染到 TextureView，让换手机实验时能**立即肉眼验证**前后摄/分辨率/帧率是否生效，
 * 参数通过 [CameraPrefs] 持久化，[CameraModule] 推流时也读同一份。
 */
class CameraActivity : Activity() {

    private lateinit var textureView: TextureView
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val resolutions = listOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))
    private val fpsOptions = listOf(24, 30, 60)

    private val REQ_CAM = 9001

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_camera)
        textureView = findViewById(R.id.tvPreview)
        spRes = findViewById(R.id.spRes)
        spFps = findViewById(R.id.spFps)

        spRes.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            resolutions.map { "${it.width}x${it.height}" },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spFps.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            fpsOptions.map { "${it}fps" },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val cfg = CameraPrefs.load(this)
        spRes.setSelection(maxOf(0, resolutions.indexOfFirst { it.width == cfg.width && it.height == cfg.height }))
        spFps.setSelection(maxOf(0, fpsOptions.indexOf(cfg.fps)))

        findViewById<Button>(R.id.btnFlip).setOnClickListener { flip() }
        spRes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, i: Int, id: Long) = onParamChanged()
            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }
        spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, i: Int, id: Long) = onParamChanged()
            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    /** 前后摄翻转：存偏好 → 重开相机 */
    private fun flip() {
        val cfg = CameraPrefs.load(this)
        val next = if (cfg.facing == 0) 1 else 0
        CameraPrefs.save(this, cfg.copy(facing = next))
        toast(if (next == 0) "已切到后置" else "已切到前置")
        closeCamera()
        openCamera()
    }

    /** 分辨率/帧率改变：存偏好 → 用新尺寸重开预览 */
    private fun onParamChanged() {
        if (!textureView.isAvailable) return
        val cfg = CameraPrefs.load(this)
        val size = resolutions.getOrNull(spRes.selectedItemPosition) ?: Size(cfg.width, cfg.height)
        val fps = fpsOptions.getOrNull(spFps.selectedItemPosition) ?: cfg.fps
        CameraPrefs.save(this, cfg.copy(width = size.width, height = size.height, fps = fps))
        closeCamera()
        openCamera()
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!textureView.isAvailable) return
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val cfg = CameraPrefs.load(this)
        val id = mgr.cameraIdList.firstOrNull { cid ->
            val facing = mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING)
            if (cfg.facing == 1) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val size = pickSize(mgr, id, cfg)
        thread = HandlerThread("cam-exp").also { it.start() }
        handler = Handler(thread!!.looper)
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(size)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                toast("摄像头错误: $error")
            }
        }, handler)
    }

    private fun pickSize(mgr: CameraManager, id: String, cfg: CameraPrefs.Config): Size {
        val chars = mgr.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?: map?.getOutputSizes(ImageFormat.JPEG)
        val target = Size(cfg.width, cfg.height)
        return sizes?.firstOrNull { it == target }
            ?: sizes?.filter { it.width <= target.width && it.height <= target.height }
                ?.maxByOrNull { it.width * it.height }
            ?: sizes?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun startPreview(size: Size) {
        val camera = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
        val cfg = CameraPrefs.load(this)
        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
        }
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                runCatching { s.setRepeatingRequest(req.build(), null, handler) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = toast("预览配置失败")
        }, handler)
    }

    private fun closeCamera() {
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        thread?.quitSafely()
        runCatching { thread?.join(500) }
        thread = null; handler = null
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_CAM) {
            if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
            else toast("需要相机权限才能预览")
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    private fun toast(m: String) {
        runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
        Log.i(TAG, m)
    }

    companion object {
        private const val TAG = "CameraActivity"
    }
}
