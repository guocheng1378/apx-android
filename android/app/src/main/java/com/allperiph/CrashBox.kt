package com.allperiph

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.allperiph.shared.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动黑匣子（2026-11-14）：v223.29.99 有用户装上后「点开无反应、无崩溃提示」，但本机
 * 无法远程取日志、本地无 SDK 无法复现。这里补一个 **进程自记文件** 的通道：
 *
 *  - [install] 在进程起点（Application.onCreate 第一行）装一个全局未捕获异常钩子，
 *    崩溃时把现场（阶段标记 + 堆栈）写进公共「下载」目录的文本文件，用户拷贝即可回传；
 *  - [mark] 由启动链路各关键点调用（进程启动 / 主壳启动 / 界面组合），无论最终崩在哪，
 *    文件里都留着最后走过的路；**不崩也留** —— 文件存在但只到「进程启动」就停，
 *    本身就是「进程被系统拦下、代码没跑起来」的实锤证据。
 *
 * 落盘走 MediaStore（API 29+ 共享下载目录，无需存储权限；本项目 minSdk=34 恒可用），
 * MediaStore 异常时退回应用私有外部目录兜底 —— 崩溃钩子里绝不能再抛第二异常。
 * 文件每次重写全量内容（标记很少，整文件 < 几 KB），避免追加语义在 MediaStore 上难做。
 */
object CrashBox {

    private const val TAG = "CrashBox"

    /** 落到「下载」里的文件名，用户一眼认得；带日期便于多次取证 */
    private const val DISPLAY = "AllPeriph启动日志.txt"

    /** 启动链路标记：最后一条 = 进程走到的最远处 */
    private val marks = ArrayList<String>()

    private var resolver: android.content.ContentResolver? = null
    private var mediaUri: Uri? = null

    /** MediaStore 不可用（极个别 ROM 限制）时的私有目录兜底，用户需从开发者选项导出 */
    private var fallback: File? = null

    /** 原有异常钩子：写完黑匣子后原样交还，保持系统弹窗 / 统计等既有行为 */
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 进程起点第一件事。装钩子本身必须包在 runCatching 里 —— 若这里抛，整个 App
     * 连崩溃提示都不会有，比不装更糟。重复调用只生效一次（ContentProvider 多进程
     * 场景下 Application 可能多次 onCreate，同一进程装两遍钩子会递归）。
     */
    fun install(context: Context) {
        if (previousHandler != null) return
        try {
            resolver = context.contentResolver
            fallback = context.getExternalFilesDir(null)
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    synchronized(marks) {
                        marks.add("崩溃！线程 ${t.name}：${e.javaClass.name}：${e.message}")
                        val sw = java.io.StringWriter()
                        e.printStackTrace(java.io.PrintWriter(sw))
                        marks.add(sw.toString())
                    }
                    flush()
                } catch (_: Throwable) {
                    // 黑匣子自己再坏也不能影响系统原有的崩溃上报路径
                }
                previousHandler?.uncaughtException(t, e)
            }
            Log.i(TAG, "启动黑匣子已装")
        } catch (t: Throwable) {
            Log.w(TAG, "启动黑匣子安装失败：${t.message}")
        }
    }

    /**
     * 启动链路打点：从进程启动到界面组合，每个关键点一句。文件随打随落盘，
     * 「最后一条标记」就是进程实际走到的位置 —— 即使随后进程被杀（无崩溃弹窗），痕迹也在。
     */
    fun mark(stage: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(marks) { marks.add("$ts  $stage") }
        flush()
    }

    /** 全量重写日志文件；MediaStore 优先，异常退私有目录。所有异常吞掉记日志即可 */
    private fun flush() {
        val body = synchronized(marks) { marks.joinToString("\n") + "\n" }
        try {
            val r = resolver ?: return
            val uri = mediaUri ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { mediaUri = it }
                    ?: return
            }
            r.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return
        } catch (t: Throwable) {
            Log.w(TAG, "黑匣子 MediaStore 落盘失败：${t.message}")
        }
        try {
            fallback?.let { f ->
                File(f, "crashbox.txt").writeText(body, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "黑匣子私有目录落盘失败：${t.message}")
        }
    }
}
