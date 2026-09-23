package com.allperiph.screen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
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
 * 优化 #6：4MB 读缓冲延迟分配——USB bulk 打开成功后再创建，
 * quit() 时置 null 释放内存。避免副屏未激活时常驻 4MB。
 */
class ScreenThread(
    private val app: Context,
    @Suppress("UNUSED_PARAMETER") ctx: ModuleContext
) : HandlerThread("apx-screen") {

    private var textureView: TextureView? = null
    private var videoDecoder: VideoDecoder? = null
    private var windowManager: WindowManager? = null
    private val running = AtomicBoolean(true)
    private var streamId = 0

    @Volatile private var frameWidth = 1280
    @Volatile private var frameHeight = 720

    override fun start() {
        super.start()
        Handler(app.mainLooper).post {
            if (createOverlayWindow()) postNotification()
        }
        Thread({ waitForSurfaceAndRun() }, "apx-screen-read").start()
    }

    override fun quit(): Boolean {
        running.set(false)
        Handler(app.mainLooper).post { destroyOverlayWindow() }
        readBuf = null  // 优化 #6：释放 4MB 缓冲
        quitSafely()
        return super.quit()
    }

    fun statusText(): String {
        val connected = UsbBulkChannel.isOpen(streamId)
        return if (connected) "${frameWidth}x${frameHeight} connected" else "waiting for PC"
    }

    // ——————————— Overlay 窗口（主线程）———————————

    private fun createOverlayWindow(): Boolean {
        if (!Settings.canDrawOverlays(app)) {
            Log.e(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，无法显示副屏")
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

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
            isClickable = false
            isFocusable = false
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

    // ——————————— 待机通知 ————————————

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

    // 优化 #6：延迟分配——只在 USB bulk 真正打开后才创建 4MB 缓冲
    private var readBuf: ByteArray? = null

    private fun waitForSurfaceAndRun() {
        var waitCount = 0
        while (running.get() && textureView?.isAvailable != true && waitCount < 50) {
            Thread.sleep(100)
            waitCount++
        }
        if (!running.get()) return

        UsbBulkChannel.setPaths(streamId, null, null)
        val openRc = UsbBulkChannel.open(streamId)
        if (openRc != 0) {
            Log.e(TAG, "USB bulk 打开失败: rc=$openRc（本机走 NCM/TCP 前此为预期失败）")
            return
        }
        Log.i(TAG, "USB bulk 通道已打开，开始读帧")

        val headerBuf = ByteArray(16)
        readBuf = ByteArray(4 * 1024 * 1024)  // 优化 #6：此时才分配

        try {
            while (running.get()) {
                val buf = readBuf ?: break
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

                if (payloadLen <= 0 || payloadLen > buf.size) {
                    Log.w(TAG, "非法 payloadLen=$payloadLen")
                    continue
                }

                val payloadRead = readFull(buf, payloadLen)
                if (payloadRead != payloadLen) {
                    Log.w(TAG, "payload 读取不完整: $payloadRead/$payloadLen")
                    continue
                }

                when (frameStreamId) {
                    STREAM_VIDEO -> handleVideoFrame(buf, payloadLen)
                    STREAM_CTRL -> handleControlFrame(buf, payloadLen)
                    else -> { /* 未知 streamId */ }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "读循环异常", e)
        } finally {
            UsbBulkChannel.close(streamId)
            readBuf = null  // 优化 #6：关闭时释放
            Log.i(TAG, "USB bulk 通道已关闭")
        }
    }

    private fun handleVideoFrame(data: ByteArray, len: Int) {
        val decoder = videoDecoder ?: return
        decoder.decode(data, 0, len)
    }

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

    private fun readFull(buf: ByteArray, needed: Int): Int {
        var offset = 0
        while (offset < needed) {
            val n = UsbBulkChannel.read(streamId, buf, needed - offset)
            if (n <= 0) return offset
            offset += n
        }
        return offset
    }

    companion object {
        private const val TAG = "ScreenThread"
        private const val STREAM_VIDEO = 0
        private const val STREAM_CTRL = 3
        private const val CMD_RESOLUTION = 0x10
        private const val CMD_DISCONNECT = 0x7F
        private const val REQ_NOTIFY_CLICK = 100
        private const val NOTIFY_ID = 2001
    }
}