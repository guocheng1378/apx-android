package com.allperiph.screen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.allperiph.R
import com.allperiph.core.Log
import com.allperiph.core.ModuleContext
import com.allperiph.core.UsbBulkChannel
import com.allperiph.ui.NotificationChannels
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 副屏核心线程：从 USB bulk 读取 PC 视频帧 → MediaCodec 解码 → TextureView 渲染。
 *
 * v1.34（从 v21 移植，**剥离了触控上行**）：仅 PC → 手机单向显示，ep2 不再使用。
 *
 * **v1.34 真机修正 —— "打开开关后全屏黑屏"**：
 * 最初的实现用全屏 [SurfaceView] overlay，而 SurfaceView 在没有视频帧时默认渲染为
 * **黑色**，且窗口只加了 FLAG_NOT_FOCUSABLE（不挡按键但仍**消费触摸**）——
 * 用户一开主开关就得到一块点不动的全屏黑块。三处修正：
 *
 * 1. [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]：触摸穿透，overlay 纯显示不挡操作；
 * 2. SurfaceView → [TextureView]：TextureView 无内容时**完全透明**，
 *    待机期（PC 未推流）不再有黑块，首帧到达后自动出现画面；
 * 3. 启动时发一条常驻通知（CHANNEL_STATUS），点击打开应用 —— 用户有明确的"出口"
 *    去关闭副屏开关，而不是被黑屏困住。
 *
 * 数据流（PROTOCOL.md §3.1 Video Stream）：
 *   PC → Phone: APX1 帧头(16B) + 扩展头 + DirtyRect[] + 编码帧分片
 *
 * 线程模型：
 *   - 本线程（HandlerThread "apx-screen"）：USB bulk 读循环（阻塞在 read() 上）
 *   - 主线程：TextureView 创建 / 销毁 + 解码器重建
 *   - 解码器：MediaCodec 同步模式，在本线程 dequeueInputBuffer / dequeueOutputBuffer
 *
 * 前置条件：用户已授予 [Settings.canDrawOverlays]（SYSTEM_ALERT_WINDOW）。
 */
