package com.allperiph.screenmirror

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.allperiph.R

/**
 * 屏幕采集授权页：向系统申请投屏授权（MediaProjection 弹窗）。
 *
 * 授权成功 → startForegroundService 携带 resultCode/data 启动采集服务后 finish；
 * 拒绝 → 如实提示（文案走资源），不启动任何服务。
 *
 * Android 14+ 要求：mediaProjection 类型的前台服务必须在应用可见时启动，
 * 因此授权 → 起服务这条链路必须在本前台页内完成，不能后台直接起服务。
 */
class ScreenMirrorActivity : ComponentActivity() {

    private lateinit var statusView: TextView

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
            statusView.text = getString(R.string.sm_start_done)
            statusView.postDelayed({ finish() }, 1200)
        } else {
            statusView.text = getString(R.string.sm_start_denied)
            statusView.postDelayed({ finish() }, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(48, 40, 48, 40)
            setBackgroundColor(Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            addView(statusView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        })
        statusView.text = getString(R.string.sm_start_wait)
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (manager == null) {
            statusView.text = getString(R.string.sm_start_denied)
            statusView.postDelayed({ finish() }, 2000)
            return
        }
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: android.content.Intent) {
        val intent = android.content.Intent(this, ScreenMirrorService::class.java)
            .putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenMirrorService.EXTRA_DATA, data)
        startForegroundService(intent)
    }
}
