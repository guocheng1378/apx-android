package com.allperiph.wireless

/**
 * 控制目标选择器：触控板 / 键盘 / 多媒体 / 副屏触摸四处出口在「选了受控设备」时短路到
 * [controlClient]（9511 客户端），否则维持原行为（蓝牙 HID / USB TLC / 本机）。
 *
 * 受控设备既可以是 TV，也可以是 PC —— 二者都运行 9511 服务端并广播 APX1TV 信标，
 * 手机端 [TvControllerClient] 连入即控。由触控板右上角的设备 chip 设置：
 * 选某设备 → 连入并赋值 [controlClient]；选「本机 / PC」→ [clear]。
 */
object ControlTarget {

    /** 当前选中的 9511 控制客户端（连入 TV / PC）；null 表示走默认（本机） */
    @Volatile
    var controlClient: TvControllerClient? = null

    /** 选中设备的 IP（文件传输通道用，与 controlClient 同步赋值） */
    @Volatile
    var host: String = ""

    /** 设备 chip 展示名（设备名或 IP；默认「本机 / PC」） */
    @Volatile
    var label: String = "本机 / PC"

    /**
     * 受控设备类型："tv" 或 "pc"。
     * 决定快捷键/键盘是否进入 TV 专属布局（遥控/媒体那套）：TV 进，PC 不进（保持鼠标 + 完整键鼠）。
     * 由信标前缀区分：APX1TV→tv（电视）、APX1PC→pc、APX1PH→phone（手机被控）；
     * 只有 tv 会切 TV 专属快捷键布局。PC 走自动发现，手动输入 IP 只用于 TV（默认 tv）。
     */
    @Volatile
    var type: String = "tv"

    /** 是否已选定且连上受控设备 */
    fun isControlling(): Boolean = controlClient?.ready == true

    /** 切回默认：断开受控设备并复位 */
    fun clear() {
        runCatching { controlClient?.disconnect() }
        controlClient = null
        host = ""
        label = "本机 / PC"
        type = "tv"
    }
}
