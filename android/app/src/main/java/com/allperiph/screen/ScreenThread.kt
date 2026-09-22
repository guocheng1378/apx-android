package com.allperiph.screen

import android.content.Context
import android.graphics.PixelFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.*
import android.widget.FrameLayout
import com.allperiph.core.Log
import com.allperiph.core.ModuleContext
import com.allperiph.core.UsbBulkChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 副屏核心线程：从 USB bulk 读取 PC 视频帧 → 解码 → 渲染；采集触控 → 上报。
 *
 * 数据流（§3.3 Video Stream）：
 *   PC → Phone: APX1 帧头(16B) + H.264/H.265/MJPEG 编码帧
 *   Phone → PC: APX1 帧头(16B) + Digitizer HID 报告(33B)
 *
 * 线程模型：
 *   - 本线程：USB bulk 读循环（阻塞在 read() 上）
 *   - 主线程：SurfaceView 渲染 + 触控事件收集
 *   - 解码器：MediaCodec 异步模式在本线程回调
 */
class ScreenThread(
    private val app: Context,
    private val ctx: ModuleContext
) : HandlerThread("apx-screen") {

    private var surfaceView: SurfaceView? = null
    private var videoDecoder: VideoDecoder? = null
    private var touchCollector: TouchCollector? = null
    private var windowManager: WindowManager? = null
    private val running = AtomicBoolean(true)
    private var streamId = 0  // 复用 UsbBulkChannel streamId=0

    // 分辨率（PC 通过控制帧协商）
    @Volatile private var frameWidth = 1280
    @Volatile private var frameHeight = 720

    override fun start() {
        super.start()
        Handler(app.mainLooper).post { createOverlayWindow() }
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

    private fun createOverlayWindow() {
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        surfaceView = SurfaceView(app).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    initDecoder(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                    touchCollector = TouchCollector(w, h)
                    touchCollector?.onReport = { report -> sendTouchReport(report) }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    videoDecoder?.stop()
                    videoDecoder = null
                }
            })
            setOnTouchListener { _, event ->
                touchCollector?.onTouchEvent(event) ?: false
            }
        }

        windowManager?.addView(surfaceView, params)
    }

    private fun destroyOverlayWindow() {
        surfaceView?.let {
            runCatching { windowManager?.removeView(it) }
        }
        surfaceView = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    // ——————————— 解码器 ————————————

    private fun initDecoder(surface: android.view.Surface) {
        videoDecoder = VideoDecoder(frameWidth, frameHeight)
        videoDecoder!!.configure(surface)
        Log.i(TAG, "解码器已配置: ${frameWidth}x${frameHeight}")
    }

    // ——————————— USB bulk 读循环 ————————————

    private fun waitForSurfaceAndRun() {
        // 等待 Surface 就绪（最多 5 秒）
        var waitCount = 0
        while (running.get() && surfaceView?.holder?.surface == null && waitCount < 50) {
            Thread.sleep(100)
            waitCount++
        }
        if (!running.get()) return

        // 打开 USB bulk 通道
        UsbBulkChannel.setPaths(streamId, null, null)
        val openRc = UsbBulkChannel.open(streamId)
        if (openRc != 0) {
            Log.e(TAG, "USB bulk 打开失败: rc=$openRc")
            return
        }
        Log.i(TAG, "USB bulk 通道已打开，开始读帧")

        // APX1 帧头固定 16 字节
        val headerBuf = ByteArray(16)
        val readBuf = ByteArray(4 * 1024 * 1024)  // 4MB 读缓冲

        try {
            while (running.get()) {
                // 1. 读帧头（16 字节）
                val headerRead = readFull(headerBuf, 16)
                if (headerRead != 16) {
                    Log.w(TAG, "帧头读取不完整: $headerRead bytes")
                    continue
                }

                // 2. 校验 magic
                if (headerBuf[0] != 'A'.code.toByte() ||
                    headerBuf[1] != 'P'.code.toByte() ||
                    headerBuf[2] != 'X'.code.toByte() ||
                    headerBuf[3] != '1'.code.toByte()) {
                    Log.w(TAG, "帧 magic 错误，跳过")
                    continue
                }

                // 3. 解析 streamId 和 payloadLen（小端）
                val frameStreamId = headerBuf[4].toInt() and 0xFF
                val payloadLen = (headerBuf[8].toInt() and 0xFF) or
                    ((headerBuf[9].toInt() and 0xFF) shl 8) or
                    ((headerBuf[10].toInt() and 0xFF) shl 16) or
                    ((headerBuf[11].toInt() and 0xFF) shl 24)

                if (payloadLen <= 0 || payloadLen > readBuf.size) {
                    Log.w(TAG, "非法 payloadLen=$payloadLen")
                    continue
                }

                // 4. 读 payload
                val payloadRead = readFull(readBuf, payloadLen)
                if (payloadRead != payloadLen) {
                    Log.w(TAG, "payload 读取不完整: $payloadRead/$payloadLen")
                    continue
                }

                // 5. 按 streamId 分流
                when (frameStreamId) {
                    STREAM_VIDEO -> handleVideoFrame(readBuf, payloadLen)
                    STREAM_CTRL -> handleControlFrame(readBuf, payloadLen)
                    STREAM_TOUCH -> { /* 触控是上行，不处理下行 */ }
                    else -> { /* 未知 streamId，忽略 */ }
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
        // 检查是否为关键帧（flags bit0）
        val flags = data[5].toInt() and 0xFF  // 帧头 flags 字段（offset 5）
        // 关键帧时重置解码器（处理分辨率切换等场景）
        if (flags and 0x01 != 0) {
            // 关键帧：如果分辨率变了，重建解码器
            // （实际分辨率从帧头的 width/height 字段读取）
        }
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
                    // 重建解码器
                    Handler(app.mainLooper).post {
                        surfaceView?.holder?.surface?.let { surface ->
                            videoDecoder?.stop()
                            initDecoder(surface)
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

    // ——————————— 触控上报（上行）———————————

    private fun sendTouchReport(report: ByteArray): Boolean {
        if (!running.get() || !UsbBulkChannel.isOpen(streamId)) return false

        // 包装成 APX1 帧：header(16B) + payload
        val frame = ByteArray(16 + report.size)
        // magic: APX1
        frame[0] = 'A'.code.toByte()
        frame[1] = 'P'.code.toByte()
        frame[2] = 'X'.code.toByte()
        frame[3] = '1'.code.toByte()
        // streamId = kStreamTouch (2)
        frame[4] = STREAM_TOUCH.toByte()
        // flags = kFlagKeyFrame (0x01)
        frame[5] = 0x01
        // payloadLen (小端)
        val len = report.size
        frame[8] = (len and 0xFF).toByte()
        frame[9] = ((len shr 8) and 0xFF).toByte()
        frame[10] = ((len shr 16) and 0xFF).toByte()
        frame[11] = ((len shr 24) and 0xFF).toByte()
        // payload
        System.arraycopy(report, 0, frame, 16, report.size)

        val written = UsbBulkChannel.write(streamId, frame, 0, frame.size)
        return written == frame.size
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
        private const val STREAM_TOUCH = 2  // kStreamTouch
        private const val STREAM_CTRL = 3   // kStreamControl
        private const val CMD_RESOLUTION = 0x10
        private const val CMD_DISCONNECT = 0x7F
    }
}
