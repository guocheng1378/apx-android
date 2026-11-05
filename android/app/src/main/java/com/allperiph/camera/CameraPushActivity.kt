package com.allperiph.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.allperiph.core.MediaOut
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 摄像头推流页（M7-b，docs/spikes/m7-media-inbound-spike.md §2.3）。
 *
 * 链路：CameraX ImageAnalysis（YUV_420_888 / 720p / KEEP_ONLY_LATEST）
 *      → NV21 → framework YuvImage JPEG（q80）→ [MediaOut.camera]（streamId=6）。
 *
 * 取舍：
 *   · 走 ImageAnalysis + YuvImage 而非 ImageCapture：JPEG 编码全在 framework，
 *     CameraX 只管采集/生命周期/预览，第三方依赖面最小（build.gradle 例外注释）。
 *   · 节流 100ms（≈10fps）+ KEEP_ONLY_LATEST：慢机器自动丢旧帧，不积压。
 *   · 未连接（[MediaOut.ready] == false）不编码不发送，状态条如实计数 ——
 *     「调用方必须如实降级，不得假装已送达」（MediaOut.kt 纪律）。
 *   · 生命周期绑定：锁屏/离开页面相机由 CameraX 自动释放，无后台占用。
 *     PC 侧远程拉起 / 常驻采集是 M7-c 的事。
 *   · 权限申请走 Activity Result API（registerForActivityResult）而非
 *     override onRequestPermissionsResult —— 后者在 ComponentActivity 下签名
 *     编译不过（PR #162 CI 轮 1 实测），前者是官方推荐路径且无 override 问题。
 *   · 两处包名坑（PR #162 CI 轮 1/2 实测踩过，后来者照官方 reference 抄）：
 *     分辨率策略类在 `androidx.camera.core.resolutionselector` 子包（不在根包）；
 *     ActivityResultContracts 在 `androidx.activity.result.contract` 子包
 *     （不在 `androidx.activity.result` —— 那里放的是 ActivityResultCallback）。
 *
 * 启动（当前无应用内入口，M7-c 面板接）：
 *   adb shell am start -n com.allperiph/.camera.CameraPushActivity
 */
class CameraPushActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView

    private var provider: ProcessCameraProvider? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    // 权限结果回调（必须在 onCreate 前注册 —— 属性初始化器在构造期执行，满足约束）
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) {
            bindCamera()
        } else {
            note = "缺少摄像头权限 — 推流未启动（系统设置授权后重进本页）"
            render()
        }
    }

    // 全部只在分析线程上增改；render() 抛回主线程刷新 UI
    private var sent = 0
    private var paused = 0
    private var failed = 0
    private var lastBytes = 0
    private var lastAt = 0L

    @Volatile private var note = "初始化…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(36, 26, 36, 26)
            setBackgroundColor(0x99000000.toInt())
        }
        setContentView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        })
        note = "等待摄像头权限…"
        render()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            cameraPermission.launch(arrayOf(Manifest.permission.CAMERA))
        }
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
                                Size(TARGET_W, TARGET_H),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                    .build()
                    .apply { setAnalyzer(analyzerExecutor, ::onFrame) }
                val hasBack = runCatching {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }.getOrDefault(false)
                val selector = if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA
                               else CameraSelector.DEFAULT_FRONT_CAMERA
                p.unbindAll()
                p.bindToLifecycle(this, selector, preview, analysis)
                provider = p
                note = "采集已启动 — 等待 PC 连接"
                render()
            }.onFailure {
                note = "相机绑定失败：${it.message}"
                render()
            }
        }, mainExecutor)
    }

    /** 分析线程回调；[image] 必须关闭（KEEP_ONLY_LATEST 只留最新一帧）。 */
    private fun onFrame(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt < MIN_INTERVAL_MS) return          // ≈10fps 节流
            if (!MediaOut.ready()) {
                paused++
                note = "未连接 — 暂停发送（预览保持）"
                render()
                return   // 不消耗 lastAt：恢复连接后下一帧立即发
            }
            lastAt = now
            runCatching {
                val nv21 = yuv420ToNv21(it)
                val yuv = YuvImage(nv21, ImageFormat.NV21, it.width, it.height, null)
                val bos = ByteArrayOutputStream(384 * 1024)
                if (!yuv.compressToJpeg(Rect(0, 0, it.width, it.height), JPEG_QUALITY, bos)) {
                    failed++
                    note = "JPEG 编码失败"
                    render()
                    return
                }
                val bytes = bos.toByteArray()
                if (MediaOut.camera(bytes)) {
                    sent++
                    lastBytes = bytes.size
                    if (note != "已连接 — 推流中") {
                        note = "已连接 — 推流中"
                    }
                } else {
                    failed++
                    note = "入队失败（MediaOut 出口不可用）"
                }
                render()
            }.onFailure {
                failed++
                note = "采集异常：${it.message}"
                render()
            }
        }
    }

    /** YUV_420_888 → NV21（Y 平面 + VU 交错）。按各平面 stride 容忍 rowStride > width。 */
    private fun yuv420ToNv21(img: ImageProxy): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]
        var pos = 0
        val yb = y.buffer
        for (row in 0 until h) {
            val base = row * y.rowStride
            if (y.pixelStride == 1) {
                yb.position(base)
                yb.get(out, pos, w)
                pos += w
            } else {
                for (col in 0 until w) out[pos++] = yb.get(base + col * y.pixelStride)
            }
        }
        val cw = w / 2
        val ch = h / 2
        val ub = u.buffer
        val vb = v.buffer
        for (row in 0 until ch) {
            val uBase = row * u.rowStride
            val vBase = row * v.rowStride
            for (col in 0 until cw) {
                out[pos++] = vb.get(vBase + col * v.pixelStride)   // NV21：V 先
                out[pos++] = ub.get(uBase + col * u.pixelStride)
            }
        }
        return out
    }

    private fun render() {
        val text = "$note\n已发送 $sent · 未连接暂停 $paused · 失败 $failed · 单帧 ${lastBytes / 1024}KB"
        runOnUiThread { statusView.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        provider?.unbindAll()
        analyzerExecutor.shutdown()
    }

    private companion object {
        // 档位（720p / q80 / ≈10fps）：spike §2.3 首版默认；
        // config/面板档位是 M7-c 的活，先不引入手机侧 config 读取（仓内无先例）。
        const val TARGET_W = 1280
        const val TARGET_H = 720
        const val JPEG_QUALITY = 80
        const val MIN_INTERVAL_MS = 100L
    }
}
