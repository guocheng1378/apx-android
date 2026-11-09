package com.allperiph.screenmirror

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.allperiph.R
import com.allperiph.core.MediaOut
import com.allperiph.core.ScreenMirrorRequestEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.shared.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕采集前台服务（投屏到电脑）：MediaProjection → VirtualDisplay + ImageReader
 * （RGBA_8888）→ Bitmap → 等比缩放至宽约 720 → JPEG q65 → [MediaOut.camera]（streamId=6）。
 *
 * 与摄像头推流共用 stream 6：PC 端无需新增解复用，预览窗直接复用。
 * 取舍：
 *   · Android 14（targetSdk 34）强制：使用 MediaProjection 前必须先启动
 *     mediaProjection 类型前台服务，并先 registerCallback 再 createVirtualDisplay。
 *   · 节流 100ms（≈10fps）+ acquireLatestImage：只取最新帧，慢机器不积压。
 *   · 未连接（MediaOut.ready() == false）不编码不发送，如实降级。
 *   · off 路径：订阅 [ScreenMirrorRequestEvent]，PC 关开关时自动停止；
 *     通知「停止」动作 / 系统侧停止（registerCallback）同样走 stopMirror。
 */
class ScreenMirrorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val running = AtomicBoolean(false)

    private var closeDisposable: EventBus.Disposable? = null

    private var sent = 0
    private var failed = 0
    private var lastBytes = 0
    private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        closeDisposable = EventBus.on<ScreenMirrorRequestEvent>(mainHandler) {
            if (!it.on) stopMirror()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirror()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "缺少授权参数，屏幕采集无法启动")
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+：必须先以 mediaProjection 类型进入前台，再创建 MediaProjection
        startForeground(
            NOTIFY_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException("MediaProjectionManager 不可用")
            val mp = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection 获取失败")
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "系统侧停止屏幕采集")
                    stopMirror()
                }
            }
            mp.registerCallback(cb, mainHandler)
            projectionCallback = cb
            projection = mp

            val wm = getSystemService(WindowManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val srcW = metrics.widthPixels
            val srcH = metrics.heightPixels
            val scale = TARGET_W.toFloat() / maxOf(srcW, srcH)
            val outW = (srcW * scale).toInt().coerceAtLeast(2)
            val outH = (srcH * scale).toInt().coerceAtLeast(2)

            val thread = HandlerThread("apx-screenmirror").apply { start() }
            captureThread = thread
            val reader = ImageReader.newInstance(srcW, srcH, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r -> onImage(r, outW, outH) }, Handler(thread.looper))
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "apx-screenmirror", srcW, srcH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            )
            running.set(true)
        }.onFailure {
            Log.w(TAG, "屏幕采集启动失败：${it.message}")
            stopMirror()
        }
    }

    /** 采集线程回调；[image] 必须关闭。 */
    private fun onImage(reader: ImageReader, outW: Int, outH: Int) {
        val image: Image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt < MIN_INTERVAL_MS) return   // ≈10fps 节流
            if (!MediaOut.ready()) {
                return   // 不消耗 lastAt：恢复连接后下一帧立即发
            }
            lastAt = now
            runCatching {
                val bitmap = imageToBitmap(image)
                val scaled = if (bitmap.width != outW || bitmap.height != outH) {
                    Bitmap.createScaledBitmap(bitmap, outW, outH, true)
                } else bitmap
                val bos = ByteArrayOutputStream(256 * 1024)
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) {
                    failed++
                    return
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                val bytes = bos.toByteArray()
                if (MediaOut.camera(bytes)) {
                    sent++
                    lastBytes = bytes.size
                } else {
                    failed++
                }
            }.onFailure {
                failed++
                Log.w(TAG, "屏幕帧编码失败：${it.message}")
            }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (pixelStride == 4 && rowStride == w * 4) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            return bmp
        }
        // rowStride 与宽度不一致时逐行拷贝
        val dense = java.nio.ByteBuffer.allocate(w * 4 * h)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.limit(row * rowStride + w * 4)
            dense.put(buffer.slice())
        }
        buffer.clear()
        dense.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(dense)
        return bmp
    }

    private fun stopMirror() {
        if (!running.compareAndSet(true, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
            projectionCallback = null
            projection?.stop()
            projection = null
            captureThread?.quitSafely()
            captureThread = null
        }.onFailure { Log.w(TAG, "屏幕采集清理异常：${it.message}") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "屏幕采集已停止：sent=$sent failed=$failed")
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            R.drawable.ic_stat_peripheral,
            getString(R.string.notify_action_stop),
            stopIntent,
        ).build()
        return Notification.Builder(this, "apx_status")
            .setSmallIcon(R.drawable.ic_stat_peripheral)
            .setContentTitle(getString(R.string.sm_notify_title))
            .setContentText(getString(R.string.sm_notify_text))
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeDisposable?.dispose()
        stopMirror()
    }

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val NOTIFY_ID = 0x9A3

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val ACTION_STOP = "com.allperiph.action.STOP_SCREEN_MIRROR"

        // 档位（宽 720 / q65 / ≈10fps）：与摄像头推流同量级，复用 stream 6 的带宽预算
        const val TARGET_W = 720
        const val JPEG_QUALITY = 65
        const val MIN_INTERVAL_MS = 100L
    }
}
