package com.allperiph.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.allperiph.R
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.util.Log
import com.allperiph.wireless.ControlTarget

/**
 * 悬浮球「最新照片卡片」的数据与发送（ROADMAP §5.2 ①，2026-11-12）。
 *
 * 目标只有两件：
 *  1. **查最新一张照片/截图**（MediaStore 按 `DATE_ADDED` 倒序取 1 条）并解码小尺寸
 *     缩略图（[ContentResolver.loadThumbnail]，系统缓存命中时近乎零开销）——
 *     是相册投影（全量）的先行验证，刻意不做分组/分页/监听；
 *  2. **拖出即发**：文件本体走 [FileSender] 的 9512 通道，落盘到对端「收到的文件」
 *     目录（与触控板页发文件同一条链路，协议零新增）。
 *
 * ## 发送目标怎么定（两路取一，覆盖 PC 的两个方向）
 *  · 手机当触控板**控制**电脑：[ControlTarget.host]（选了受控设备时的 IP）；
 *  · 手机**被**电脑控制（最常见）：控制面对端就是那台电脑，
 *    取 [com.allperiph.controlled.ControlledService.server] 的 currentPeerHost()。
 *  两条链路都没通 → 如实提示「先连上再发」，不猜 IP、不装成功。
 *
 * ## 权限
 * 读照片需要 `READ_MEDIA_IMAGES`（minSdk 34，无老系统分支）。授权走独立透明页
 * [PhotoPermActivity]（Service 不能直接弹权限框，这是 framework 的限制）；
 * 未授权时查询直接跳过，卡片显示占位图，点击进授权页 —— 不假装相册是空的。
 */
object BallLatestPhoto {

    private const val TAG = "BallLatestPhoto"

    /** 查到的一条：媒体 uri + 文件名（发送时作对端落盘名）+ DATE_ADDED（缓存命中判定） */
    data class Latest(val uri: Uri, val name: String, val dateAdded: Long)

    /** 上次查询结果与解码好的缩略图（同一条照片重复展开菜单时不再解码，省电省 IO） */
    @Volatile private var cached: Latest? = null
    @Volatile private var cachedThumb: Bitmap? = null

    /** 读照片权限是否已授（API 33+ 的分媒体权限；本仓 minSdk 34，没有老系统分支） */
    fun hasMediaPerm(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 同步查最新一张。菜单在主线程构建，但查询是 ContentProvider 的 binder 调用 +
     * 索引扫描（有 DATE_ADDED 索引，万级库也在毫秒级）—— 不值得为此起线程，
     * 真正的解码（loadThumbnail）才放到后台（见 [loadThumb]）。
     */
    fun query(ctx: Context): Latest? {
        if (!hasMediaPerm(ctx)) return null
        val cr = ctx.contentResolver
        return runCatching {
            // 排序 + 限量走 query bundle（QUERY_ARG_*）：老式排序参数已废弃，minSdk 34 直接用新口
            val bundle = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_ADDED),
                )
                // 降序常量全名 QUERY_SORT_DIRECTION_DESCENDING（QUERY_SORT_DESCENDING 不存在，
                // CI 编译第一时间红过一回——名字记错很贵，写全）。
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
            )
            cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, bundle, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@runCatching null
                    val id = c.getLong(0)
                    Latest(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(1) ?: "photo.jpg",
                        dateAdded = c.getLong(2),
                    )
                }
        }.onFailure { Log.w(TAG, "查最新照片失败：${it.message}") }.getOrNull()
    }

    /**
     * 解码缩略图（后台线程调，回主线程 [onReady]）。
     * 每次展开菜单都重查（[query]），这里只按「是不是同一条」决定要不要重新解码：
     * 拍了新照片 → cached 换人、旧缩略图作废；同一条 → 命中缓存直接回调（省电省 IO）。
     */
    fun loadThumb(ctx: Context, latest: Latest, sizePx: Int, onReady: () -> Unit) {
        if (cached != latest) {
            cached = latest
            cachedThumb = null
        }
        if (cachedThumb != null) {
            onReady()
            return
        }
        Thread({
            val bmp = runCatching {
                ctx.contentResolver.loadThumbnail(latest.uri, android.util.Size(sizePx * 2, sizePx * 2), null)
            }.onFailure { Log.w(TAG, "缩略图解码失败：${it.message}") }.getOrNull()
            cachedThumb = bmp
            Handler(Looper.getMainLooper()).post { onReady() }
        }, "apx-ball-thumb").start()
    }

    /** 卡片要显示的图：有缩略图给缩略图，没有（还没解出来/没权限）给占位图标 */
    fun thumbOrNull(): Bitmap? = cachedThumb

    /** 发送中的去重位：拖出与抬手两个分支都叫 [send]，别把一张照片发两遍 */
    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 发当前最新一张给电脑。UI 事件（点按 / 拖出）都汇到这里：
     * 没权限 → 拉授权页；没连上 → 如实提示；发完（成功或失败）Toast 报结果。
     * 阻塞活（9512 发送）甩专用线程，Toast 回主线程 —— 与 TouchpadActivity.sendFile 同口径。
     */
    fun send(ctx: Context, card: android.view.View) {
        if (!hasMediaPerm(ctx)) {
            runCatching { PhotoPermActivity.start(ctx) }
                .onFailure { Log.w(TAG, "拉授权页失败：${it.message}") }
            return
        }
        val latest = query(ctx)
        if (latest == null) {
            toast(ctx, R.string.ui_ball_photo_none)
            return
        }
        val host = peerHost()
        if (host == null) {
            toast(ctx, R.string.ui_ball_photo_target_none)
            return
        }
        if (!sending.compareAndSet(false, true)) return
        toast(ctx, R.string.ui_main_toast_file_send)
        Thread({
            val ok = runCatching { FileSender.send(ctx, host, latest.uri) }
                .onFailure { Log.w(TAG, "发照片失败：${it.message}") }
                .getOrDefault(false)
            sending.set(false)
            Handler(Looper.getMainLooper()).post {
                toast(
                    ctx,
                    if (ok) R.string.ui_main_text_file_send_done else R.string.ui_touchpad_text_send_failed,
                )
                // 发完收起菜单：动作已完成，留着一张卡片没有任何后续可点
                FloatingBallService.collapseOverlays()
            }
        }, "apx-ball-photo-send").start()
    }

    /** 发送目标：手机控电脑（ControlTarget）优先，其次手机被电脑控制（控制面对端）。
     * 手机控手机的 TvControlServer 不在这条路上 —— 照片发电脑是本步的明确范围（ROADMAP §5.2 ①）。
     */
    private fun peerHost(): String? {
        if (ControlTarget.isControlling() && ControlTarget.host.isNotBlank()) return ControlTarget.host
        val controlled = com.allperiph.controlled.ControlledService.server ?: return null
        if (!controlled.ready) return null
        return controlled.currentPeerHost()?.takeIf { it.isNotBlank() }
    }

    private fun toast(ctx: Context, res: Int) {
        Toast2.show(ctx, res)
    }
}

/** 文案 toast 的小收口（避免各处直接 new Toast 的样式漂移；仅本文件内部使用） */
private object Toast2 {
    private val main = Handler(Looper.getMainLooper())
    fun show(ctx: Context, res: Int) {
        main.post {
            android.widget.Toast.makeText(ctx.applicationContext, ctx.getString(res), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
