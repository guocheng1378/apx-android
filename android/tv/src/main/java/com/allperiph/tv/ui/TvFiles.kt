package com.allperiph.tv.ui

import android.content.Context
import com.allperiph.tv.TvServerService
import com.allperiph.shared.net.FileReceiver
import java.io.File

/**
 * 文件面板的数据来源：收到的文件从哪读、能发给谁。
 *
 * 「能发给谁」刻意不依赖用户手输 IP：电视上打字很痛苦（遥控器），所以目标 =
 * **当前连入方**（正在控制本机的那台）优先，其次**曾经连过的对端**（服务端落盘记忆）。
 * 这也符合实际用法：手机连上电视 → 电视收到的文件再发回这台手机。
 */
object TvFiles {

    /** 接收目录：与 [FileReceiver] 的落盘位置必须一致（<外部文件>/APX） */
    fun recvDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "APX")

    /** 已收到的文件，按修改时间倒序（新的在前） */
    fun listReceived(ctx: Context): List<File> {
        val dir = recvDir(ctx)
        val all = dir.listFiles() ?: return emptyList()
        return all.filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    /**
     * 发送目标候选：当前连入方 + 曾连过的对端（去重、保序）。
     * 列表为空表示「本机还没被任何设备连过」——界面要如实提示，而不是假装能发。
     */
    fun targets(): List<String> = TvServerService.current?.sendTargets() ?: emptyList()

    /** 根据文件扩展名返回对应类型图标 */
    fun fileIcon(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
            name.endsWith(".png", true) || name.endsWith(".gif", true) ||
            name.endsWith(".bmp", true) || name.endsWith(".webp", true) ||
            name.endsWith(".svg", true) -> "\uD83D\uDDBC\uFE0F" // 🖼️
        name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
            name.endsWith(".avi", true) || name.endsWith(".mov", true) ||
            name.endsWith(".flv", true) || name.endsWith(".wmv", true) -> "\uD83C\uDFAC" // 🎬
        name.endsWith(".mp3", true) || name.endsWith(".wav", true) ||
            name.endsWith(".flac", true) || name.endsWith(".aac", true) ||
            name.endsWith(".ogg", true) || name.endsWith(".m4a", true) -> "\uD83C\uDFB5" // 🎵
        name.endsWith(".pdf", true) || name.endsWith(".doc", true) ||
            name.endsWith(".docx", true) || name.endsWith(".xls", true) ||
            name.endsWith(".xlsx", true) || name.endsWith(".ppt", true) ||
            name.endsWith(".pptx", true) || name.endsWith(".txt", true) ||
            name.endsWith(".csv", true) || name.endsWith(".md", true) -> "\uD83D\uDCC4" // 📄
        // 安装包 / 压缩包 / 其它统一用「包」图标（以前 .apk、压缩包、else 三支返回同一个值，是冗余分支）
        else -> "\uD83D\uDCE6" // 📦
    }

    /**
     * 按扩展名猜 MIME：拉起外部应用时用，也是 `ApxFileProvider.getType` 的来源。
     * 以前 TvFileActivity 与 TvFilePrompt 各写了一份逐字符相同的实现 —— 收到这里。
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

    /** 人类可读大小（KB/MB/GB）。必须钉 Locale：否则阿拉伯语等地区会输出本地化数字 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}