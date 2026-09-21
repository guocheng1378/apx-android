package com.allperiph.gadget

import com.allperiph.core.LinkSpeed
import com.allperiph.core.Log
import com.allperiph.core.SysPath

/**
 * UDC 探测与速度门禁（架构 §6.1）。
 * 必须在挂载前完成：非 super-speed 时对外明确告警并进入降级模式（USB 2.0 回退）。
 */
data class UdcInfo(
    val name: String,
    val speed: LinkSpeed,
    val rawSpeed: String,
) {
    val path: String get() = "${SysPath.UDC_CLASS}/$name"
}

object UdcProbe {

    private const val TAG = "UdcProbe"

    /** 枚举 /sys/class/udc 下的控制器并读取 current_speed */
    fun list(shell: RootShell): List<UdcInfo> {
        val names = shell.listDir(SysPath.UDC_CLASS)
        if (names.isEmpty()) {
            Log.w(TAG, "no UDC under ${SysPath.UDC_CLASS}")
            return emptyList()
        }
        return names.mapNotNull { n ->
            val raw = shell.readAttr("${SysPath.UDC_CLASS}/$n/${SysPath.UDC_CURRENT_SPEED}")
            UdcInfo(n, parseSpeed(raw), raw)
        }.also {
            Log.i(TAG, "udc=${it.joinToString { u -> "${u.name}:${u.rawSpeed}" }}")
        }
    }

    /** 内核写出的字符串 → 协议枚举（§2.7 linkSpeed） */
    fun parseSpeed(raw: String): LinkSpeed {
        val s = raw.trim().lowercase()
        return when {
            s.contains("super-plus") || s.contains("superplus") || s.contains("10gbps") -> LinkSpeed.SUPER_PLUS
            s.contains("super") -> LinkSpeed.SUPER
            s.contains("high") -> LinkSpeed.HIGH
            s.contains("full") -> LinkSpeed.FULL
            // 协议里没有 low-speed 编码，归为 unknown 由上层告警
            else -> LinkSpeed.UNKNOWN
        }
    }

    /** 优先超高速；同速时取第一个（多数机型只有一个 UDC） */
    fun pickBest(candidates: List<UdcInfo>): UdcInfo? =
        candidates.maxByOrNull { it.speed.code }
}
