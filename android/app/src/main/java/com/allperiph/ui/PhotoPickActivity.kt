package com.allperiph.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 相册**多选**页（issue#168 二轮，2026-11-11）。
 *
 * 悬浮球照片卡改成「点选 → 再点发」的节奏后，点卡要拉起系统相册并允许**多选** ——
 * 而照片卡在 Service（overlay）里构建，只能借透明 Activity 做宿主（与权限页
 * [PhotoPermActivity]、语音页 VoiceCaptureActivity 同一先例：runtime 组件必须由
 * Activity 发起）。
 *
 * 这里刻意做薄：进来就用 [ActivityResultContracts.GetMultipleContents] 拉
 * 系统多选（MIME 限图片），拿到结果后**先把字节快照进本应用缓存**再投回
 * [BallLatestPhoto.onPicked]（快照原因见下「为什么复制」），随后 finish。
 * 取消 / 没选任何一张时不回调 —— 卡维持原状（待发为空，下次点按重新进选图）。
 *
 * ## 为什么复制（issue#168 2026-11-11 19:05 日志九追）
 * 选图结果的 URI 授权在**部分厂商（HyperOS 实测）**上随本页 finish 一并收回：
 * 待发缩略图、发送时的探大小 / 兜底解码全部 SecurityException（`picker_get_content`
 * uri 调用方无权读取），相册按钮发什么败什么。而结果回调此刻授权还活着 ——
 * 趁 finish 之前把每张图流式复制进 cache（不占内存；失败的单张退回原 uri，
 * 发送端自有兜底，行为与旧版一致），此后缩略图与发送一律用本地快照，不再碰
 * picker uri。快照命名带原始文件名：对端落盘名跟着走（`FileSender.send(host, file)`
 * 取文件名，见 [com.allperiph.shared.net.FileSender]）。
 */
class PhotoPickActivity : ComponentActivity() {

    /** 必须在 onCreate 前注册（属性初始化器在构造期执行，PhotoPermActivity 同款注释） */
    private val pick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val picked = uris ?: emptyList()
        Log.i("PhotoPick", "多选结果：${picked.size} 张")
        if (picked.isEmpty()) {
            finish()
            return@registerForActivityResult
        }
        Thread({
            val dir = File(cacheDir, BallLatestPhoto.SNAP_DIR).apply { mkdirs() }
            val local = picked.mapIndexed { i, uri ->
                runCatching {
                    // 原始文件名趁授权活着查一次；查不到退时间戳名（后缀跟显示名走，缺省补 .jpg）
                    val raw = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    val safe = raw?.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    val name = safe ?: "photo_${System.currentTimeMillis()}_$i"
                    val ext = if (name.contains('.')) "" else ".jpg"
                    val out = File(dir, "$name$ext")
                    contentResolver.openInputStream(uri)!!.use { ins ->
                        out.outputStream().use { ins.copyTo(it) }
                    }
                    Log.i("PhotoPick", "快照 $i：${out.name}（${out.length()}B）")
                    Uri.fromFile(out)
                }.onFailure { Log.w("PhotoPick", "快照 $i 失败：${it.message}") }
                    .getOrNull() ?: uri
            }
            runOnUiThread {
                BallLatestPhoto.onPicked?.invoke(this, local)
                finish()
            }
        }, "apx-photo-snap").start()
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
