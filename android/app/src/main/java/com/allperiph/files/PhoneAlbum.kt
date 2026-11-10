package com.allperiph.files

import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.allperiph.R
import com.allperiph.notify.NotificationSyncBus
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.proto.ApxFrame.AlbumEntry
import com.allperiph.shared.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 相册投影（ROADMAP §5.2 ②，2026-11-12，协议 0x48–0x4B、能力位 bit4）。
 *
 * 让**电脑**浏览本机相册：0x48 分页取条目（0x49 应答，按 `DATE_ADDED` 倒序）、
 * 0x4A 新照片监听开关（开着时拍了新照片经既有 0x40 通知链路推送，不新开帧族）、
 * 0x4B 取一张缩略图（JPEG 本体经 [FileSender] 的 9512 通道推回，文件名
 * `albumthumb_<id>.jpg`，PC 端虚拟主机映射后可直接当 <img> 地址；失败发 0x47，
 * path = `album:thumb:<id>`）。原图/导出复用既有 0x46（[PhoneFiles] 的取文件，
 * 无损）—— 所以条目里的 path 必须是真实绝对路径，拿不到的（部分机型 `DATA` 为空）
 * 直接从列表剔除。
 *
 * ## 权限
 * 读照片需要 `READ_MEDIA_IMAGES`（本仓 minSdk 34，无老系统分支）；授权入口与悬浮球
 * 照片卡同一条路（[com.allperiph.ui.PhotoPermActivity]）。未授权时 0x48/0x4B 回
 * `PHONEFILE_ERR_NO_PERM`，PC 端如实提示，不假装相册是空的。
 *
 * ## 接线
 * 与 [PhoneFiles] 同一套注入口径：三条控制链路（shared ControlServer /
 * TvControlServer 注入 `onAlbumCmd`，TvControllerClient 直调）统一汇进 [handle]；
 * 目录 IO 与 9512 发送都是阻塞活，全部甩专用线程，不卡注入队列。
 */
object PhoneAlbum {

    private const val TAG = "PhoneAlbum"

    /** 本端能力位：0x33 能力协商里上报（CAP_ALBUM），支持相册浏览就报 */
    const val CAP = ApxFrame.CAP_ALBUM

    /** 0x47 回执里缩略图失败的 path 前缀（PC 端据此区分「取文件失败」与「缩略图失败」） */
    const val THUMB_PATH_PREFIX = "album:thumb:"

    /** 读照片权限是否已授：READ_MEDIA_IMAGES 或已给「所有文件访问」（后者覆盖前者） */
    fun hasImageAccess(ctx: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
        ) {
            return@runCatching true
        }
        ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 三条控制链路的统一入口（回调/直调都落这里）。reader 线程调用 —— 相册查询、
     * 缩略图解码与 9512 发送都是阻塞活，全部甩到专用线程，不卡注入队列。
     * @param cmd 0x48=分页 0x4A=监听开关 0x4B=缩略图；body=完整帧载荷（含 cmd 字节）
     * @param reply 应答通道（0x49/0x47 走原控制链路返回）
     * @param peerHost 对端 IP（0x4B 经 9512 推送缩略图的目标）
     */
    fun handle(cmd: Int, body: ByteArray, reply: (ByteArray) -> Boolean, peerHost: String?) {
        Thread({
            when (cmd) {
                ApxFrame.CMD_ALBUM_PAGE -> {
                    // 载荷：[0x48][page u32 LE] —— 最短 5 字节，缺字节按 0 页兜底
                    var page = 0
                    if (body.size >= 5) {
                        for (i in 0 until 4) page = page or ((body[1 + i].toInt() and 0xFF) shl (8 * i))
                    }
                    pageAndReply(page, reply)
                }
                ApxFrame.CMD_ALBUM_WATCH -> {
                    // 载荷：[0x4A][on u8]（1=开 0=关）；无应答帧，开关成败看后续推送
                    if (body.size >= 2) setWatch(body[1].toInt() and 0xFF != 0)
                }
                ApxFrame.CMD_ALBUM_THUMB -> {
                    // 载荷：[0x4B][id u32 LE]；本体经 9512 推回，失败发 0x47
                    var id = 0L
                    if (body.size >= 5) {
                        for (i in 0 until 4) id = id or ((body[1 + i].toLong() and 0xFF) shl (8 * i))
                    }
                    sendThumb(id, peerHost, reply)
                }
            }
        }, "apx-phone-album").start()
    }

    /**
     * 0x48 → 0x49：取一页相册条目。分页/排序都走 query bundle（QUERY_ARG_*）——
     * 老式排序参数已废弃；`DATE_ADDED` 有索引，万级库分页也在毫秒级。
     * err 取值复用 ApxFrame.PHONEFILE_ERR_*（0=成功 1=无权限 3=查询失败）。
     */
    private fun pageAndReply(page: Int, reply: (ByteArray) -> Boolean) {
        val ctx = com.allperiph.App.context
        if (ctx == null || !hasImageAccess(ctx)) {
            Log.w(TAG, "相册浏览被拒：未授予照片读取权限")
            reply(ApxFrame.packAlbumPageReply(ApxFrame.PHONEFILE_ERR_NO_PERM, page, emptyList()))
            return
        }
        val entries = runCatching {
            queryPage(ctx, page)
        }.onFailure { Log.w(TAG, "相册查询失败：${it.message}") }.getOrNull()
        if (entries == null) {
            reply(ApxFrame.packAlbumPageReply(ApxFrame.PHONEFILE_ERR_READ, page, emptyList()))
            return
        }
        if (entries.isNotEmpty() && page == 0) {
            // 开页即记住最新一张：0x4A 打开后 ContentObserver 只推「比它新的」，
            // 避免监听刚开就把历史照片当新照片灌给 PC。
            lastSeenId = entries[0].id
        }
        reply(ApxFrame.packAlbumPageReply(ApxFrame.PHONEFILE_ERR_NONE, page, entries))
    }

