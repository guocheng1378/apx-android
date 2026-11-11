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
import java.io.File

/**
 * 悬浮球「最新照片卡片」的数据与发送（ROADMAP §5.2 ①，2026-11-12）。
 *
 * 目标只有两件：
 *  1. **查最新一张照片/截图**（MediaStore 按 `DATE_ADDED` 倒序取 1 条）并解码小尺寸
 *     缩略图（[ContentResolver.loadThumbnail]，系统缓存命中时近乎零开销）——
 *     是相册投影（全量）的先行验证，刻意不做分组/分页/监听；
 *  2. **点选 → 再点发**（issue#168 二轮改版）：点卡先进系统**多选**（可挑多张），
 *     选完再点卡把选中的全部发出（[FileSender] 9512 通道），发完自动重置重新选图。
 *     待发期间卡面显示首张缩略图 + 数量角标（见文末「多选待发」一节）。
 *     相册为空时系统多选页自己会呈现空态，这里不再单独报「还没有照片」。
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

    /** 悬浮球偏好文件：与 [FloatingBallService]、[BallIcon] 同一份，避免偏好文件碎片化 */
    private const val PREF_FILE = "apx_floating_ball"

    /** 选图快照在 cache 下的目录名（[PhotoPickActivity] 写、本类发送后清） */
    internal const val SNAP_DIR = "apx_pick"

    /** 查到的一条：媒体 uri + 文件名（发送时作对端落盘名）+ DATE_ADDED（缓存命中判定） */
    data class Latest(val uri: Uri, val name: String, val dateAdded: Long)

    /** 上次查询结果与解码好的缩略图（同一条照片重复展开菜单时不再解码，省电省 IO） */
    @Volatile private var cached: Latest? = null
    @Volatile private var cachedThumb: Bitmap? = null

    /** 读照片权限是否已授（API 33+ 的分媒体权限；本仓 minSdk 34，没有老系统分支） */
    fun hasMediaPerm(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // —————————————————————————— 相册按钮显示开关（issue#168 二轮，2026-11-11） ——————————————————————————
    //
    // 二轮反馈「相册按钮没有加到 app 设置页悬浮球菜单项，需要加上，保证可以自定义是否显示
    // 这个相册按钮」。此前照片卡是**常驻**项（不进勾选清单、关不掉）—— 常驻的初衷是
    // 「发照片不该被清空清单关掉」，但用户要的是自定义权，常驻让位。
    // 开关存进悬浮球同一份偏好文件（`apx_floating_ball`），默认开（老用户零感知）。

    private const val KEY_PHOTO_CARD_ON = "photo_card_on"

    /** 照片卡是否显示在扇形菜单第一位（默认开） */
    fun photoCardEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getBoolean(KEY_PHOTO_CARD_ON, true)

    /** 设置照片卡显示开关；关闭后菜单展开即不再出现（菜单每次现建，无需通知服务） */
    fun setPhotoCardEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PHOTO_CARD_ON, on).apply()
    }

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

    // —————————————————————————— 多选待发（issue#168 二轮，2026-11-11） ——————————————————————————
    //
    // 二轮反馈「发送逻辑不对：应该每次点击按钮后触发多选图片，然后再点击图标发送到电脑，
    // 发送之后按钮重置需要重新选择新图片」。改后的节奏：
    //   ① 点照片卡（待发为空）→ 拉系统多选（[PhotoPickActivity]，GetMultipleContents）；
    //   ② 选完卡上显示第一张缩略图 + 数量角标 → 再点卡 = 把选中的全部发出（9512 逐张）；
    //   ③ 发完（无论成败）清空待发 → 卡回到「最新照片」占位，下次点按重新选图。

    /** 待发清单：用户刚在多选框挑的图；非空时点卡即发，发完清空 */
    @Volatile private var pending: List<Uri> = emptyList()

    /** 待发数量（卡上角标 / 判定当前该选图还是该发送） */
    fun pendingCount(): Int = pending.size

    /** 待发的第一张（卡上缩略图用它；null = 没有待发） */
    fun pendingFirst(): Uri? = pending.firstOrNull()

    /**
     * 选图结果回调：[PhotoPickActivity] 拿到系统多选结果后投回这里（选图页是透明
     * Activity，结果不经任何窗口 —— 由本回调解码 + 落状态）。挂上回调是拉选图页的
     * 前置条件；服务进程活着期间一直挂着也无妨（重复挂同一实现）。
     */
    @Volatile var onPicked: ((Context, List<Uri>) -> Unit)? = null

    /** 待发首张的缩略图缓存：键是首张 uri 的字符串，换了人立即作废重解 */
    @Volatile private var pendingThumb: Bitmap? = null
    @Volatile private var pendingThumbKey: String? = null

    /** 卡面缩略图：有待发给待发首张的缩略图，没有给 null（回落「最新照片」占位） */
    fun pendingThumbOrNull(): Bitmap? = pendingThumb

    /**
     * 解码待发首张缩略图（后台线程，回主线程 [onReady]）—— 与 [loadThumb] 同口径，
     * 但针对多选结果 URI：九追起结果是本地快照（file://，[PhotoPickActivity] 落
     * 缓存，避开厂商收回 picker uri 授权的问题），用 BitmapFactory 按解码边界降采样；
     * 极旧的 picker uri（快照失败残留）仍走 [ContentResolver.loadThumbnail]。
     */
    fun loadPendingThumb(ctx: Context, sizePx: Int, onReady: () -> Unit) {
        val first = pending.firstOrNull() ?: return
        val key = first.toString()
        if (pendingThumbKey == key && pendingThumb != null) {
            onReady()
            return
        }
        pendingThumbKey = key
        pendingThumb = null
        Thread({
            pendingThumb = decodeThumb(ctx, first, sizePx)
            Handler(Looper.getMainLooper()).post { onReady() }
        }, "apx-ball-pending-thumb").start()
    }

    /**
     * 缩略图统一入口：本地快照（file://）走 BitmapFactory 边界降采样，
     * 其他（media / picker uri）走 [ContentResolver.loadThumbnail]（媒体库缓存命中近乎零开销）。
     */
    private fun decodeThumb(ctx: Context, uri: Uri, sizePx: Int): Bitmap? = runCatching {
        if (uri.scheme == "file") {
            val path = uri.path ?: return@runCatching null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx * 2) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                },
            )
        } else {
            ctx.contentResolver.loadThumbnail(uri, android.util.Size(sizePx * 2, sizePx * 2), null)
        }
    }.onFailure { Log.w(TAG, "缩略图解码失败：${it.message}") }.getOrNull()

    /** 多选结果落状态：记清单、作废旧缩略图、Toast 提示下一步（发送） */
    private fun handlePicked(ctx: Context, uris: List<Uri>) {
        pending = uris.distinct()
        pendingThumb = null
        pendingThumbKey = null
        toast(ctx, R.string.ui_ball_photo_picked, pending.size)
    }

    /**
     * 照片卡总入口（点按 / 拖出都汇到这）：有待发就**全部发出**并清空重置；
     * 没有待发就挂好回调、拉系统多选页。没权限先走授权页，没连上如实提示。
     * 发送走 [FileSender] 的 9512 通道逐张发，与触控板页发文件同一条链路。
     */
    fun send(ctx: Context, card: android.view.View) {
        if (!hasMediaPerm(ctx)) {
            runCatching { PhotoPermActivity.start(ctx) }
                .onFailure { Log.w(TAG, "拉授权页失败：${it.message}") }
            return
        }
        val list = pending
        if (list.isEmpty()) {
            // ① 待发为空：进多选。回调先挂再拉页，选图页拿结果时必定有接收方
            onPicked = { c, uris -> handlePicked(c, uris) }
            runCatching { PhotoPickActivity.start(ctx) }
                .onFailure { Log.w(TAG, "拉多选页失败：${it.message}") }
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
            var ok = 0
            list.forEach { uri ->
                if (sendOne(ctx, host, uri)) ok++
            }
            val total = list.size
            // ③ 发完重置：待发清空 + 缩略图作废 + 快照缓存清空（快照只在待发期间有效，
            //    留着只会白占 cache）—— 卡回到「最新照片」占位，下次点按重新选图
            //   （用户明确要求「发送之后按钮重置需要重新选择新图片」）
            pending = emptyList()
            pendingThumb = null
            pendingThumbKey = null
            File(ctx.cacheDir, SNAP_DIR).takeIf { it.isDirectory }?.deleteRecursively()
            sending.set(false)
            Handler(Looper.getMainLooper()).post {
                if (ok == total) toast(ctx, R.string.ui_ball_photo_send_done, total)
                else toast(ctx, R.string.ui_touchpad_text_send_failed)
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

    /**
     * 单张发送，带「假流」兜底（issue#168 2026-11-11 六追：悬浮球相册按钮发到 Mac
     * 收到的是 1157B 的 file.bin —— FileSender.queryName 查不到 DISPLAY_NAME、
     * openInputStream 只吐 1KB 假数据；文件面板正常说明 9512 链路本身通）。
     *
     * 流程：九追起多选结果优先是本地快照（file://，[PhotoPickActivity] 落缓存，
     * 规避厂商收回 picker uri 授权）—— 本地文件直接走 [FileSender.send] 的 File 版，
     * 探大小用 File.length、文件名用快照文件名（快照时已带原始名），不需要兜底；
     * 快照失败退回原 uri 时才走原兜底：先 openFileDescriptor 探真实大小 ——
     *  · 大小正常（>2KB）→ 原样走 [FileSender.send]（Context+Uri 版）；
     *  · 打不开或 0..2047B（假流特征）→ 退 [ContentResolver.loadThumbnail] 4K 缩略图
     *    （媒体库有缓存，不依赖原始流），压 JPEG 90 后用流版 [FileSender.send] 发出，
     *    文件名取 MediaStore DISPLAY_NAME，查不到落 `IMG_<时间戳>.jpg`。
     */
    private fun sendOne(ctx: Context, host: String, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        // 本地快照：文件就在 cache 里，直接发（名字=快照文件名，带原始名）
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return false)
            if (f.isFile) return FileSender.send(host, f)
        }
        val size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }
            .onFailure { Log.w(TAG, "探大小失败：${it.message}") }
            .getOrNull()
        if (size != null && size > 2047L) {
            return runCatching { FileSender.send(ctx, host, uri) }
                .onFailure { Log.w(TAG, "发照片失败：${it.message}") }
                .getOrDefault(false)
        }
        // 假流兜底：缩略图重压缩成真流再发
        val name = runCatching {
            cr.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "IMG_${System.currentTimeMillis()}.jpg"
        val bmp = runCatching {
            cr.loadThumbnail(uri, android.util.Size(4096, 4096), null)
        }.onFailure { Log.w(TAG, "缩略图兜底失败：${it.message}") }.getOrNull() ?: return false
        val bytes = java.io.ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            bmp.recycle()
            bos.toByteArray()
        }
        return FileSender.send(host, name, bytes.size.toLong(), bytes.inputStream())
    }

    private fun toast(ctx: Context, res: Int, arg: Int = 0) {
        Toast2.show(ctx, res, arg)
    }
}

/** 文案 toast 的小收口（避免各处直接 new Toast 的样式漂移；仅本文件内部使用） */
private object Toast2 {
    private val main = Handler(Looper.getMainLooper())
    fun show(ctx: Context, res: Int, arg: Int = 0) {
        main.post {
            val text = if (arg > 0) ctx.getString(res, arg) else ctx.getString(res)
            android.widget.Toast.makeText(ctx.applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
