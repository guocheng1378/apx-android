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
    /**
     * 自建分发站更新 API（v223.30.0 起）：检查更新先查这里（国内可达、带国内服务器
     * 直链），站点不可达再回落 GitHub Releases（对老网络环境零感知）。
     * 响应见盒上 server.py /api/update：
     * {platform,latest,current,update,url,mirror,size,sha256,notes,force,ts}。
     */
    private const val SITE_API = "https://guoapp.dpdns.org"
    /**
     * 旧分发站（instabox 隧道，v223.30.0 起一直用的那条）：2026-11-17 站点已迁到飞牛 NAS
     * + Cloudflare 隧道（见 [SITE_API]），这里只作回退 —— 部分网络到 Cloudflare 不稳时
     * 仍能查到更新，避免整条「检查更新」直接掉到 GitHub。
     */
    private const val SITE_API_LEGACY = "https://8090-agqxwmnnzcndiz2wynoexrkqjm.instabox.tech"
    private const val UA = "AllPeriph-UpdateCheck"
    private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** 国内镜像前缀：直连 GitHub 不通的网络用它做 API 回退与安装包直链。 */
    private const val MIRROR = "https://gh-proxy.com/"
    /** API 回退镜像链（按序试）：gh-proxy 单点挂掉曾把整条检查更新拖死，v223.29.32 改链式。 */
    private val MIRRORS = listOf(MIRROR, "https://ghfast.top/", "https://ghproxy.net/")
    private const val API_MIRROR = MIRROR + API
    private const val RELEASE_DL = MIRROR + "https://github.com/guocheng1378/apx-android/releases/download"

    sealed class Result {
        /** 本地不比远端旧 */
        data class UpToDate(val tag: String) : Result()
        /**
         * 远端更新（[url] 是下载直链或 Release 页，下载在这里，不在 App 内装包）。
         * v223.30.0 起支持自建分发站：[source]="site" 时 url 为站点国内直链、
         * mirror 为备用分享页；默认 "github" 保持旧行为。notes/size/force/mirror
         * 来自 /api/release，默认值保证 SettingsPage 等旧构造点零改动。
         */
        data class NewVersion(
            val tag: String,
            val url: String,
            val notes: String = "",
            val size: Long = 0L,
            val force: Boolean = false,
            val mirror: String = "",
            val source: String = "github",
        ) : Result()
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

    /**
     * 更新源优先级（v223.30.0）：自建分发站 → GitHub 直连 → 镜像链。
     * 站点挂了/超时只回 Failed，不影响后面的 GitHub 回落（单镜像挂掉
     * 不再拖死整条检查的老约束继续成立）。
     */
    private fun fetchLatest(): Result {
        val fromSite = fetchFromSite()
        if (fromSite !is Result.Failed) return fromSite
        com.allperiph.shared.util.Log.w("UpdateChecker", "site failed (${fromSite.why}), fallback GitHub")
        val direct = fetchTag(API)
        if (direct !is Result.Failed) return direct
        com.allperiph.shared.util.Log.w("UpdateChecker", "direct failed (${direct.why}), retry mirrors")
        var last = direct
        for (m in MIRRORS) {
            val r = fetchTag(m + API)
            if (r !is Result.Failed) return r
            com.allperiph.shared.util.Log.w("UpdateChecker", "mirror failed ($m): ${r.why}")
            last = r
        }
        return last
    }

    /**
     * 查自建分发站 /api/update：服务端已按版本比好 update/latest/url/mirror，
     * App 只做展示。update=false → UpToDate；update=true 但 latest/url 为空
     * 视为接口异常 → Failed（交给 GitHub 兜底，不给用户弹残缺数据）。
     */
    private fun fetchFromSite(): Result {
        val primary = fetchFromSiteOnce(SITE_API)
        if (primary is Result.Failed) {
            com.allperiph.shared.util.Log.w(
                "UpdateChecker",
                "site primary failed (${primary.why}), retry legacy",
            )
            return fetchFromSiteOnce(SITE_API_LEGACY)
        }
        return primary
    }

    private fun fetchFromSiteOnce(base: String): Result = try {
        val ver = java.net.URLEncoder.encode(currentTag(), "UTF-8")
        val conn = URL("$base/api/update?platform=android&ver=$ver").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        try {
            when (conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    if (!json.optBoolean("update", false)) {
                        Result.UpToDate(currentTag())
                    } else {
                        val latest = json.optString("latest")
                        val dl = json.optString("url")
                        when {
                            latest.isEmpty() -> Result.Failed("site update=true but empty latest")
                            dl.isEmpty() -> Result.Failed("site update=true but empty url")
                            else -> Result.NewVersion(
                                tag = latest,
                                url = dl,
                                notes = json.optString("notes"),
                                size = json.optLong("size", 0L),
                                force = json.optBoolean("force", false),
                                mirror = json.optString("mirror"),
                                source = "site",
                            )
                        }
                    }
                }
                else -> Result.Failed("site http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        com.allperiph.shared.util.Log.w("UpdateChecker", "site check failed: ${e}")
        Result.Failed("site ${e.javaClass.simpleName}")
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

    /** 旧入口保持不变（GitHub 源）：包成 NewVersion 走统一弹窗。 */
    fun showUpdateDialog(context: Context, tag: String, url: String) {
        showUpdateDialog(context, Result.NewVersion(tag = tag, url = url))
    }

    /**
     * 发现新版本的弹窗（v223.30.0 起按更新源分两条路）：
     *  - GitHub 源：positive「国内高速下载」= gh-proxy 镜像直链 APK；
     *    neutral「查看更新」= 原 Release 页（兜底，也方便核对资产）。
     *  - 分发站源：positive = 自建站点国内服务器直链 APK（不跑代理、不受 GH 限速）；
     *    neutral = mirror 备用分享页（光鸭），mirror 空则退 GitHub Release 页。
     *  - force=true：服务端标记的强制更新，弹窗不可取消。
     * 不做应用内更新（免签名/权限/断点续传一套新问题）。
     */
    fun showUpdateDialog(context: Context, r: Result.NewVersion) {
        val message = buildString {
            append(context.getString(R.string.about_update_hint, currentTag(), r.tag))
            if (r.size > 0L) {
                append("\n包大小：约 ${(r.size + 1048575L) / 1048576L} MB")
            }
            if (r.notes.isNotBlank()) {
                append("\n${r.notes.trim()}")
            }
        }
        ApDialog.builder(context)
            .setTitle(context.getString(R.string.about_update_new, r.tag))
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.about_update_dl_cn)) { _, _ ->
                if (r.source == "site") {
                    // 分发站源：站点直链优先，打不开再开备用分享页
                    if (!openUrl(context, r.url)) {
                        val fallback = r.mirror.ifEmpty {
                            "https://github.com/guocheng1378/apx-android/releases/latest"
                        }
                        openUrl(context, fallback)
                    }
                } else {
                    // GitHub 源：镜像直链优先，打不开再退回原 Release 页
                    val apkMirror = "$RELEASE_DL/${r.tag}/AllPeriph-${r.tag}-app.apk"
                    if (!openUrl(context, apkMirror)) openUrl(context, r.url)
                }
            }
            .setNeutralButton(context.getString(R.string.about_update_open)) { _, _ ->
                // 分发站源 = 备用分享页（mirror 空则退 Release 页）；GitHub 源 = Release 页
                val fallback = if (r.source == "site") r.mirror else ""
                val page = fallback.ifEmpty {
                    "https://github.com/guocheng1378/apx-android/releases/latest"
                }
                openUrl(context, page)
            }
            .setNegativeButton(context.getString(R.string.about_later), null)
            .apply { if (r.force) setCancelable(false) }
            .show()
    }

    /** 手动兕底出口（检查失败时给用户）：国内镜像前缀直开 Release 页。 */
    fun openReleaseMirror(context: Context): Boolean =
        openUrl(context, MIRROR + "https://github.com/guocheng1378/apx-android/releases/latest")

    /** 手动兕底出口：GitHub 原 Release 页。 */
    fun openReleasePage(context: Context): Boolean =
        openUrl(context, "https://github.com/guocheng1378/apx-android/releases/latest")

    private fun openUrl(context: Context, url: String): Boolean =
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .isSuccess
}
