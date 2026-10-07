package com.allperiph.tv.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import com.allperiph.shared.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 给外部应用（播放器 / 安装器 / 看图）临时开放**收到的文件**。
 *
 * ## 为什么要有它
 * 收到的文件落在应用私有目录（`<外部文件>/APX`）。以前是直接 `Uri.fromFile(...)` 跨应用传
 * `file://`，于是撞上两件事：
 *  1. targetSdk ≥ 24 时系统直接抛 `FileUriExposedException`；
 *  2. 就算绕过，外部应用对这个私有路径**没有读权限**，播放器/安装器照样打不开。
 *
 * 当时的绕法是 `StrictMode.setVmPolicy(VmPolicy.Builder().build())` —— 把整个进程的 VM 检测
 * 清空，而且**没有任何地方恢复**（进程级、不可逆），一次调用后全 App 的 file:// 暴露检测、
 * SQLite 泄漏检测全部失效。
 *
 * 本模块坚持零第三方依赖（见 tv/build.gradle.kts），所以不引 `androidx.core` 的 FileProvider，
 * 自己实现一个最小版本：只支持按绝对路径读单文件，配合
 * `Intent.FLAG_GRANT_READ_URI_PERMISSION` 给目标应用一次性读权限。
 */
class ApxFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw FileNotFoundException("找不到文件：$uri")
        if (!f.isFile) throw FileNotFoundException("不是普通文件：$uri")
        // 只给只读：外部应用（播放器/安装器）不需要写，避免它们改掉刚收到的文件
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return TvFiles.mimeOf(name)
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
            if (p.isNullOrBlank()) {
                Log.w("ApxFileProvider：URI 缺路径 $uri")
                return null
            }
            return File(p)
        }
    }
}
