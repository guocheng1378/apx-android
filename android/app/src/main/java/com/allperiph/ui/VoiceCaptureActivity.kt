package com.allperiph.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.allperiph.R

/**
 * 「语音」工具键的识别宿主页（2026-11-11 成宇「语音按键链路不能用」）。
 *
 * 系统语音识别必须由 Activity 发起（startActivityForResult 要宿主），而悬浮球 overlay
 * 里的键盘面板只有 Service 上下文 —— 旧实现在这个场景只弹「语音要在主屏键盘页使用」，
 * 悬浮键盘的语音键成了死键。改走与 [PhotoPermActivity] 同一套「透明页承载」先例：
 * 透明主题页进来就拉系统识别、拿到结果经伴生回调投回订阅方、随即 finish，全程无感。
 *
 * 识别器挑选与 KeyboardPage.launchKeyboardVoice 同口径（v223.29.33 结论）：
 * **先挑非 Google 的识别活动再拉起**（国内 ROM 往往只有 Google 语音、网络不可达）；
 * 只有 Google 时如实提示，不静默拉起。ACTION / EXTRA 字面值与 SpeechRecognizer
 * 源码逐字一致（公开协议常量，零符号依赖）。
 */
class VoiceCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VoiceCapture"

        /** 识别请求码（仅本页内部使用） */
        private const val REQ_SPEECH = 4151

        /**
         * 识别结果投递口：KeyboardPage 的键盘面板在组合期挂上（DisposableEffect 挂 / 摘，
         * 防 Service 面板重建后旧回调滞留）。传 null = 没听清 / 用户取消 / 识别不可用，
         * 由订阅方决定提示与清态。
         */
        @Volatile
        var onResult: ((String?) -> Unit)? = null

        /** 全仓唯一启动入口（悬浮键盘 Service 场景需 NEW_TASK 标志；check_entrypoints 认 X.start(） */
        fun start(c: Context) = c.startActivity(intent(c))

        private fun intent(c: Context): Intent =
            Intent(c, VoiceCaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchSpeech()
    }

    /** 挑选识别器并拉起：挑选口径与 KeyboardPage.launchKeyboardVoice 逐字一致 */
    private fun launchSpeech() {
        val intent = Intent("android.speech.action.RECOGNIZE_SPEECH").apply {
            putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
            putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "zh-CN")
            putExtra("android.speech.extra.LANGUAGE", "zh-CN")
        }
        val handlers = packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY,
        )
        val nonGoogle = handlers.firstOrNull {
            !(it.activityInfo?.packageName ?: "").startsWith("com.google.android")
        }
        val pick = nonGoogle ?: handlers.firstOrNull()
        if (pick == null) {
            toast(R.string.ui_kb_voice_unavailable)
            deliver(null)
            return
        }
        if (nonGoogle == null) {
            // 只有 Google 语音（国内网络不可达）：如实提示，不静默拉起
            toast(R.string.ui_kb_voice_google_only)
            deliver(null)
            return
        }
        intent.setPackage(pick.activityInfo.packageName)
        runCatching { startActivityForResult(intent, REQ_SPEECH) }
            .onFailure {
                Log.w(TAG, "识别页拉起失败：${it.message}")
                toast(R.string.ui_kb_voice_unavailable)
                deliver(null)
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH) return
        // EXTRA_RESULTS 字面值与 SpeechRecognizer 源码逐字一致（同 launchKeyboardVoice 口径）
        val t = data?.getStringArrayListExtra("android.speech.extra.RESULTS")
            ?.firstOrNull().orEmpty()
        deliver(t.ifBlank { null })
    }

    /** 投递结果给订阅方后退出透明页；回调异常只记日志，不能把透明页留在栈里 */
    private fun deliver(text: String?) {
        runCatching { onResult?.invoke(text) }
            .onFailure { Log.w(TAG, "识别结果投递失败：${it.message}") }
        finish()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
