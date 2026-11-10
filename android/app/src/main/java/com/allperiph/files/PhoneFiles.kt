package com.allperiph.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.proto.ApxFrame.PhoneFileEntry
import com.allperiph.shared.util.Log
import java.io.File

/**
 * 手机文件浏览（「文件管理」方向三，2026-11-11，协议 0x44–0x47、能力位 bit3）。
 *
 * 让**电脑/控制端**浏览本机目录并取走文件：0x44 问目录（0x45 应答）、0x46 取文件
 * （文件本体经 [FileSender] 的 9512 通道推回，失败用 0x47 回执）。
 * 实现落 app 模块，由三条控制链路接线（[com.allperiph.controlled.ControlledService]
 * 注入 ：shared ControlServer / app TvControlServer 的 onPhoneFileCmd；
 * [com.allperiph.wireless.TvControllerClient] 直调）。
 *
 * ## 权限边界（刻意收窄的两条）
 *  1. 只列/只取普通文件，目录整包下载与删除/重命名**不做**——删除类操作只能在本机
 *     文件面板（FilePanelActivity）里做，那里有确认弹窗；远程通道不给写能力。
 *  2. 浏览范围=公共外部存储（/storage/emulated/…）。Android 11+ 需要「所有文件访问」
 *     （MANAGE_EXTERNAL_STORAGE，用户在系统设置里授）；没授就回 ERR_NO_PERM，
 *     文件面板里有「去授权」入口，PC 端如实提示，不假装列表为空。
 *     授权前应用私有目录（接收文件的 APX 目录）不受影响——那是 getExternalFilesDir。
 */
object PhoneFiles {

    private const val TAG = "PhoneFiles"

    /** 本端能力位：0x33 能力协商里上报（CAP_PHONE_FILES），支持浏览就报 */
    const val CAP = ApxFrame.CAP_PHONE_FILES

