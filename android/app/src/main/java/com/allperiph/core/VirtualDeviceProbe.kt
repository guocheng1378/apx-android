package com.allperiph.core

/**
 * 无线模式虚拟设备探测（架构 §三 阶段二）：探测本机是否已安装第三方虚拟
 * 传感器 / 虚拟声卡 / 虚拟摄像头，供面板给出可用性与引导；不可用时如实降级。
 *
 * 仅做包名/服务存在性判断（不触发安装），逻辑可在无真机下验证。
 */
object VirtualDeviceProbe {
    data class Result(
        val virtualSensor: Boolean,
        val virtualAudio: Boolean,
        val virtualCamera: Boolean,
        val note: String,
    )

    // 常见第三方虚拟设备包名（示例，真机联调时按需扩充）
    private val SENSOR_PKGS = listOf("com.example.virtualsensor")
    private val AUDIO_PKGS = listOf("com.example.virtualaudio")
    private val CAMERA_PKGS = listOf("com.example.virtualcamera")

    fun probe(pm: android.content.pm.PackageManager): Result {
        val has = { list: List<String> ->
            list.any { runCatching { pm.getPackageInfo(it, 0) != null }.getOrDefault(false) }
        }
        val s = has(SENSOR_PKGS); val a = has(AUDIO_PKGS); val c = has(CAMERA_PKGS)
        val note = buildList {
            if (!s) add("未检测到虚拟传感器")
            if (!a) add("未检测到虚拟声卡")
            if (!c) add("未检测到虚拟摄像头")
            if (s && a && c) add("已具备全部虚拟设备，无线模式可完整运行")
        }.joinToString("；")
        Log.i(TAG, "虚拟设备探测 s=$s a=$a c=$c")
        return Result(s, a, c, note)
    }

    private const val TAG = "VirtualDeviceProbe"
}
