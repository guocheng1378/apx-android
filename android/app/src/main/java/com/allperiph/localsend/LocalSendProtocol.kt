package com.allperiph.localsend

import org.json.JSONObject
import java.util.UUID

/**
 * LocalSend 互传协议参数（v2.2，均以 localsend/localsend 官方源码为准）：
 *  - UDP 多播发现：组 224.0.0.167:53317（IPv6 扩展组 ff12::fd3a:e420 暂不参与）；
 *    announce JSON 突发 100/500/2000ms；收到 announce 后以 HTTP register 应答
 *  - HTTP API：GET info / POST register / POST prepare-upload / POST|PUT upload / POST cancel
 *  - 字段 camelCase；version 为协议版本（"2.2"），deviceType phone/tablet
 */
object LocalSendProtocol {
    const val MULTICAST_GROUP = "224.0.0.167"
    const val MULTICAST_PORT = 53317
    const val HTTP_PORT = 53317
    const val PROTOCOL_VERSION = "2.2"
    const val APP_VERSION = "219"

    const val PATH_INFO = "/api/localsend/v2/info"
    const val PATH_REGISTER = "/api/localsend/v2/register"
    const val PATH_PREPARE = "/api/localsend/v2/prepare-upload"
    const val PATH_UPLOAD = "/api/localsend/v2/upload"
    const val PATH_CANCEL = "/api/localsend/v2/cancel"

    /** http 模式下指纹为随机串（https 才是证书 sha256），进程内不变即可 */
    val fingerprint: String = UUID.randomUUID().toString()

    /** info / register 响应与设备登记共用的设备信息 */
    fun deviceInfo(alias: String, deviceType: String, download: Boolean): JSONObject = JSONObject()
        .put("alias", alias)
        .put("version", PROTOCOL_VERSION)
        .put("deviceModel", android.os.Build.MODEL)
        .put("deviceType", deviceType)
        .put("fingerprint", fingerprint)
        .put("download", download)

    /** 多播 announce 报文：设备信息 + 服务端口 + 协议 + announce 标志 */
    fun announce(alias: String, deviceType: String, download: Boolean, port: Int): JSONObject =
        deviceInfo(alias, deviceType, download)
            .put("port", port)
            .put("protocol", "http")
            .put("announce", true)

    /**
     * POST register 请求体：官方 dto_v2 的 RegisterDto = selfInfo + port + protocol。
     *
     * 旧实现这里直接发 `deviceInfo()`，没有 port —— 对端按协议取不到端口就把这次 register
     * 丢掉（PC 接收端以前是静默 return），「多播被路由器挡了还能靠单播 register 兜底」
     * 这条路等于从来没通。
     */
    fun register(alias: String, deviceType: String, download: Boolean, port: Int): JSONObject =
        deviceInfo(alias, deviceType, download)
            .put("port", port)
            .put("protocol", "http")
}