    /** 是否已授予「所有文件访问」：API 30+ 看 isExternalStorageManager，更老版本看旧权限 */
    fun hasStorageAccess(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // API 29-：申请过 READ 即可读公共目录（本 app 在 30+ 才开此功能，这里是兜底）
            ctx.checkUriOrLegacyPermission()
        }
    }.getOrDefault(false)

    private fun Context.checkUriOrLegacyPermission(): Boolean =
        checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 「去授权」系统页入口（文件面板用）：所有文件访问的开关在系统设置里 */
    fun storageSettingsIntent(ctx: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        }
    }.getOrNull()

    /**
     * 根入口（0x44 path 为空时回报）：从最常去的地方开始逛。
     * 返回的都是**目录名**，不含路径 —— 对端拿到名字后拼绝对路径再请求
     *（公共外部存储固定在 [STORAGE_ROOT]，见 listAndReply 的相对路径兜底）。
     * 「内部存储」指存储根目录本身（想看全部目录时的入口）。
     */
    private fun roots(): List<PhoneFileEntry> {
        val base = Environment.getExternalStorageDirectory() ?: return emptyList()
        val names = listOf("Download", "DCIM", "Pictures", "Movies", "Music", "Documents", "")
        return names.mapNotNull { n ->
            val f = if (n.isEmpty()) base else File(base, n)
            if (f.isDirectory) PhoneFileEntry(n.ifEmpty { "内部存储" }, isDir = true, size = 0) else null
        }
    }

    /**
     * 三条控制链路的统一入口（回调/直调都落这里）。reader 线程调用 —— 目录 IO 与
     * 9512 发送都是阻塞活，全部甩到专用线程，不卡注入队列。
     * @param cmd 0x44=浏览 0x46=取文件；body=完整帧载荷（含 cmd 字节）
     * @param reply 应答通道（0x45/0x47 走原控制链路返回）
     * @param peerHost 对端 IP（0x46 经 9512 推送的目标）
     */
    fun handle(cmd: Int, body: ByteArray, reply: (ByteArray) -> Boolean, peerHost: String?) {
        // 载荷：[cmd][pathLen u16 LE][path...]，cmd 字节后的最短长度 3
        if (body.size < 3) return
        val pathLen = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if (body.size < 3 + pathLen) return
        val path = String(body, 3, pathLen, Charsets.UTF_8)
        Thread({
            when (cmd) {
                ApxFrame.CMD_PHONE_FILE_LIST -> listAndReply(path, reply)
                ApxFrame.CMD_PHONE_FILE_PULL -> pullAndSend(path, peerHost, reply)
            }
        }, "apx-phone-files").start()
    }

    /** 0x44 → 0x45：列出目录（path 空 = 根入口）。err 取值见 ApxFrame.PHONEFILE_ERR_* */
    private fun listAndReply(path: String, reply: (ByteArray) -> Boolean) {
        val ctx = com.allperiph.App.context
        if (ctx != null && !hasStorageAccess(ctx)) {
            Log.w(TAG, "浏览被拒：未授予「所有文件访问」权限")
            reply(ApxFrame.packPhoneFileListReply(ApxFrame.PHONEFILE_ERR_NO_PERM, path, emptyList()))
            return
        }
        if (path.isEmpty()) {
            reply(ApxFrame.packPhoneFileListReply(ApxFrame.PHONEFILE_ERR_NONE, "", roots()))
            return
        }
        // 相对路径按公共外部存储根解析（兜底）：对端正常拼绝对路径请求，但「Download」
        // 这类根入口名字直接发回来的对端不该因此拿不到 —— 解析口径与 roots() 对齐。
        val f0 = File(path)
        val dir = if (f0.isAbsolute) f0 else File(Environment.getExternalStorageDirectory(), path)
        if (!dir.isDirectory) {
            reply(ApxFrame.packPhoneFileListReply(ApxFrame.PHONEFILE_ERR_NOT_FOUND, path, emptyList()))
            return
        }
        // 列目录：listFiles() 给的是 Array<File>，先按「目录在前、名称不区分大小写」排好，
        // 再映射成协议条目（isDir/长度都是 File 自带字段，一次遍历不重复 stat）。
        // runCatching 兜 IO 异常：失败回 READ 错误码，PC 端如实提示，不给空列表装正常。
        val list = runCatching {
            dir.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?.map { f -> PhoneFileEntry(f.name, f.isDirectory, if (f.isFile) f.length() else 0L) }
                .orEmpty()
        }.getOrElse {
            reply(ApxFrame.packPhoneFileListReply(ApxFrame.PHONEFILE_ERR_READ, path, emptyList()))
            return
        }
        reply(ApxFrame.packPhoneFileListReply(ApxFrame.PHONEFILE_ERR_NONE, path, list))
    }

    /**
     * 0x46 → 文件本体（9512 推送）。成功不发帧（文件到达即回执）；
     * 失败发 0x47（PC 端据此把按钮从「取回中」还原）。注意 FileSender 的 name 取文件名，
     * 对端落在「收到的文件」目录 —— 不会写别的位置（接收端路径固定，见 FileReceiver）。
     */
    private fun pullAndSend(path: String, peerHost: String?, reply: (ByteArray) -> Boolean) {
        if (peerHost.isNullOrBlank()) {
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_SEND_FAILED, path))
            return
        }
        val f = File(path)
        // 只发普通文件：目录/不存在/路径怪（比如想拿设置数据库）都不给 —— 写能力不上远程通道
        if (!f.isFile || f.isDirectory) {
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_NOT_FOUND, path))
            return
        }
        val ok = FileSender.send(peerHost, f)
        if (!ok) {
            Log.w(TAG, "推文件失败：$path → $peerHost")
            reply(ApxFrame.packPhoneFileFail(ApxFrame.PHONEFILE_ERR_SEND_FAILED, path))
        } else {
            Log.i(TAG, "文件已推给对端：$path → $peerHost")
        }
    }
}
