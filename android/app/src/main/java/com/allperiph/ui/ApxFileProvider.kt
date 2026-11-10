package com.allperiph.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 手机端文件面板（v2，「文件管理」方向一）给外部应用临时开放**收到的文件**。
 *
 * ## 为什么要有它
 * 收到的文件落在 `<外部文件>/APX`（应用专属外部目录）。文件面板新增「打开 / 分享」后，
 * 要把文件交给相册 / 播放器 / 微信等外部应用 —— 直接 `Uri.fromFile(...)` 传 `file://` 会撞上：
 *  1. targetSdk ≥ 24 时系统直接抛 `FileUriExposedException`；
 *  2. 就算绕过，外部应用对这个私有路径**没有读权限**，照样打不开。
 *
 * 本仓零第三方依赖（app/build.gradle.kts 约定），所以不引 `androidx.core` 的 FileProvider，
 * 与 TV 端 `com.allperiph.tv.ui.ApxFileProvider` 同构（逐字节同实现，各自一份——两端
 * 模块边界如此，见 tv 侧同名的「为什么要有它」），只支持按绝对路径只读单文件，
 * 配合 `Intent.FLAG_GRANT_READ_URI_PERMISSION` 给目标应用一次性读权限。
 *
 * 2026-11-11：「文件管理」第一批（打开/分享 + 传输记录 + 平板双栏）落地时引入。
 */
class ApxFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw FileNotFoundException("找不到文件：$uri")
        if (!f.isFile) throw FileNotFoundException("不是普通文件：$uri")
        // 只给只读：外部应用（相册/播放器/分享目标）不需要写，避免它们改掉收到的文件
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return mimeOf(name)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
            ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** 与 AndroidManifest 里 `android:authorities` 必须一致 */
        fun authority(ctx: android.content.Context): String = "${ctx.packageName}.files"

        /**
         * 把收到的文件包装成可跨应用传递的 `content://` URI。
         * 调用方**必须**给 Intent 加 `FLAG_GRANT_READ_URI_PERMISSION`，否则目标应用读不到。
         */
        fun uriFor(ctx: android.content.Context, f: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(ctx))
            .path(f.absolutePath)
            .build()

        private fun fileOf(uri: Uri): File? {
            val p = uri.path
            if (p.isNullOrBlank()) return null
            return File(p)
        }

        /**
         * 按扩展名猜 MIME（「打开 / 分享」拉起外部应用时用，也是 [getType] 的来源）。
         * 与 TV 端 `TvFiles.mimeOf` 同表 —— 两端对同一种扩展名应该给出同一种类型。
         */
        fun mimeOf(name: String): String? = when {
            name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".gif", true) -> "image/gif"
            name.endsWith(".webp", true) -> "image/webp"
            name.endsWith(".bmp", true) -> "image/bmp"
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            name.endsWith(".wav", true) -> "audio/wav"
            name.endsWith(".pdf", true) -> "application/pdf"
            name.endsWith(".zip", true) -> "application/zip"
            name.endsWith(".txt", true) -> "text/plain"
            else -> null
        }
    }
}
