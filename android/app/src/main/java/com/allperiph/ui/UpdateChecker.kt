package com.allperiph.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.allperiph.BuildConfig
import com.allperiph.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases 更新检查（设置页 · 关于分段）。
 *
 * 数据源：仓库 `releases/latest` API → `tag_name`（形如 v205），与本机
 * [BuildConfig.RELEASE_TAG]（发版构建）/ [BuildConfig.VERSION_NAME]（回退）比**数值段**大小。
 * 两套号同源的前提在 android/app/build.gradle.kts：CI 打 tag v* 构建时 versionName == tag_name。
 *
 * 国内可用性：直连 api.github.com 失败（超时/被拦）自动回退 gh-proxy.com 镜像
 * （API 与 Release 资产都实测可通）；弹窗给「国内高速下载」直链 + 原 Release 页两个出口。
 *
 * 纪律：
 *  - 网络在后台线程执行，回调保证回主线程；
 *  - 同时只允许一次在途请求（匿名 API 60 次/小时限频，连点无意义）；
 *  - 静默检查 [autoCheck] 另有 6 小时窗口，防止每次进「关于」都打一次接口；
 *  - 失败只回 [Result.Failed]、不自动重试（想再试由用户手动再点）；
 *  - 镜像回退仅一次、仅在直连失败时触发，镜像也失败才回 Failed。
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/guocheng1378/apx-android/releases/latest"
    private const val UA = "AllPeriph-UpdateCheck"
    private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** 国内镜像前缀：直连 GitHub 不通的网络用它做 API 回退与安装包直链。 */
    private const val MIRROR = "https://gh-proxy.com/"
    private const val API_MIRROR = MIRROR + API
    private const val RELEASE_DL = MIRROR + "https://github.com/guocheng1378/apx-android/releases/download"

    sealed class Result {
        /** 本地不比远端旧 */
        data class UpToDate(val tag: String) : Result()
        /** 远端更新（[url] 是 Release 页，下载在这里，不在 App 内装包） */
        data class NewVersion(val tag: String, val url: String) : Result()
        /** 网络/接口/解析失败，[why] 只进日志，不直接给用户看 */
        data class Failed(val why: String) : Result()
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val inflight = AtomicBoolean(false)
    @Volatile private var lastAutoAt = 0L

    /** 当前构建的版本标识：发版构建是 release tag（v205），否则 versionName（1.8）。 */
    fun currentTag(): String = BuildConfig.RELEASE_TAG.ifEmpty { BuildConfig.VERSION_NAME }

    /** 手动检查：无视限频窗口，立刻拉一次。 */
    fun check(context: Context, onResult: (Result) -> Unit) {
        if (!inflight.compareAndSet(false, true)) return
        exec.execute {
            val r = fetchLatest()
            inflight.set(false)
            main.post { onResult(r) }
        }
    }

    /** 静默检查（进入「关于」分段触发）：6 小时内不重复打接口；调用方随后照常收回调。 */
    fun autoCheck(context: Context, onResult: (Result) -> Unit) {
        if (System.currentTimeMillis() - lastAutoAt < AUTO_INTERVAL_MS) return
        lastAutoAt = System.currentTimeMillis()
        check(context, onResult)
    }

    /** 直连优先；直连失败（超时/被拦/DNS）回退 gh-proxy.com 镜像，两边都失败才回 Failed。 */
    private fun fetchLatest(): Result {
        val direct = fetchTag(API)
        if (direct !is Result.Failed) return direct
        com.allperiph.shared.util.Log.w("UpdateChecker", "direct failed (${direct.why}), retry via mirror")
        return fetchTag(API_MIRROR)
    }

    private fun fetchTag(api: String): Result = try {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", UA)
        try {
            when (conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    val url = json.optString("html_url")
                    when {
                        tag.isEmpty() -> Result.Failed("empty tag_name")
                        compare(tag, currentTag()) > 0 -> Result.NewVersion(tag, url)
                        else -> Result.UpToDate(currentTag())
                    }
                }
                404 -> Result.Failed("no published release")
                else -> Result.Failed("http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        // 含无网、DNS 失败、超时、JSON 解析异常 —— 一律降级为 Failed，不抛到主线程
        com.allperiph.shared.util.Log.w("UpdateChecker", "check failed: ${e}")
        Result.Failed(e.javaClass.simpleName)
    }

    /** 数值段比较：v205 vs 1.8 → 逐段比大小，缺段按 0 补。a 比 b 新则返回正数。 */
    private fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    private fun parts(tag: String): List<Int> = tag
        .removePrefix("v")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }

    /**
     * 发现新版本的弹窗，两个下载出口：
     *  - 「国内高速下载」：gh-proxy 镜像直链 APK（github.com 打不开的网络用它）；
     *  - 「查看更新」：原 Release 页面（镜像直链不可用时的兜底，也方便核对资产）。
     * 不做应用内更新（免签名/权限/断点续付一套新问题）。
     */
    fun showUpdateDialog(context: Context, tag: String, url: String) {
        val apkMirror = "$RELEASE_DL/$tag/AllPeriph-$tag-app.apk"
        AlertDialog.Builder(context, com.allperiph.ui.ApPalette.dialogStyle())
            .setTitle(context.getString(R.string.about_update_new, tag))
            .setMessage(context.getString(R.string.about_update_hint, currentTag(), tag))
            .setPositiveButton(context.getString(R.string.about_update_dl_cn)) { _, _ ->
                // 镜像直链优先，打不开再退回原 Release 页
                if (!openUrl(context, apkMirror)) openUrl(context, url)
            }
            .setNeutralButton(context.getString(R.string.about_update_open)) { _, _ ->
                openUrl(context, url)
            }
            .setNegativeButton(context.getString(R.string.about_later), null)
            .show()
    }

    private fun openUrl(context: Context, url: String): Boolean =
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .isSuccess
}
