package com.allperiph.shared.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出的**公共口径**（issue #52 第 3 条：这条体验要「安卓 / TV、win、Mac 一起优化」，
 * 不能只修 mac 端）。
 *
 * 手机端与电视端共用这里；PC 端三平台用 C++ 侧的 `apxpc::logexport`。
 * 两端**命名规则一字不差**，用户把几份日志摆在一起也分得清谁是谁：
 *
 *     apx-logs-<类型>@<设备标识>-<yyyyMMdd-HHmmss>.txt
 *
 * 例：`apx-logs-logcat@PJZ110-20261101-093012.txt`
 *     `apx-logs-tvlog@MiTV4A-20261101-093012.txt`
 *
 * 为什么要有 `@设备标识`：以前叫 `log_1793466844855.txt`，一台机自己看还行，
 * 一旦 pad + 手机 + 电脑三台的日志摆在一起，**光看文件名完全不知道是谁的**，
 * 还得逐个打开核对里面那行 `Build.MODEL`。
 */
object LogExport {

    /** 手机端落盘目录（相对 Download）：与 PC 端「下载/AllPeriphLogs」对齐，一眼找得到 */
    private const val PUBLIC_DIR = "AllPeriph"

    /** 电视端落盘目录名：与「收到的文件」同一个目录（<外部文件>/APX），见 [saveToReceivedDir] */
    private const val RECV_DIR = "APX"

    /**
     * 导出文件名：`apx-logs-<kind>@<型号>-<stamp>.txt`。
     *
     * [kind] 传英文小写短名（`logcat` / `log` / `tvlog`），它只是文件名里的一段、
     * 不直接进界面，所以不走 strings.xml。
     *
     * 型号里只留 `[A-Za-z0-9._-]`：`Build.MODEL` 有带空格括号的（如 `Pixel 7 (Pro)`），
     * 空格会被某些文件管理器截断、括号与中文跨设备拷贝后可能变乱码。
     * 全被过滤掉时回落 `Android`（不回落空串 —— 那会让文件名里出现 `@@`，看着像 bug）。
     */
    fun fileName(kind: String): String {
        val model = Build.MODEL.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .ifBlank { "Android" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return "apx-logs-${kind}@${model}-${stamp}.txt"
    }

    /** 文件头用的时间戳：`yyyy-MM-dd HH:mm:ss` */
    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /**
     * 手机端：写进 `下载/AllPeriph/<fileName>`，返回**可直接显示给用户的落点**。
     *
     * API 29+ 走 MediaStore，**不需要任何存储权限**，同名自动加 ` (1)` 不覆盖
     * （手机端 minSdk 34，实际总走这一条）；API 23–28 的兜底写进应用外部目录，
     * 不让一个日志导出反过来逼用户开存储权限。
     *
     * 写不进去时抛异常 —— 调用方负责提示失败原因，这里不吞（吞掉就成「点了没反应」）。
     */
    /**
     * [subDir] 为 `null` 时沿用默认目录 `下载/AllPeriph`；给了就用 `下载/<subDir>`。
     * 只保留 `[A-Za-z0-9._-]` 与一级路径，避免 `..` 之类把文件写到下载目录之外。
     */
    fun saveToDownloads(ctx: Context, fileName: String, body: String, subDir: String? = null): String {
        val relDir = if (subDir.isNullOrBlank()) PUBLIC_DIR else sanitizeSubDir(subDir)
        val safeName = sanitizeName(fileName)
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + relDir,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw IllegalStateException("insert failed")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream returned null")
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return "${Environment.DIRECTORY_DOWNLOADS}/$relDir/$safeName"
        }
        val dir = appDir(ctx)
        writeFile(dir, safeName, bytes)
        return File(dir, safeName).absolutePath
    }

    /**
     * 电视端：写进 `<外部文件>/APX/`，也就是**「收到的文件」那个目录**。
     *
     * 为什么不跟手机端一样进 Download：电视上根本没有文件管理器，
     * Download 里的东西用户**拿不出来**；而 `APX` 里的文件会直接出现在电视主页的
     * 「收到的文件」列表里，那里本来就支持**把它发回手机 / 电脑** —— 于是
     * 「导出日志」在电视上就成了一条真正走得通的链路，不需要用户开存储权限、
     * 也不需要新增任何界面。
     *
     * 代价是这个日志会出现在「收到的文件」列表里。文件名 `apx-logs-tvlog@…` 自带说明，
     * 用户一眼分得清它不是谁发来的文件。
     */
    fun saveToReceivedDir(ctx: Context, fileName: String, body: String): String {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, RECV_DIR)
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeFile(dir, fileName, bytes)
        return File(dir, fileName).absolutePath
    }

    // —— 内部 ——

    /** 目录名只留安全字符，且只允许一级（`a/b` 这类会被压成 `a_b`） */
    private fun sanitizeSubDir(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._/-]"), "").replace("/", "_").ifBlank { PUBLIC_DIR }

    /** 文件名同理：去掉路径分隔符与控制字符，空名回落默认前缀 */
    private fun sanitizeName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "apx-logs.txt" }

    private fun appDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, PUBLIC_DIR)

    private fun writeFile(dir: File, name: String, bytes: ByteArray) {
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("mkdir failed: $dir")
        File(dir, name).writeBytes(bytes)
    }
}