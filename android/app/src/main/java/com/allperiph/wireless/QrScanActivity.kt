package com.allperiph.wireless

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatioStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionStrategy
import androidx.camera.core.PreviewView
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.allperiph.R
import com.allperiph.shared.net.PairQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * 扫码配对页（2026-11-26 TV 配对批）：对准 TV 配对页的二维码扫一下，
 * 解出 `allperf://<ip>:<port>?c=<6位码>`（[PairQr.decode]）回传给调用方连接。
 *
 * 链路与 [com.allperiph.camera.CameraPushActivity] 同款：CameraX ImageAnalysis
 * （YUV_420_888）+ 后台分析线程；解码用 zxing（shared 已依赖，零依赖原则的既定例外），
 * 只取 Y 平面亮度喂 [PlanarYUVLuminanceSource]（二维码只需要亮度，不必转 RGB）。
 * 识别成功即 setResult + finish，不预览结果不二次确认 —— 配对码本身就是短命凭据。
 */
class QrScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private var provider: ProcessCameraProvider? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    /** 权限结果回调（必须在 onCreate 前注册 —— 属性初始化器在构造期执行，满足约束） */
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) {
            bindCamera()
        } else {
            note(getString(R.string.ui_touchpad_scan_no_camera))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 26, 48, 26)
            setBackgroundColor(0x99000000.toInt())
        }
        setContentView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            note(getString(R.string.ui_touchpad_scan_wait_camera))
            cameraPermission.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun note(t: String) {
        runOnUiThread { statusView.text = t }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val p = future.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                    .build()
                    .apply { setAnalyzer(analyzerExecutor, ::onFrame) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                provider = p
                note(getString(R.string.ui_touchpad_scan_hint))
            }.onFailure {
                note(getString(R.string.ui_touchpad_scan_camera_failed, it.message ?: ""))
            }
        }, mainExecutor)
    }

    /** 分析线程回调；[image] 必须关闭（KEEP_ONLY_LATEST 只留最新一帧）。≈10fps 节流。 */
    private var lastAt = 0L
    private fun onFrame(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt < 100) return
            lastAt = now
            val hit = runCatching { decodeQr(it) }.getOrNull()
            if (hit == null) return   // 没扫到：下一帧再来，不刷 UI（避免每帧重绘提示）
            val (ip, port, code) = hit
            note(getString(R.string.ui_touchpad_scan_found, ip))
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_CODE, code)
            })
            finish()
        }
    }

    /**
     * 单帧解码：Y 平面按行拷成紧凑缓冲（容忍 rowStride > width）→ 按传感器朝向
     * 转正 → zxing QRCodeReader。扫不到抛 NotFoundException，由调用方 runCatching 吞掉。
     */
    private fun decodeQr(img: ImageProxy): Triple<String, Int, String>? {
        val y = img.planes[0]
        val w = img.width
        val h = img.height
        val packed = ByteArray(w * h)
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(packed, row * w, w)
        }
        // 传感器朝向转正（竖持手机常见 90°）：旋转后宽高互换
        val (data, dw, dh) = when (img.imageInfo.rotationDegrees) {
            90 -> rotate(packed, w, h, cw = true)
            270 -> rotate(packed, w, h, cw = false)
            else -> Triple(packed, w, h)
        }
        val source = PlanarYUVLuminanceSource(data, dw, dh, 0, 0, dw, dh, false)
        val result = QRCodeReader().decode(
            BinaryBitmap(source),
            mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true),
        )
        return PairQr.decode(result.text)
    }

    /** 亮度矩阵旋转 90°（cw=true 顺时针 / false 逆时针），返回 (新数据, 新宽, 新高) */
    private fun rotate(src: ByteArray, w: Int, h: Int, cw: Boolean): Triple<ByteArray, Int, Int> {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = src[row * w + col]
                // 顺时针：新图 (h-1-row, col)；逆时针：新图 (col, w-1-row) —— 行主序展开
                val ni = if (cw) col * h + (h - 1 - row) else (w - 1 - col) * h + row
                out[ni] = v
            }
        }
        return Triple(out, h, w)
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        provider?.unbindAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
    }
}
