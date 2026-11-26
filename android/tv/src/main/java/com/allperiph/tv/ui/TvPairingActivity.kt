package com.allperiph.tv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.net.PairQr
import com.allperiph.shared.net.PairingCode
import com.allperiph.tv.TvServerService

/**
 * TV 端「配对」页（2026-11-26 TV 配对批，用户拍板方向①）。
 *
 * 全屏亮两样东西，控制端（手机/PC）任取其一完成首连：
 *  · **配对二维码**：内容 `allperf://<ip>:<port>?c=<6位码>`（[PairQr]），手机端
 *    「扫码连接」一扫即连 —— 遥控器逐位输码在电视上不现实，扫码是主路径；
 *  · **大号 6 位码**：给 PC 端手输（PC 面板连接卡已有配对码输入口，v223.38 落地）。
 *
 * 码的生命周期与手机端配对码页同口径（[PairingCode]）：10 分钟有效、连错 5 次作废、
 * 进程内存态不落盘。首连成功后被控端经 0x36 把持久令牌发给对端落盘，之后免输。
 * 本页只展示凭据，不做任何网络动作 —— 握手校验全在 ControlServer。
 */
class TvPairingActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每秒刷新倒计时；码过期自动换新（电视场景没人守着点「重新生成」） */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            refreshCode()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var codeView: TextView
    private lateinit var leftView: TextView
    private lateinit var qrView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        PairingCode.newCode()   // 进页面即生成（旧码作废，失败计数清零）
        setContentView(buildUi())
        refreshCode()
    }

    override fun onResume() { super.onResume(); mainHandler.post(tick) }
    override fun onPause() { super.onPause(); mainHandler.removeCallbacks(tick) }

    /** 退出即作废：配对码本来就是「当面交付」的短命凭据，页面关了留着没意义 */
    override fun finish() {
        super.finish()
        PairingCode.consume()
    }

    private fun buildUi(): LinearLayout {
        val pad = TvUi.dp(this, 40f)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setBackgroundColor(TvUi.Pal.bg)
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = getString(com.allperiph.tv.R.string.tv_pairing_title)
            TvUi.applyTextSize(this, TvUi.Type.TITLE)
            setTextColor(TvUi.Pal.text)
        }
        codeView = TextView(this).apply {
            // 6 位码是本页主角：字号随分辨率缩放（1080p 基准 96sp）
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 96f * TvUi.scale(this@TvPairingActivity))
            setTextColor(TvUi.Pal.accent)
            letterSpacing = 0.15f
        }
        leftView = TextView(this).apply {
            TvUi.applyTextSize(this, TvUi.Type.CAPTION)
            setTextColor(TvUi.Pal.textDim)
        }
        qrView = ImageView(this).apply {
            // 二维码取屏幕短边的 60%，保证 3 米外手机相机对得上焦
            val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.6f).toInt()
            layoutParams = LinearLayout.LayoutParams(side, side).apply { topMargin = pad }
        }
        val hint = TextView(this).apply {
            text = getString(com.allperiph.tv.R.string.tv_pairing_hint)
            TvUi.applyTextSize(this, TvUi.Type.CAPTION)
            setTextColor(TvUi.Pal.textDim)
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        content.addView(title)
        content.addView(codeView)
        content.addView(leftView)
        content.addView(qrView)
        content.addView(hint)
        return content
    }

    /** 刷新码与倒计时；码过期/被用掉自动换新，二维码随之重画 */
    private fun refreshCode() {
        var code = PairingCode.current()
        if (code == null) {
            code = PairingCode.newCode()
            redrawQr(code)
        } else if (code != shownCode) {
            redrawQr(code)
        }
        shownCode = code
        codeView.text = code
        val left = PairingCode.remainingSeconds()
        leftView.text = if (left > 0) getString(com.allperiph.tv.R.string.tv_pairing_expire, left / 60, left % 60)
                        else getString(com.allperiph.tv.R.string.tv_pairing_expired)
    }

    /** 本机 IPv4（与 ControlServer 启动日志同口径）：二维码里的连接地址 */
    private fun localIpv4(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: ""
    } catch (_: Throwable) { "" }

    private fun redrawQr(code: String) {
        val ip = localIpv4()
        val bmp: Bitmap? = if (ip.isEmpty()) null
        else PairQr.makeBitmap(PairQr.encode(ip, ControlServer.PORT, code), qrView.layoutParams.width.coerceAtLeast(200))
        if (bmp != null) {
            qrView.setImageBitmap(bmp)
            qrView.visibility = android.view.View.VISIBLE
        } else {
            // 取不到本机地址（罕见：网络未就绪）——降级只展示 6 位码，下一秒重试会重画
            qrView.visibility = android.view.View.INVISIBLE
        }
    }

    /** 当前已画进二维码的码（避免每秒重画同一张） */
    private var shownCode: String = ""
}
