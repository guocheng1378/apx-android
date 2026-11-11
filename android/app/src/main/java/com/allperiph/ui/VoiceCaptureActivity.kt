package com.allperiph.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
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
         * 下载失败有「重试」入口，不换源（保持来源单一可审计）。
         */
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

        /** 离线模型落盘目录名（内部存储；目录内有 .ok 标记 = 下载+解压完整） */
        private const val MODEL_DIR = "vosk-model-small-cn-0.22"

        /**
         * 解压总量上限（全审查条 14）：模型包解压后实际 ~40MB，留 6 倍余量 ——
         * 上游 zip 被换成 zip 炸弹时，解压到上限即抛错，不写满内部存储。
         */
        private const val MAX_UNZIP_BYTES = 256L * 1024 * 1024

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

        /** 下载/解压失败：给「重试」入口 */
        data object Failed : Offline()

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
                runOnUiThread { offlineState = Offline.Failed }
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
        if (offlineState is Offline.Downloading) return
        offlineState = Offline.Downloading(0)
        Thread {
            try {
                val zip = File(cacheDir, "$MODEL_DIR.zip")
                // 全审查条 3：先写 .tmp 再改名 —— 断网 / 杀进程留下的半个包不会被
                // 当成完整 zip 拿去解压；也只有改名成功（下载完整）才继续。
                val tmp = File(cacheDir, "$MODEL_DIR.zip.tmp")
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.connect()
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
                if (!tmp.renameTo(zip)) throw IOException("下载产物改名失败")
                unzipModel(zip, modelDir())
                File(modelDir(), ".ok").writeText(MODEL_DIR)
                zip.delete()
                runOnUiThread { startOfflineListening() }
            } catch (e: Exception) {
                Log.w(TAG, "离线语音包下载/解压失败：${e.message}")
                // 清掉半截下载产物，重试从零开始（全审查条 3 的另一半）
                runCatching { File(cacheDir, "$MODEL_DIR.zip.tmp").delete() }
                runCatching { File(cacheDir, "$MODEL_DIR.zip").delete() }
                runOnUiThread { offlineState = Offline.Failed }
            }
        }.start()
    }

    /** 解压到内部存储；zip-slip 防御：解压结果必须落在模型目录内 */
    private fun unzipModel(zip: File, target: File) {
        target.mkdirs()
        val targetPath = target.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(target, e.name)
                if (!out.canonicalPath.startsWith(targetPath + File.separator)) {
                    throw IOException("zip-slip: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { f ->
                        while (true) {
                            val n = zis.read(buf)
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
                zis.closeEntry()
            }
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
                        }
                        is Offline.Downloading -> {
                            BodyText(
                                getString(R.string.ui_voice_offline_progress, s.percent),
                                15.sp,
                            )
                        }
                        Offline.Failed -> {
                            BodyText(getString(R.string.ui_voice_offline_retry), 15.sp)
                            ApComponents.ActionButton(
                                text = getString(R.string.ui_voice_offline_retry),
                                onClick = { downloadAndPrepare() },
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
