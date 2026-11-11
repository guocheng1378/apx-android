package com.allperiph.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 相册**多选**页（issue#168 二轮，2026-11-11）。
 *
 * 悬浮球照片卡改成「点选 → 再点发」的节奏后，点卡要拉起系统相册并允许**多选** ——
 * 而照片卡在 Service（overlay）里构建，只能借透明 Activity 做宿主（与权限页
 * [PhotoPermActivity]、语音页 VoiceCaptureActivity 同一先例：runtime 组件必须由
 * Activity 发起）。
 *
 * 这里刻意做薄：进来就用 [ActivityResultContracts.GetMultipleContents] 拉
 * 系统多选（"image/*"），拿到结果（无论几张、取消与否）投回
 * [BallLatestPhoto.onPicked] 即 finish。取消 / 没选任何一张时不回调 ——
 * 卡维持原状（待发为空，下次点按重新进选图）。
 *
 * 结果 URI 是系统临时授权（持续到本进程结束），发送与缩略图解码都在同一会话内
 * 完成；进程被杀待发作废，卡自然回落「最新照片」占位，不会假装还有图。
 */
class PhotoPickActivity : ComponentActivity() {

    /** 必须在 onCreate 前注册（属性初始化器在构造期执行，PhotoPermActivity 同款注释） */
    private val pick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val picked = uris ?: emptyList()
        Log.i("PhotoPick", "多选结果：${picked.size} 张")
        if (picked.isNotEmpty()) {
            BallLatestPhoto.onPicked?.invoke(this, picked)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick.launch("image/*")
    }

    companion object {
        /** 悬浮球照片卡点按时拉起（Service 场景需 NEW_TASK 标志）—— 伴生启动器，全仓唯一入口 */
        fun start(c: Context) = c.startActivity(intent(c))

        /** 构造 Intent（[start] 内部用；保留是为了与既有调用点兼容） */
        fun intent(c: Context): Intent =
            Intent(c, PhotoPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
