package com.allperiph.tv.ui

import android.content.Context
import com.allperiph.tv.TvServerService
import com.allperiph.tv.net.TvFileReceiver
import java.io.File

/**
 * 文件面板的数据来源：收到的文件从哪读、能发给谁。
 *
 * 「能发给谁」刻意不依赖用户手输 IP：电视上打字很痛苦（遥控器），所以目标 = 
 * **当前连入方**（正在控制本机的那台）优先，其次**曾经连过的对端**（服务端落盘记忆）。
 * 这也符合实际用法：手机连上电视 → 电视收到的文件再发回这台手机。
 */
object TvFiles {

    /** 接收目录：与 [TvFileReceiver] 的落盘位置必须一致（<外部文件>/APX） */
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

    /** 人类可读大小 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
