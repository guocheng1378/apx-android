package com.allperiph.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.allperiph.R
import com.allperiph.ui.miuix.ApComponents
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * 「语音」工具键的识别宿主页（2026-11-11 成宇「语音按键链路不能用」）。
 *
 * 系统语音识别必须由 Activity 发起（startActivityForResult 要宿主），而悬浮球 overlay
 * 里的键盘面板只有 Service 上下文 —— 旧实现在这个场景只弹「语音要在主屏键盘页使用」，
 * 悬浮键盘的语音键成了死键。改走与 [PhotoPermActivity] 同一套「透明页承载」先例：
 * 透明主题页进来就拉系统识别、拿到结果经伴生回调投回订阅方、随即 finish，全程无感。
 *
 * v223.29.60（成宇「弹了系统语音识别不可用」）：国内 ROM 普遍**没有** RECOGNIZE_SPEECH
 * 处理器（Google 引擎也没有），系统识别这条路在用户机上走不通 —— 有非 Google 系统识别器
 * 时仍优先走系统识别（识别质量与耗电都更好），否则兜底内置**离线识别**（Vosk，
 * Apache-2.0，模型 vosk-model-small-cn-0.22 同为 Apache-2.0）：首次使用点一下按钮下载
 * 约 40MB 语音包到内部存储，之后识别全程本地不联网。旧口径「只有 Google 语音时建议用
 * 输入法语音」一并退役 —— 有本地方案可用了，不再把用户往外推。
 *
 * ACTION / EXTRA 字面值与 SpeechRecognizer 源码逐字一致（公开协议常量，零符号依赖）。
 */
class VoiceCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VoiceCapture"

        /** 识别请求码（仅本页内部使用） */
        private const val REQ_SPEECH = 4151

        /**
         * 离线语音包来源（Vosk 官方模型仓库，Apache-2.0；小模型体积/精度权衡最佳）。
         * 成宇 2026-11-12「语音包下不了，是没有加速源」：官方源在境内网络常年连不上，
         * 第一版加 hf-mirror 镜像——但成宇 2026-11-12 21:2x「语音链路还是不行，下载不下来」
         * 复查发现 hf-mirror 对 xet 仓库的 resolve 请求 302 跳海外 CDN（cas-bridge.xethub.hf.co，
         * us-east-1），境内到最后一跳照样不通，镜像等于没绕出去；官方源实测慢到 20KB/s 量级。
         * 现改用魔搭 ModelScope 的同内容镜像仓库（真·国内 CDN，零跳转；zip 与官方同结构同体积
         * 43898754 字节，必备文件逐一核对），置于首位；官方与 hf-mirror 留作后备。
         * 任一路走通都过「非 2xx / 包过小 / 必备文件齐全」三道关（见 [MODEL_REQUIRED_FILES]），
         * 不是同一份模型就判失败，不给残包/换包上机加载的机会。
         */
        private val MODEL_URLS = listOf(
            "https://modelscope.cn/models/Bovin12/vosk-model-small-cn-0.22/resolve/master/vosk-model-small-cn-0.22.zip",
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            "https://hf-mirror.com/guloooovoooo/vosk-model-small-cn/resolve/main/vosk-model-small-cn.zip",
        )

        /**
         * 模型包必备文件（解压后统一校验，官方源/镜像/SAF 导入共用）：任一缺失判失败。
         * 这几项是 Vosk Model() 起识别的硬依赖，缺了加载必崩 —— 与其带残模型进识别，
         * 不如当场报失败让用户重试/换源。
         */
        private val MODEL_REQUIRED_FILES = listOf(
            "conf/model.conf",
            "am/final.mdl",
            "graph/Gr.fst",
        )

        /** 离线模型落盘目录名（内部存储；目录内有 .ok 标记 = 下载+解压完整） */
        private const val MODEL_DIR = "vosk-model-small-cn-0.22"

        /**
         * 解压总量上限（全审查条 14）：模型包解压后实际 ~40MB，留 6 倍余量 ——
         * 上游 zip 被换成 zip 炸弹时，解压到上限即抛错，不写满内部存储。
         */
        private const val MAX_UNZIP_BYTES = 256L * 1024 * 1024

        /**
         * 语音包 zip 的最小可信体积（#6「检测实际是否下载」）：模型压缩包 ~40MB，
         * 劫持页/错误页/半截包都远小于它 —— 低于阈值判失败，不浪费解压与用户时间。
         */
        private const val MIN_ZIP_BYTES = 10L * 1024 * 1024

        /**
         * 识别结果投递口：KeyboardPage 的键盘面板在组合期挂上（DisposableEffect 挂 / 摘，
         * 防 Service 面板重建后旧回调滞留）。传 null = 没听清 / 用户取消 / 识别不可用，
         * 由订阅方决定提示与清态。
         */
        @Volatile
        var onResult: ((String?) -> Unit)? = null

        /** 全仓唯一启动入口（悬浮键盘 Service 场景需 NEW_TASK 标志；check_entrypoints 认 X.start(） */
        fun start(c: Context) = c.startActivity(intent(c))

        private fun intent(c: Context): Intent =
            Intent(c, VoiceCaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
    }

    // —— 离线识别的运行期句柄（生命周期 = 本 Activity；onDestroy 里兜底回收）——
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    /** 结果只投递一次的闸门：静音收束 / 超时 / 出错 / 手动「结束」可能先后到达 */
    private var delivered = false

    /** 离线路径的界面状态（Compose 驱动；系统识别路径不碰它） */
    private var offlineState by mutableStateOf<Offline>(Offline.NeedDownload)

    /** 必须在 onCreate 前注册（属性初始化器在构造期执行，PhotoPermActivity 同款注释） */
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startOfflineListening() else offlineState = Offline.NoMicPerm
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 双路分发：有非 Google 系统识别器走老路（透明无感）；否则进离线面板（可见卡片）
        if (hasNonGoogleRecognizer()) {
            launchSpeech()
        } else {
            setContent { OfflinePanel() }
            // 成宇 2026-11-13 04:0x「再次点又回到了第一张图」：offlineState 初值是
            // NeedDownload，此前重进本页只画面板、没推状态机 —— 语音包明明已就绪，
            // 第二次进来仍停在下载引导卡，要再走一遍下载/导入才能用。补统一入口
            // startOfflineListening()：模型好了直接进识别，没好时该入口本身会置回
            // NeedDownload（行为与旧面板一致），有权限问题也按既有分支给出引导。
            startOfflineListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseEngine()
    }

    // ==================== 路径一：系统识别（原链路，口径未动） ====================

    /** 只认非 Google 的系统识别器（v223.29.33 结论：国内 Google 语音网络不可达，宁可离线） */
    private fun hasNonGoogleRecognizer(): Boolean = packageManager
        .queryIntentActivities(
            Intent("android.speech.action.RECOGNIZE_SPEECH"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        .any { !(it.activityInfo?.packageName ?: "").startsWith("com.google.android") }

    /** 挑选识别器并拉起：挑选口径与旧 KeyboardPage.launchKeyboardVoice 逐字一致 */
    private fun launchSpeech() {
        val intent = Intent("android.speech.action.RECOGNIZE_SPEECH").apply {
            putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
            putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "zh-CN")
            putExtra("android.speech.extra.LANGUAGE", "zh-CN")
        }
        val pick = packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { !(it.activityInfo?.packageName ?: "").startsWith("com.google.android") }
        if (pick == null) { // 理论不可达（onCreate 已判过）；防御性兜底进离线面板
            setContent { OfflinePanel() }
            startOfflineListening()
            return
        }
        intent.setPackage(pick.activityInfo.packageName)
        runCatching { startActivityForResult(intent, REQ_SPEECH) }
            .onFailure {
                Log.w(TAG, "识别页拉起失败：${it.message}")
                toast(R.string.ui_kb_voice_unavailable)
                finishDeliver(null)
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH) return
        // EXTRA_RESULTS 字面值与 SpeechRecognizer 源码逐字一致（同旧 launchKeyboardVoice 口径）
        val t = data?.getStringArrayListExtra("android.speech.extra.RESULTS")
            ?.firstOrNull().orEmpty()
        finishDeliver(t.ifBlank { null })
    }

    // ==================== 路径二：内置离线识别（Vosk） ====================

    /** 离线路径的界面状态（sealed 让 when 穷尽分支） */
    private sealed class Offline {
        /** 模型未就绪：给「下载语音包并开始」入口 */
        data object NeedDownload : Offline()

        /** 下载中：百分比进度（0–100） */
        data class Downloading(val percent: Int) : Offline()

        /**
         * 下载/解压/加载失败：给「重试」入口。成宇 2026-11-12 22:4x「语音模型下载完了说
         * 下载失败，是否链路有问题」——此前三类失败（下载链路 / 解压校验 / 引擎加载）
         * 共用一个「下载失败」文案，下载满格后报失败分不清是哪一环；Failed 改带具体
         * 原因显示在面板上，链路哪一环断了当场可见。
         */
        data class Failed(val reason: String) : Offline()

        /** SAF 导入中（issue#168 2026-11-12 #6 兜底：本地 zip 拷入 + 解压需要几秒到十几秒） */
        data object Importing : Offline()

        /** 麦克风权限被拒：给「允许麦克风」入口 */
        data object NoMicPerm : Offline()

        /** 模型加载中（首载 1–3 秒，给个态免得像卡死） */
        data object Preparing : Offline()

        /** 识别中：partial 为当前实时识别文本（空白 = 还没听清） */
        data class Listening(val partial: String) : Offline()
    }

    private fun modelDir(): File = File(filesDir, MODEL_DIR)

    /** .ok 标记是下载+解压**都**成功后由我们写入的 —— 目录半途残缺也能被识别出来重下 */
    private fun modelReady(): Boolean =
        modelDir().exists() && File(modelDir(), ".ok").exists()

    /** 离线启动的统一入口：模型没好 → 引导下载；权限没有 → 申请；都齐 → 起识别 */
    private fun startOfflineListening() {
        if (isFinishing) return
        if (!modelReady()) {
            offlineState = Offline.NeedDownload
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            offlineState = Offline.NoMicPerm
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (speech != null) return // 识别已在跑（重进面板等场景），别把 AudioRecord 顶掉
        // Preparing 守卫：模型加载线程在后台跑时 offlineState 已置 Preparing，
        // 期间 micPerm 授权回调、下载完成回调度 / launchSpeech 兜底再进来，
        // 没有这一守卫会并发起第二个线程 —— 后建的 Model/SpeechService 引用
        // 顶掉先建的（字段被覆盖），先建那份的 AudioRecord 永远被占、无法回收。
        if (offlineState is Offline.Preparing) return
        offlineState = Offline.Preparing
        Thread {
            try {
                // Model/Recognizer/SpeechService 构造要 JNI + 大内存，一律后台线程做，
                // 完成后回主线程置 Listening（SpeechService.startListening 自带采集线程）
                model = Model(modelDir().absolutePath)
                recognizer = Recognizer(model, 16000.0f)
                val svc = SpeechService(recognizer, 16000.0f)
                speech = svc
                svc.startListening(offlineListener)
                runOnUiThread { offlineState = Offline.Listening("") }
            } catch (e: Throwable) {
                // Throwable 而不是 Exception：Model 构造走 JNI + 大内存，OOM 之类的
                // Error 一旦漏出会杀线程而不走 Failed，页面卡死在「加载中」
                Log.w(TAG, "离线识别初始化失败：${e.message}")
                releaseEngine()
                runOnUiThread {
                    offlineState = Offline.Failed(
                        getString(R.string.ui_voice_fail_load, e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    /** Vosk 回调全部落在其采集线程上，状态与投递统一回主线程 */
    private val offlineListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            val p = runCatching { JSONObject(hypothesis).optString("partial") }
                .getOrNull().orEmpty()
            runOnUiThread {
                if (offlineState is Offline.Listening) offlineState = Offline.Listening(p)
            }
        }

        override fun onResult(hypothesis: String) = finishWithResult(hypothesis)

        override fun onFinalResult(hypothesis: String) = finishWithResult(hypothesis)

        override fun onError(exception: Exception) {
            Log.w(TAG, "离线识别出错：${exception.message}")
            runOnUiThread { finishDeliver(null) }
        }

        override fun onTimeout() {
            // 停留过久自动收：有部分结果就交出去，没有按没听清处理
            val p = (offlineState as? Offline.Listening)?.partial
            runOnUiThread { finishDeliver(p?.takeIf { it.isNotBlank() }) }
        }
    }

    /** Vosk 的终态 JSON 是 {"text": "..."}（partial 是 {"partial": "..."}），字段名同源码口径 */
    private fun finishWithResult(json: String) {
        val text = runCatching { JSONObject(json).optString("text") }.getOrNull().orEmpty()
        runOnUiThread { finishDeliver(text.takeIf { it.isNotBlank() }) }
    }

    /** 下载语音包（后台线程，进度回主线程）→ 解压 → 打 .ok 标记 → 直接进识别 */
    private fun downloadAndPrepare() {
        // 全审查条 3：进行中守卫 —— 按钮连点 / 重试竞态不再并发写同一个 zip（会损坏）。
        // offlineState 在主线程同步检查、同步置位，下载线程启动前就挡住二次进入。
        if (offlineState is Offline.Downloading || offlineState is Offline.Importing) return
        offlineState = Offline.Downloading(0)
        Thread {
            // issue#168 2026-11-12 #6「下载语音包还是失败」+ 同日成宇「没有加速源」：
            // 官方源在用户网络直连常年失败（跨境链路抖动 / DNS 污染）。逐路直连
            // [MODEL_URLS]（魔搭国内 CDN → 官方 → hf-mirror），任一路走通即收；
            // 每一路都要过「非 2xx / 包过小 / 必备文件齐全」三道关才算成功。
            val ok = MODEL_URLS.any { url -> tryDownloadDirect(url) != null } ||
                MODEL_URLS.any { url -> tryDownloadViaManager(url) != null }
            if (!ok) {
                // 清掉半截下载产物，重试从零开始（全审查条 3 的另一半）
                runCatching { File(cacheDir, "$MODEL_DIR.zip.tmp").delete() }
                runCatching { File(cacheDir, "$MODEL_DIR.zip").delete() }
                runOnUiThread { offlineState = Offline.Failed(lastFailReason.ifBlank { getString(R.string.ui_voice_fail_all_sources) }) }
            }
        }.start()
    }

    /** 最近一次失败的具体原因（downloadAndPrepare 收口时带给 Failed 态显示） */
    @Volatile
    private var lastFailReason: String = ""

    /**
     * 直连下载：成功（解压 + 打标完成）返回 null；失败返回原因字符串交给下一路。
     * #6「检测实际是否下载」—— 旧实现不读响应码：运营商劫持页 / CDN 错误页会被
     * 当成下载内容写进文件，解压必炸还看不出原因。现在非 2xx 直接判失败，包过小
     * （模型压缩包 ~40MB，10MB 以下必不是它）同样判失败。
     */
    private fun tryDownloadDirect(url: String): String? {
        val zip = File(cacheDir, "$MODEL_DIR.zip")
        val tmp = File(cacheDir, "$MODEL_DIR.zip.tmp")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            tmp.outputStream().use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastPct = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                runOnUiThread {
                                    offlineState = Offline.Downloading(pct)
                                }
                            }
                        }
                    }
                }
            }
            // 全审查条 3：先写 .tmp 再改名 —— 断网 / 杀进程留下的半个包不会被
            // 当成完整 zip 拿去解压；也只有改名成功（下载完整）才继续。
            if (!tmp.renameTo(zip)) throw IOException("下载产物改名失败")
            if (zip.length() < MIN_ZIP_BYTES) throw IOException("下载产物过小（${zip.length()}B）")
            unzipModel(zip, modelDir())
            File(modelDir(), ".ok").writeText(MODEL_DIR)
            zip.delete()
            runOnUiThread { startOfflineListening() }
            null
        } catch (e: Exception) {
            Log.w(TAG, "直连下载失败：${e.message}")
            runCatching { tmp.delete() }
            runCatching { zip.delete() }
            getString(R.string.ui_voice_fail_direct, url.substringAfterLast('/'), e.message ?: e.javaClass.simpleName)
                .also { lastFailReason = it }
        }
    }

    /**
     * DownloadManager 兜底（#6 第二路）：走系统下载器 —— 自带断点续传 / 重试 / 网络
     * 策略（双卡、Wi-Fi/蜂窝偏好都由系统裁决），与 App 内直连走的是同一批下载节点但
     * 连接生命周期更稳。成功（解压 + 打标完成）返回 null；失败返回原因字符串。
     */
    private fun tryDownloadViaManager(url: String): String? {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return getString(R.string.ui_voice_fail_dm_missing).also { lastFailReason = it }
        val extZip = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$MODEL_DIR.zip")
        val id = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(url)).apply {
                    setTitle(MODEL_DIR)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    // 落 app 私有外部目录：不需要存储权限，卸载即清
                    setDestinationInExternalFilesDir(
                        this@VoiceCaptureActivity, Environment.DIRECTORY_DOWNLOADS, "$MODEL_DIR.zip",
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "DownloadManager 入队失败：${e.message}")
            return getString(R.string.ui_voice_fail_enqueue, e.message ?: e.javaClass.simpleName)
                .also { lastFailReason = it }
        }
        // 轮询到终态（面板上有进度条可看，这里不需要回调粒度更细）
        while (!isFinishing) {
            Thread.sleep(1_000)
            val q = dm.query(DownloadManager.Query().setFilterById(id)) ?: continue
            if (!q.moveToFirst()) {
                q.close()
                continue
            }
            val status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            q.close()
            if (total > 0) {
                val pct = (got * 100 / total).toInt().coerceIn(0, 100)
                runOnUiThread { if (offlineState is Offline.Downloading) offlineState = Offline.Downloading(pct) }
            }
            when (status) {
                // COLUMN_REASON 带系统给出的失败码（如存储不足 / 网络不可用），一并透出
                DownloadManager.STATUS_FAILED -> {
                    val reason = q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON).let {
                        runCatching { q.getInt(it) }.getOrNull() ?: -1
                    }
                    return getString(R.string.ui_voice_fail_dm, reason).also { lastFailReason = it }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    return runCatching {
                        val zip = File(cacheDir, "$MODEL_DIR.zip")
                        extZip.copyTo(zip, overwrite = true)
                        if (zip.length() < MIN_ZIP_BYTES) throw IOException("下载产物过小（${zip.length()}B）")
                        unzipModel(zip, modelDir())
                        File(modelDir(), ".ok").writeText(MODEL_DIR)
                        zip.delete()
                        extZip.delete()
                        runOnUiThread { startOfflineListening() }
                        null
                    }.getOrElse {
                        Log.w(TAG, "DownloadManager 产物处理失败：${it.message}")
                        runCatching { extZip.delete() }
                        getString(R.string.ui_voice_fail_unzip, it.message ?: it.javaClass.simpleName)
                            .also { lastFailReason = it }
                    }
                }
            }
        }
        return getString(R.string.ui_voice_fail_interrupt).also { lastFailReason = it }
    }

    /**
     * SAF 导入语音包（#6「是否需要调用第三方 app 支持」的另一条兜底）：手机网络到
     * 源站走不通时，任何途径拿到的官方 zip（电脑下载后传过来、网盘、浏览器）都能
     * 从文件选择器选进来 —— 校验大小、复用 [unzipModel]（zip-slip 防御同源）。
     */
    private val importZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importZipFrom(uri)
        }

    private fun importZipFrom(uri: Uri) {
        if (offlineState is Offline.Downloading || offlineState is Offline.Importing) return
        offlineState = Offline.Importing
        Thread {
            try {
                val zip = File(cacheDir, "$MODEL_DIR.zip")
                contentResolver.openInputStream(uri)?.use { inp ->
                    zip.outputStream().use { out -> inp.copyTo(out, 64 * 1024) }
                } ?: throw IOException("选中文件读不到")
                if (zip.length() < MIN_ZIP_BYTES) throw IOException("文件过小（${zip.length()}B），不是语音包")
                // 残目录先清再解压：半途失败的解压残骸会与新 zip 的条目混在一起
                runCatching { modelDir().deleteRecursively() }
                unzipModel(zip, modelDir())
                File(modelDir(), ".ok").writeText(MODEL_DIR)
                zip.delete()
                runOnUiThread { startOfflineListening() }
            } catch (e: Exception) {
                Log.w(TAG, "语音包导入失败：${e.message}")
                runCatching { File(cacheDir, "$MODEL_DIR.zip").delete() }
                runOnUiThread {
                    offlineState = Offline.Failed(
                        getString(R.string.ui_voice_fail_import, e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    /**
     * 解压到内部存储；zip-slip 防御：解压结果必须落在模型目录内。
     * 2026-11-12 修双层嵌套：官方 zip（镜像包同构）条目自带顶层目录
     * vosk-model-small-cn-0.22/，原样落盘会变成 modelDir()/顶层目录/… 套两层，
     * Model() 按 modelDir() 找 am/conf 必落空 —— 下载再成功也起不来识别。
     * 现在统一剥掉公共顶层目录再落盘；镜像包里的 __MACOSX/ 元数据与 .DS_Store、
     * ._* 打包垃圾一并丢弃。解压完按 [MODEL_REQUIRED_FILES] 校验必备文件，
     * 缺一抛错 —— 官方源/镜像/SAF 导入三条路共用同一道收口。
     */
    private fun unzipModel(zip: File, target: File) {
        target.mkdirs()
        val targetPath = target.canonicalPath
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().toList()
            // 公共顶层目录：排除打包垃圾后所有条目共享同一段、且包内确有子层级才剥除
            // —— 散装 zip（am/、conf/ 直接在根）的顶层各不相同，不能剥。
            val tops = entries.map { it.name.trimStart('/').substringBefore('/') }
                .filter { it.isNotEmpty() && it != "__MACOSX" }
                .distinct()
            val stripTop = tops.singleOrNull()?.takeIf { top ->
                entries.any { it.name.trimStart('/').removePrefix("$top/").contains('/') }
            }
            val buf = ByteArray(64 * 1024)
            var written = 0L
            for (e in entries) {
                var name = e.name.trimStart('/')
                if (name.isEmpty()) continue
                if (stripTop != null && name.startsWith("$stripTop/")) {
                    name = name.removePrefix("$stripTop/")
                    if (name.isEmpty()) continue
                }
                // 打包垃圾（macOS 元数据 / Finder 副本）：可能出现在任意层级，逐条丢弃
                val fileName = name.substringAfterLast('/')
                if (name.startsWith("__MACOSX/") || name == "__MACOSX" ||
                    fileName == ".DS_Store" || fileName.startsWith("._")
                ) continue
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(targetPath + File.separator)) {
                    throw IOException("zip-slip: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { inp ->
                        FileOutputStream(out).use { f ->
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                f.write(buf, 0, n)
                                // 全审查条 14：解压总量上限，防 zip 炸弹写满内部存储
                                written += n
                                if (written > MAX_UNZIP_BYTES) {
                                    throw IOException("解压总量超限（疑似 zip 炸弹）")
                                }
                            }
                        }
                    }
                }
            }
        }
        // 收口校验：必备文件缺一即判失败（残包不留 .ok，重试从零开始）
        MODEL_REQUIRED_FILES.forEach { rel ->
            if (!File(target, rel).isFile) throw IOException("模型包缺少必备文件：$rel")
        }
    }

    /** 唯一出口：投递给订阅方后退出；引擎资源幂等回收（delivered 闸门防二投递） */
    private fun finishDeliver(text: String?) {
        if (delivered) return
        delivered = true
        releaseEngine()
        runCatching { onResult?.invoke(text) }
            .onFailure { Log.w(TAG, "识别结果投递失败：${it.message}") }
        finish()
    }

    /** 引擎回收幂等：stop（收束采集线程）→ shutdown（释放 AudioRecord）→ close（释放 JNI） */
    private fun releaseEngine() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    // ==================== 离线面板 UI（透明底 + 居中卡片） ====================

    @Composable
    private fun OfflinePanel() {
        // 透明主题上叠一层暗色 scrim（语义令牌，values(-night) 同值）：让卡片浮起来，
        // 背后键盘面板仍隐约可见
        Box(
            Modifier.fillMaxSize().background(Color(ContextCompat.getColor(this, R.color.voice_scrim))),
            contentAlignment = Alignment.Center,
        ) {
            ApComponents.Card(Modifier.fillMaxWidth().padding(24.dp)) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val s = offlineState) {
                        Offline.NeedDownload -> {
                            BodyText(getString(R.string.ui_voice_offline_intro), 15.sp)
                            ApComponents.ActionButton(
                                text = getString(R.string.ui_voice_offline_go),
                                onClick = { downloadAndPrepare() },
                            )
                            // issue#168 2026-11-12 #6：手机网络到源站走不通时的兜底 ——
                            // 电脑/网盘下载的官方 zip 从文件选择器直接导入
                            ApComponents.NarrowButton(
                                text = getString(R.string.ui_voice_offline_import),
                                onClick = {
                                    importZipLauncher.launch(arrayOf("*/*"))
                                },
                            )
                        }
                        is Offline.Downloading -> {
                            BodyText(
                                getString(R.string.ui_voice_offline_progress, s.percent),
                                15.sp,
                            )
                        }
                        Offline.Importing -> {
                            BodyText(getString(R.string.ui_voice_offline_importing), 15.sp)
                        }
                        is Offline.Failed -> {
                            BodyText(getString(R.string.ui_voice_offline_retry), 15.sp)
                            // 成宇 2026-11-12 22:4x「下载完了说下载失败，是否链路有问题」：
                            // 失败原因当场可见 —— 下载链路 / 解压校验 / 引擎加载哪一环断了一目了然
                            if (s.reason.isNotBlank()) {
                                BodyText(getString(R.string.ui_voice_fail_reason, s.reason), 13.sp)
                            }
                            ApComponents.ActionButton(
                                text = getString(R.string.ui_voice_offline_retry),
                                onClick = { downloadAndPrepare() },
                            )
                            // 失败态同样给导入口：重试再不通，换个来源立刻能用
                            ApComponents.NarrowButton(
                                text = getString(R.string.ui_voice_offline_import),
                                onClick = {
                                    importZipLauncher.launch(arrayOf("*/*"))
                                },
                            )
                        }
                        Offline.NoMicPerm -> {
                            BodyText(getString(R.string.ui_voice_offline_no_perm), 15.sp)
                            ApComponents.ActionButton(
                                text = getString(R.string.ui_voice_offline_grant),
                                onClick = {
                                    micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        }
                        Offline.Preparing -> {
                            BodyText(getString(R.string.ui_voice_offline_preparing), 15.sp)
                        }
                        is Offline.Listening -> {
                            BodyText(getString(R.string.ui_voice_offline_listening), 15.sp)
                            BodyText(
                                s.partial.ifBlank { getString(R.string.ui_voice_offline_listening) },
                                18.sp,
                            )
                            ApComponents.ActionButton(
                                text = getString(R.string.ui_voice_offline_stop),
                                onClick = {
                                    // stop 让采集线程收束，onResult 会带终态 JSON 回来；
                                    // 引擎与投递统一在那边处理，这里不动句柄
                                    runCatching { speech?.stop() }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** 页内轻量 Text（miuix Text 默认吃主题色，免手配色值；独立成函数收拢行高） */
    @Composable
    private fun BodyText(text: String, size: TextUnit) {
        top.yukonga.miuix.kmp.basic.Text(text = text, fontSize = size)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
