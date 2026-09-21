package com.allperiph.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.allperiph.core.LinkSpeed
import com.allperiph.core.Log
import com.allperiph.core.SysPath
import com.allperiph.gadget.RootShell
import com.allperiph.gadget.UdcProbe
import java.io.File

/**
 * 环境自检（架构 §6.1 速度门禁 / §6.2 UDC 抢占前置条件）。
 *
 * **必须在非主线程调用**：`RootShell.open()` 会 fork `su`，阻塞可达数百毫秒。
 */
object EnvChecks {

    data class Env(
        /** 是否取得 root（ConfigFS Gadget 的硬前提） */
        val rooted: Boolean,
        /** UDC 当前速度；非 super-speed 即降级 */
        val linkSpeed: LinkSpeed,
        /** UDC 名称（如 `a600000.dwc3`），未探测到为 null */
        val udc: String?,
        /** 原始 current_speed 字符串 */
        val rawSpeed: String,
        /** 是否已被电池优化限制 */
        val batteryOptimized: Boolean,
        /** 是否有通知权限（前台服务通知可见性） */
        val notificationGranted: Boolean,
        /** 一行人类可读结论 */
        val summary: String,
    ) {
        val canRunGadget: Boolean get() = rooted
        val superSpeed: Boolean get() = linkSpeed.isSuperSpeed
    }

    private const val TAG = "EnvChecks"

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sbin/su",
    )

    /** 疑似有 su（不等价于已授权，仅用于提示） */
    fun suPresent(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * 完整探测。
     *
     * @param needRoot 是否需要真的打开 root shell（探测速度时非 root 也可读 sysfs，
     *                 但为判断「能否挂载 Gadget」必须确认 root 可用）
     */
    fun probe(context: Context, needRoot: Boolean = true): Env {
        var rooted = false
        var shell: RootShell? = null
        if (needRoot) {
            shell = try {
                RootShell.open()
            } catch (t: Throwable) {
                Log.w(TAG, "打开 root shell 异常：${t.message}")
                null
            }
            rooted = shell != null
        }

        val (speed, udcName, raw) = readSpeed(shell)

        val batteryOptimized = !isIgnoringBatteryOptimizations(context)
        val notifGranted = notificationGranted(context)

        val summary = buildString {
            append(if (rooted) "Root 已授权" else if (suPresent()) "发现 su 但未授权" else "未检测到 Root")
            append(" · USB：")
            append(if (raw.isBlank()) "未知" else raw)
            if (!speed.isSuperSpeed) append("（降级运行）")
            if (batteryOptimized) append(" · 未加电池白名单")
            if (!notifGranted) append(" · 通知被关闭")
        }

        runCatching { shell?.close() }
        return Env(
            rooted = rooted,
            linkSpeed = speed,
            udc = udcName,
            rawSpeed = raw,
            batteryOptimized = batteryOptimized,
            notificationGranted = notifGranted,
            summary = summary,
        )
    }

    /**
     * 读取 `/sys/class/udc/` 下每个 UDC 目录里的 `current_speed`。
     * （注意：注释里不要写「星号紧跟斜杠」，那会提前闭合块注释。）
     * 先尝试普通权限直读（多数 sysfs 节点全局可读），失败再用 root shell。
     */
    private fun readSpeed(shell: RootShell?): Triple<LinkSpeed, String?, String> {
        val dir = File(SysPath.UDC_CLASS)
        val names = dir.list()?.filter { it.isNotBlank() }?.sorted()
        if (!names.isNullOrEmpty()) {
            for (n in names) {
                val f = File(dir, n + File.separator + SysPath.UDC_CURRENT_SPEED)
                val raw = runCatching { f.readText().trim() }.getOrDefault("")
                if (raw.isNotEmpty()) {
                    return Triple(UdcProbe.parseSpeed(raw), n, raw)
                }
            }
        }
        val s = shell ?: return Triple(LinkSpeed.UNKNOWN, null, "")
        val list = UdcProbe.list(s)
        val best = UdcProbe.pickBest(list)
            ?: return Triple(LinkSpeed.UNKNOWN, null, "")
        return Triple(best.speed, best.name, best.rawSpeed)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    fun notificationGranted(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && !nm.areNotificationsEnabled()) return false
        // Android 13+ 还需 POST_NOTIFICATIONS 运行时权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }
}
