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

    /**
     * http 模式下指纹为随机串（https 才是证书 sha256）。
     *
     * 2026-11-14 补：改为**落盘固化**（见 [initFingerprint]）——此前每次进程启动都
     * 重新随机，HyperOS 杀后台是常态，重启一次对端设备表和本机已知对端落盘里就多
     * 一条「同一台设备的孤儿」，KNOWN_PEERS_MAX 限额下孤儿会把真对端挤出去。
     * 初值保留进程内随机作兜底，正常启动路径由 [initFingerprint] 覆盖为落盘值。
     */
    var fingerprint: String = UUID.randomUUID().toString()
        private set

    /** 服务启动时注入落盘指纹；幂等，重复注入以后到为准，空串忽略 */
    fun initFingerprint(persisted: String) {
        if (persisted.isNotBlank()) fingerprint = persisted
    }

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
     * POST register 请求体：官方 dto_v2 的 RegisterDto = {info: 设备信息, port, protocol}。
     *
     * 2026-11-14 根修：旧实现把 deviceInfo 字段**平铺**在顶层（无 info 包裹）——
     * 官方 LocalSend 客户端按 RegisterDto 取不到 info 直接静默丢弃，我们发给 PC/
     * 其他装了官方端的 register 从来没生效过；自端接收侧已同时容忍平铺与包裹
     * （LocalSendService.onRegister），这里把出站改成官方口径，双端通吃。
     */
    fun register(alias: String, deviceType: String, download: Boolean, port: Int): JSONObject =
        JSONObject()
            .put("info", deviceInfo(alias, deviceType, download))
            .put("port", port)
            .put("protocol", "http")
}