class ScreenThread(
    private val app: Context,
    @Suppress("UNUSED_PARAMETER") ctx: ModuleContext
) : HandlerThread("apx-screen") {

    private var textureView: TextureView? = null
    private var videoDecoder: VideoDecoder? = null
    private var windowManager: WindowManager? = null
    private val running = AtomicBoolean(true)
    private var streamId = 0  // 复用 UsbBulkChannel streamId=0

    // 分辨率（PC 通过控制帧协商；默认 720p）
    @Volatile private var frameWidth = 1280
    @Volatile private var frameHeight = 720

    override fun start() {
        super.start()
        // 分辨率按实验偏好（副屏解码器与 PC 协商都用这个值）
        val cfg = ScreenPrefs.load(app)
        frameWidth = cfg.width
        frameHeight = cfg.height
        Handler(app.mainLooper).post {
            if (createOverlayWindow()) postNotification()
        }
        // 等待 Surface 就绪后再开始读循环
        Thread({ waitForSurfaceAndRun() }, "apx-screen-read").start()
    }

    override fun quit(): Boolean {
        running.set(false)
        Handler(app.mainLooper).post { destroyOverlayWindow() }
        quitSafely()
        return super.quit()
    }

    fun statusText(): String {
        val connected = UsbBulkChannel.isOpen(streamId)
        return if (connected) "${frameWidth}x${frameHeight} connected" else "waiting for PC"
    }

    // ——————————— Overlay 窗口（主线程）———————————

    /** @return true = overlay 创建成功（据此决定是否发通知） */
    private fun createOverlayWindow(): Boolean {
        if (!Settings.canDrawOverlays(app)) {
            Log.e(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，无法显示副屏")
            // 用户可见：否则副屏"启动了却没画面"，只能靠 logcat 才发现原因
            Handler(app.mainLooper).post {
                android.widget.Toast.makeText(
                    app,
                    "副屏需要「显示在其他应用上层」权限：系统设置 → 应用 → 全能外设 → 开启后重开主开关",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            running.set(false)
            return false
        }
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 实验偏好：是否启用触控回送。开启时移除 FLAG_NOT_TOUCHABLE，
        // 让 overlay 捕获触摸并上行给 PC（手机当 PC 的触摸副屏）；否则触摸穿透到下方应用。
        val touchEnabled = ScreenPrefs.load(app).touch
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            if (touchEnabled) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        // TextureView 而非 SurfaceView：无视频帧时完全透明（SurfaceView 会渲染成黑块）
        textureView = TextureView(app).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    initDecoder(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    videoDecoder?.stop()
                    videoDecoder = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            if (touchEnabled) {
                // 触控回送：捕获触摸事件上行给 PC（见 onTouchListener）
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, ev -> sendTouch(ev); true }
            } else {
                // 纯显示：不可点击，配合 FLAG_NOT_TOUCHABLE 双保险
                isClickable = false
                isFocusable = false
            }
        }

        windowManager?.addView(textureView, params)
        Log.i(TAG, "副屏 overlay 已创建（待机透明，等待 PC 推流）")
        return true
    }

    private fun destroyOverlayWindow() {
        cancelNotification()
        textureView?.let {
            runCatching { windowManager?.removeView(it) }
        }
        textureView = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    // ——————————— 解码器 ————————————

    private fun initDecoder(surface: Surface) {
        videoDecoder = VideoDecoder(frameWidth, frameHeight)
        videoDecoder!!.configure(surface)
        Log.i(TAG, "解码器已配置: ${frameWidth}x${frameHeight}")
    }

    // ——————————— 待机通知（副屏的"出口"）———————————

    private fun postNotification() {
        NotificationChannels.ensure(app)
        runCatching {
            val pi = PendingIntent.getActivity(
                app, REQ_NOTIFY_CLICK,
                Intent(app, com.allperiph.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val n = android.app.Notification.Builder(app, NotificationChannels.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_apx_touchpad)
                .setContentTitle("副屏待机中")
                .setContentText("等待 PC 推流；点击打开应用，可关闭副屏开关")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            app.getSystemService(android.app.NotificationManager::class.java)?.notify(NOTIFY_ID, n)
        }.onFailure { Log.w(TAG, "副屏通知发送失败: ${it.message}") }
    }

    private fun cancelNotification() {
        runCatching {
            app.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIFY_ID)
        }
    }

    // ——————————— USB bulk 读循环 ————————————

    private fun waitForSurfaceAndRun() {
        // 等待 Surface 就绪（最多 5 秒）
        var waitCount = 0
        while (running.get() && textureView?.isAvailable != true && waitCount < 50) {
            Thread.sleep(100)
            waitCount++
        }
        if (!running.get()) return

        // 打开 USB bulk 通道
        UsbBulkChannel.setPaths(streamId, null, null)
        val openRc = UsbBulkChannel.open(streamId)
        if (openRc != 0) {
            Log.e(TAG, "USB bulk 打开失败: rc=$openRc（本机走 NCM/TCP 前此为预期失败）")
            return
        }
        Log.i(TAG, "USB bulk 通道已打开，开始读帧")

        // APX1 帧头固定 16 字节
        val headerBuf = ByteArray(16)
        val readBuf = ByteArray(4 * 1024 * 1024)  // 4MB 读缓冲

        try {
            while (running.get()) {
                val headerRead = readFull(headerBuf, 16)
                if (headerRead != 16) {
                    Log.w(TAG, "帧头读取不完整: $headerRead bytes")
                    continue
                }
                if (headerBuf[0] != 'A'.code.toByte() ||
                    headerBuf[1] != 'P'.code.toByte() ||
                    headerBuf[2] != 'X'.code.toByte() ||
                    headerBuf[3] != '1'.code.toByte()
                ) {
                    Log.w(TAG, "帧 magic 错误，跳过")
                    continue
                }

                val frameStreamId = headerBuf[4].toInt() and 0xFF
                val payloadLen = (headerBuf[8].toInt() and 0xFF) or
                    ((headerBuf[9].toInt() and 0xFF) shl 8) or
                    ((headerBuf[10].toInt() and 0xFF) shl 16) or
                    ((headerBuf[11].toInt() and 0xFF) shl 24)

                if (payloadLen <= 0 || payloadLen > readBuf.size) {
                    Log.w(TAG, "非法 payloadLen=$payloadLen")
                    continue
                }

                val payloadRead = readFull(readBuf, payloadLen)
                if (payloadRead != payloadLen) {
                    Log.w(TAG, "payload 读取不完整: $payloadRead/$payloadLen")
                    continue
                }

                when (frameStreamId) {
                    STREAM_VIDEO -> handleVideoFrame(readBuf, payloadLen)
                    STREAM_CTRL -> handleControlFrame(readBuf, payloadLen)
                    else -> { /* 未知 streamId（含 STREAM_TOUCH 上行），忽略 */ }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "读循环异常", e)
        } finally {
            UsbBulkChannel.close(streamId)
            Log.i(TAG, "USB bulk 通道已关闭")
        }
    }

    /** 将编码帧送入 MediaCodec 解码 */
    private fun handleVideoFrame(data: ByteArray, len: Int) {
        val decoder = videoDecoder ?: return
        decoder.decode(data, 0, len)
    }

    /** 处理控制帧（分辨率协商等） */
    private fun handleControlFrame(data: ByteArray, len: Int) {
        if (len < 1) return
        val cmd = data[0].toInt() and 0xFF
        when (cmd) {
            CMD_RESOLUTION -> {
                if (len >= 5) {
                    val w = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                    val h = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
                    Log.i(TAG, "PC 请求分辨率: ${w}x${h}")
                    frameWidth = w
                    frameHeight = h
                    Handler(app.mainLooper).post {
                        textureView?.let { tv ->
                            if (tv.isAvailable) {
                                videoDecoder?.stop()
                                initDecoder(Surface(tv.surfaceTexture))
                            }
                        }
                    }
                }
            }
            CMD_DISCONNECT -> {
                Log.i(TAG, "PC 请求断开")
                running.set(false)
            }
        }
    }

    // ——————————— 工具方法 ————————————

    /** 阻塞读取指定字节数 */
    private fun readFull(buf: ByteArray, needed: Int): Int {
        var offset = 0
        while (offset < needed) {
            val n = UsbBulkChannel.read(streamId, buf, needed - offset)
            if (n <= 0) return offset  // EOF 或错误
            offset += n
        }
        return offset
    }

    companion object {
        private const val TAG = "ScreenThread"
        private const val STREAM_VIDEO = 0  // kStreamVideo
        private const val STREAM_CTRL = 3   // kStreamControl
        private const val CMD_RESOLUTION = 0x10
        private const val CMD_DISCONNECT = 0x7F
        private const val REQ_NOTIFY_CLICK = 100
        private const val NOTIFY_ID = 2001
    }
}