    /** 一页相册条目（[ApxFrame.ALBUM_PAGE_SIZE] 条，`DATE_ADDED` 倒序）。DATA 为空的条目剔除。 */
    private fun queryPage(ctx: Context, page: Int): List<AlbumEntry> {
        val cr = ctx.contentResolver
        val bundle = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, ApxFrame.ALBUM_PAGE_SIZE)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page.coerceAtLeast(0) * ApxFrame.ALBUM_PAGE_SIZE)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val out = ArrayList<AlbumEntry>(ApxFrame.ALBUM_PAGE_SIZE)
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, bundle, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: ""
                // DATA 为空（部分云照片/转码条目）= 0x46 取不到原图 —— 留着只会点一次失败一次
                if (path.isEmpty()) continue
                out.add(
                    AlbumEntry(
                        id = c.getLong(iId),
                        path = path,
                        name = c.getString(iName) ?: "photo.jpg",
                        added = c.getLong(iAdded),   // 秒（DATE_ADDED 口径），PC 端按它折算日期
                        size = c.getLong(iSize),
                    )
                )
            }
        }
        return out
    }

    /** 0x4B：取一张缩略图。loadThumbnail 命中系统缓存时近乎零开销；本体压成 JPEG 经 9512 推回。 */
    private fun sendThumb(id: Long, peerHost: String?, reply: (ByteArray) -> Boolean) {
        val failPath = "$THUMB_PATH_PREFIX$id"
        if (peerHost.isNullOrBlank()) {
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_SEND_FAILED, failPath))
            return
        }
        val ctx = com.allperiph.App.context
        if (ctx == null || !hasImageAccess(ctx)) {
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_NO_PERM, failPath))
            return
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = runCatching {
            ctx.contentResolver.loadThumbnail(uri, android.util.Size(ApxFrame.ALBUM_THUMB_PX, ApxFrame.ALBUM_THUMB_PX), null)
        }.onFailure { Log.w(TAG, "缩略图解码失败 id=$id：${it.message}") }.getOrNull()
        if (bmp == null) {
            // 条目不存在/已删：与「取文件失败」同码（NOT_FOUND），PC 端把占位图如实标灰
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_NOT_FOUND, failPath))
            return
        }
        val bytes = runCatching {
            val bos = ByteArrayOutputStream(1 shl 15)
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            bos.toByteArray()
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_READ, failPath))
            return
        }
        val name = "albumthumb_$id.jpg"
        val ok = FileSender.send(peerHost, name, bytes.size.toLong(), ByteArrayInputStream(bytes))
        if (!ok) {
            Log.w(TAG, "推缩略图失败：$name → $peerHost")
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_SEND_FAILED, failPath))
        }
    }

    // ———————————————————— 0x4A 新照片监听 ————————————————————

    /** 监听刚打开时以最新一张为基准：只推比它新的（否则一开监听就把相册灌过去） */
    @Volatile private var lastSeenId: Long = -1L
    @Volatile private var observer: ContentObserver? = null

    /** 开/关新照片监听。开 = 挂 ContentObserver（主线程 Handler，查库甩后台线程）。 */
    private fun setWatch(on: Boolean) {
        val ctx = com.allperiph.App.context ?: return
        synchronized(this) {
            if (on && observer == null) {
                if (!hasImageAccess(ctx)) {
                    Log.w(TAG, "新照片监听开不了：未授予照片读取权限")
                    return
                }
                lastSeenId = runCatching { queryPage(ctx, 0).firstOrNull()?.id ?: -1L }.getOrDefault(-1L)
                val h = Handler(Looper.getMainLooper())
                val obs = object : ContentObserver(h) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        // 防抖：一次拍照 MediaStore 可能连报多次，统一 post 去重（查最新比对 id）
                        h.removeCallbacks(notifyNewest)
                        h.postDelayed(notifyNewest, 500)
                    }
                }
                ctx.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
                observer = obs
                Log.i(TAG, "新照片监听已开（基准 id=$lastSeenId）")
            } else if (!on && observer != null) {
                runCatching { ctx.contentResolver.unregisterContentObserver(observer!!) }
                observer = null
                Log.i(TAG, "新照片监听已关")
            }
        }
    }

    /** 防抖后的「查最新一张并推送」；同一条只推一次，互删/清空不推（比对 id） */
    private val notifyNewest = Runnable {
        val ctx = com.allperiph.App.context ?: return@Runnable
        if (observer == null) return@Runnable
        val newest = runCatching { queryPage(ctx, 0).firstOrNull() }.getOrNull() ?: return@Runnable
        if (newest.id == lastSeenId) return@Runnable
        lastSeenId = newest.id
        val ok = NotificationSyncBus.sendPush(
            key = "apx-album-${newest.id}",
            title = ctx.getString(R.string.ui_album_watch_push_title),
            text = newest.name,
            app = ctx.getString(R.string.ui_album_watch_push_app),
        )
        Log.i(TAG, "新照片 ${newest.name} 推送${if (ok) "成功" else "失败（对端不支持/未连接）"}")
    }
}
