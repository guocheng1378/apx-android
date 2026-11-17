package com.allperiph

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.allperiph.shared.util.Log
import com.allperiph.shared.util.LogExport
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
 *
 * **两份文件各管一头（2026-11-15 取证链路补强）**：启动日志是「全量重写式」的——每次
 * 进程启动 marks 清零重写，下一次成功启动会把上一次的崩溃痕迹整个抹掉；而「装新版后
 * 崩一次、之后正常」恰是最常见的样本形态，证据必须在覆盖前分家。所以崩溃时额外调
 * [snapshot] 另存一份带时间戳的崩溃快照（`下载/AllPeriph/apx-logs-crash@<设备名>-<时间>.txt`，
 * 与运行日志导出同一套 [LogExport] 命名口径），正常启动永远不写快照，多次崩溃各留一份
 * 不互相覆盖。
 */
object CrashBox {

    private const val TAG = "CrashBox"

    /** 落到「下载」里的文件名，用户一眼认得；带日期便于多次取证 */
    private const val DISPLAY = "AllPeriph启动日志.txt"

    /**
     * 崩溃快照数量护栏（2026-11-16 自主唤醒审查）：快照每次崩溃新增一份、MediaStore 同名
     * 只加后缀不覆盖，崩溃循环（如「启动即崩反复点开」）会在下载目录持续堆积；到这个
     * 上限就不再新增 —— 最近的 20 份足够排查，避免无限膨胀。只停新增、不删旧文件
     * （删除外部数据须用户确认，这里不碰）。
     */
    private const val SNAPSHOT_CAP = 20

    /** 启动链路标记：最后一条 = 进程走到的最远处 */
    private val marks = ArrayList<String>()

    private var resolver: android.content.ContentResolver? = null
    private var mediaUri: Uri? = null

    /** 崩溃快照落盘用：[LogExport.saveToDownloads] 需要完整 Context，resolver 不够（取 applicationContext，进程内恒可用） */
    private var appCtx: Context? = null

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
            appCtx = context.applicationContext
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
                    // 崩溃现场另存快照（见类文档「两份文件各管一头」）：必须在 flush 之后、
                    // 异常交还系统之前做完，失败静默 —— 快照丢了大不了还剩启动日志兜底，
                    // 绝不能为它再抛第二异常。
                    snapshot()
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
                // 两段式插入（IS_PENDING，2026-11-18 修）：先在本应用 pending 区建档、
                // 写完再转正。此前直接按最终名插进公共 Download，文件名又是固定的
                // [DISPLAY]，第二次启动起目录里就躺着同名旧文件，部分 ROM（HyperOS
                // 实测）这时 unique 命名直接抛 IllegalArgumentException
                // 「Failed to build unique file」，黑匣子从此每次落盘都失败、只剩
                // 私有目录兜底。与 LogExport.saveToDownloads、LocalSendService 的
                // 既有写入口径对齐 —— 仓里另外两处 MediaStore 写入都是这个写法。
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                // 建档前先清掉本应用上次留下的同名旧档（含上次进程崩溃没转正的
                // pending 档）：黑匣子是「全量重写」语义，旧档轻则越攒越多
                // 「 (1)」副本，重则在部分 ROM 上卡住后面的转正环节。先删后插，
                // 转正时最终名一定空着。删不掉不拦 —— 大不了多份副本，黑匣子不能停摆。
                deleteStaleOwnRows(r)
                val pending = r.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: return
                mediaUri = pending
                pending
            }
            r.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                ?: run {
                    // 流打不开多半是这条记录已经废了（如被用户在文件管理器删掉）：
                    // 清缓存让下一轮 flush 重新 insert，而不是抱着坏 URI 写到天荒地老
                    mediaUri = null
                    return
                }
            // 写通即转正（IS_PENDING=0）；转正撞名或失败不拦 —— 文件留在 pending 区，
            // 本应用照常可读写，下一轮 flush 继续全量重写
            runCatching {
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.onFailure { t -> Log.w(TAG, "黑匣子转正失败（保持 pending，不影响续写）：${t.message}") }
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

    /**
     * 清掉下载集合里本应用自己的同名旧档（[DISPLAY]，见 [flush] 的先删后插）：
     * 只挑 owner 是本包的行 —— 卸载重装前的孤儿档也在其列，别人的同名文件查得到
     * 也删不动。任何异常就地吞掉，由调用方决定照常继续。
     */
    private fun deleteStaleOwnRows(r: android.content.ContentResolver) {
        val pkg = appCtx?.packageName ?: return
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            r.query(
                downloads,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(DISPLAY, pkg),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    r.delete(android.content.ContentUris.withAppendedId(downloads, c.getLong(idCol)), null, null)
                }
            }
        }.onFailure { t -> Log.w(TAG, "黑匣子旧档清理失败（保留副本）：${t.message}") }
    }

    /**
     * 崩溃现场快照（只在崩溃钩子里调用）：写
     * `下载/AllPeriph/apx-logs-crash@<设备名>-<时间>.txt`，命名走 [LogExport.fileName]
     * 与运行日志导出**同一套口径**，用户把几份文件摆在一起也分得清谁是谁。
     *
     * 文件头带版本与机型 —— 远端收文件的人不用再追问「哪个版本装的」；正文就是当时
     * 的全量 marks（含崩溃行与堆栈，[flush] 前刚写好）。LogExport 的 MediaStore 路径
     * 同名自动加 ` (1)` 不覆盖，多次崩溃各留一份；写不进去时 LogExport 抛异常，
     * 这里 [runCatching] 兜住记日志即可 —— 启动日志那份兜底还在。
     */
    private fun snapshot() {
        val ctx = appCtx ?: return
        // 数量护栏：查询失败不拦截落盘（宁可多存一份也别漏证据），只有确认超上限才跳过
        val overCap = runCatching { snapshotCount() >= SNAPSHOT_CAP }.getOrDefault(false)
        if (overCap) {
            Log.w(TAG, "崩溃快照已达上限 $SNAPSHOT_CAP 份，本次不再新增")
            return
        }
        runCatching {
            val head = buildString {
                appendLine("AllPeriph 启动崩溃快照")
                appendLine("App 版本：${BuildConfig.VERSION_NAME}")
                appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine()
            }
            val body = synchronized(marks) { head + marks.joinToString("\n") + "\n" }
            LogExport.saveToDownloads(ctx, LogExport.fileName("crash", ctx), body)
        }.onFailure { Log.w(TAG, "崩溃快照落盘失败：${it.message}") }
    }

    /**
     * 现存崩溃快照数：按文件名前缀在 MediaStore 下载集合里数（LIKE 匹配 apx-logs-crash@%，
     * 不挑子目录——快照都在「下载/AllPeriph」，但按名匹配对将来挪目录也稳）。
     * 查询异常抛给调用方处理，这里不吞。
     */
    private fun snapshotCount(): Int {
        val r = resolver ?: return 0
        return r.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("apx-logs-crash@%"),
            null,
        )?.use { it.count } ?: 0
    }
}
