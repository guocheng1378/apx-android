#!/usr/bin/env python3
"""repo-edit：v223.29.32 发版四连修（16 编辑，锚均取自 search fragment / get resource 原文）。

 1. 防火墙规则名升版 v2（desktop_main / host_service）—— 旧规则绑旧 exe 路径时按名查重形同虚设；
 2. PC LocalSend receiver register 入表兕底 —— 多播被丢时手机单播 register 是唯一发现通路；
 3. 手机 UpdateChecker 镜像链 + 检查失败弹手动出口（上一版在线更新不了的体验根因）；
 4. PC 面板在线更新入口（update.check / update.open action + 概览页 Row）。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

D_MAIN = "pc/host/src/desktop_main.cpp"
D_SVC = "pc/host/src/app/host_service.cpp"
RCEIVER = "pc/host/src/localsend/local_send_receiver.cpp"
U_CHECK = "android/app/src/main/java/com/allperiph/ui/UpdateChecker.kt"
SET_PAGE = "android/app/src/main/java/com/allperiph/ui/compose/SettingsPage.kt"
A_ROUTER = "pc/host/src/api/action_router.cpp"
OVERVIEW = "pc/web/src/pages/Overview.tsx"
CHANGELOG = "docs/CHANGELOG.md"
USE_DOC = "docs/使用说明.md"

EDITS = [
    {
        "id": "F1 desktop 规则名 v2",
        "file": D_MAIN,
        "anchor": 'const char* ruleName = "AllPeriph apxdesktop ctrl";',
        "new": (
            "// v223.29.32 规则名升版：旧名规则按名查重、绑旧 exe 路径时形同虚设 —— 升级换目录\n"
            "// 后有规则却匹配不到当前进程，UDP 53317（互传多播）与 9501 入站全被拦，\n"
            "// 症状正是『手机能连电脑、电脑发现不了手机』。换名强制所有升级机器首启重建。\n"
            "    const char* ruleName = "
            '"AllPeriph apxdesktop ctrl v2";'
        ),
    },
    {
        "id": "F2 host 规则名 v2",
        "file": D_SVC,
        "anchor": 'const char* ruleName = "AllPeriph apxhost ctrl";',
        "new": (
            "// v223.29.32 规则名升版（同 desktop：路径老化按名查重失效 → 强制重建，口径同）\n"
            "    const char* ruleName = "
            '"AllPeriph apxhost ctrl v2";'
        ),
    },
    {
        "id": "F3 host 注释口径 53317",
        "file": D_SVC,
        "anchor": "（TCP 9511 + UDP 9501），免去手动配置。",
        "new": (
            "（program 级全端口：9511/9502/9512 TCP、9501 与互传 53317 的 UDP 入站），免去手动配置。"
        ),
    },
    {
        "id": "F4 register 分支读 body",
        "file": RCEIVER,
        "anchor": (
            '        } else if (method == "post" && path == kPathRegister) {\n'
            "            rd.discard(contentLength);"
        ),
        "new": (
            '        } else if (method == "post" && path == kPathRegister) {\n'
            "            // v223.29.32 register 入表兕底（官方协议语义）：手机收到本机 announce 后会\n"
            "            // 单播 register 应答 —— 多播（Wi-Fi→有线）被路由器丢时，这是让『附近设备』\n"
            "            // 看到手机的唯一通路。此前只回 200 不记设备，列表对手机永远空着。\n"
            "            std::string regBody;\n"
            "            if (contentLength <= (64u << 10)) {\n"
            "                if (!rd.readInto(regBody, contentLength)) regBody.clear();\n"
            "            } else {\n"
            "                rd.discard(contentLength);\n"
            "            }\n"
            "            onRegisterFromPeer(c, regBody);"
        ),
    },
    {
        "id": "F5 onRegisterFromPeer 新方法",
        "file": RCEIVER,
        "anchor": "    void udpLoop() {",
        "new": (
            "    // 单播 register 入表：与 onAnnouncePacket 同一入表路径（60s 窗口、onDevice 回调）。\n"
            "    // body 是对端 selfInfo 顶层 JSON，或官方 {info:{...}} 包装，两种都吃。\n"
            "    void onRegisterFromPeer(Sock c, const std::string& body) {\n"
            "        if (body.empty()) return;\n"
            "        std::string err;\n"
            "        const apxpc::net::Json j = apxpc::net::Json::parse(body, &err);\n"
            "        if (!j.isObject()) return;\n"
            "        const apxpc::net::Json* info = &j;\n"
            "        if (const apxpc::net::Json* nested = j.find(\"info\"); nested && nested->isObject()) info = nested;\n"
            "        const std::string proto = jsonStr(*info, \"protocol\", \"http\");\n"
            "        if (proto != \"http\") return;\n"
            "        const apxpc::net::Json* p = info->find(\"port\");\n"
            "        const int port = (p && p->isNumber()) ? p->asInt(0) : 0;\n"
            "        if (port <= 0 || port > 65535) return;\n"
            "        char ipStr[INET_ADDRSTRLEN] = {0};\n"
            "        sockaddr_in peer{};\n"
            "#if defined(_WIN32)\n"
            "        int peerLen = sizeof(peer);\n"
            "#else\n"
            "        socklen_t peerLen = sizeof(peer);\n"
            "#endif\n"
            "        if (::getpeername(c, reinterpret_cast<sockaddr*>(&peer), &peerLen) != 0) return;\n"
            "        if (!::inet_ntop(AF_INET, &peer.sin_addr, ipStr, sizeof ipStr)) return;\n"
            "        const std::string peerIp(ipStr);\n"
            "        const std::string fp = jsonStr(*info, \"fingerprint\", peerIp);\n"
            "        const std::string key = fp.empty() ? peerIp : fp;\n"
            "        if (key == fingerprint) return;\n"
            "        Device d;\n"
            "        d.alias = jsonStr(*info, \"alias\", peerIp);\n"
            "        d.deviceType = jsonStr(*info, \"deviceType\", \"phone\");\n"
            "        d.fingerprint = key;\n"
            "        d.ip = peerIp;\n"
            "        d.port = static_cast<uint16_t>(port);\n"
            "        d.lastSeenMs = nowMs();\n"
            "        bool isNew = false;\n"
            "        {\n"
            "            std::lock_guard lk(mu);\n"
            "            isNew = devices.find(key) == devices.end();\n"
            "            devices[key] = d;\n"
            "        }\n"
            "        if (isNew) {\n"
            "            APX_LOGI(\"LocalSend register 入表：{}（{}:{}）\", d.alias.c_str(), d.ip.c_str(),\n"
            "                     static_cast<int>(d.port));\n"
            "            if (onDevice) onDevice(d);\n"
            "        }\n"
            "    }\n"
            "\n"
            "    void udpLoop() {"
        ),
    },
    {
        "id": "F6 MIRRORS 镜像链",
        "file": U_CHECK,
        "anchor": (
            "    /** 国内镜像前缀：直连 GitHub 不通的网络用它做 API 回退与安装包直链。 */\n"
            '    private const val MIRROR = "https://gh-proxy.com/"'
        ),
        "new": (
            "    /** 国内镜像前缀：直连 GitHub 不通的网络用它做 API 回退与安装包直链。 */\n"
            '    private const val MIRROR = "https://gh-proxy.com/"\n'
            "    /** API 回退镜像链（按序试）：gh-proxy 单点挂掉曾把整条检查更新拖死，v223.29.32 改链式。 */\n"
            "    private val MIRRORS = listOf(MIRROR, "
            '"https://ghfast.top/", "https://ghproxy.net/")'
        ),
    },
    {
        "id": "F7 fetchLatest 链式回退",
        "file": U_CHECK,
        "anchor": (
            "    /** 直连优先；直连失败（超时/被拦/DNS）回退 gh-proxy.com 镜像，两边都失败才回 Failed。 */\n"
            "    private fun fetchLatest(): Result {\n"
            "        val direct = fetchTag(API)\n"
            "        if (direct !is Result.Failed) return direct\n"
            '        com.allperiph.shared.util.Log.w("UpdateChecker", "direct failed (${direct.why}), retry via mirror")\n'
            "        return fetchTag(API_MIRROR)\n"
            "    }"
        ),
        "new": (
            "    /** 直连优先；失败按序回退镜像链，全失败才回 Failed（单镜像挂掉不再拖死整条检查）。 */\n"
            "    private fun fetchLatest(): Result {\n"
            "        val direct = fetchTag(API)\n"
            "        if (direct !is Result.Failed) return direct\n"
            '        com.allperiph.shared.util.Log.w("UpdateChecker", "direct failed (${direct.why}), retry mirrors")\n'
            "        var last = direct\n"
            "        for (m in MIRRORS) {\n"
            "            val r = fetchTag(m + API)\n"
            "            if (r !is Result.Failed) return r\n"
            '            com.allperiph.shared.util.Log.w("UpdateChecker", "mirror failed ($m): ${r.why}")\n'
            "            last = r\n"
            "        }\n"
            "        return last\n"
            "    }"
        ),
    },
    {
        "id": "F8 openRelease 两个出口",
        "file": U_CHECK,
        "anchor": (
            "    private fun openUrl(context: Context, url: String): Boolean =\n"
            "        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }\n"
            "            .isSuccess\n"
            "}"
        ),
        "new": (
            "    /** 手动兕底出口（检查失败时给用户）：国内镜像前缀直开 Release 页。 */\n"
            "    fun openReleaseMirror(context: Context): Boolean =\n"
            '        openUrl(context, MIRROR + "https://github.com/guocheng1378/apx-android/releases/latest")\n'
            "\n"
            "    /** 手动兕底出口：GitHub 原 Release 页。 */\n"
            "    fun openReleasePage(context: Context): Boolean =\n"
            '        openUrl(context, "https://github.com/guocheng1378/apx-android/releases/latest")\n'
            "\n"
            "    private fun openUrl(context: Context, url: String): Boolean =\n"
            "        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }\n"
            "            .isSuccess\n"
            "}"
        ),
    },
    {
        "id": "F9 SettingsPage 失败分支",
        "file": SET_PAGE,
        "anchor": (
            "                                val msg = when (r) {\n"
            '                                    is UpdateChecker.Result.UpToDate -> "已是最新：${r.tag}"\n'
            '                                    is UpdateChecker.Result.Failed -> "检查失败"\n'
            '                                    else -> ""\n'
            "                                }\n"
            "                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()"
        ),
        "new": (
            "                                if (r is UpdateChecker.Result.Failed) {\n"
            "                                    // v223.29.32：失败不再只吐一行『检查失败』就结束（上一版\n"
            "                                    // 『在线更新不了』的体验根因）—— 弹出两个手动出口。\n"
            "                                    com.allperiph.ui.ApDialog.builder(ctx)\n"
            "                                        .setTitle(\"检查更新失败\")\n"
            "                                        .setMessage(\"网络可能不通（GitHub 直连与镜像均失败）。可手动打开 Release 页下载安装包。\")\n"
            "                                        .setPositiveButton(\"国内镜像打开\") { _, _ -> UpdateChecker.openReleaseMirror(ctx) }\n"
            "                                        .setNeutralButton(\"GitHub 原页\") { _, _ -> UpdateChecker.openReleasePage(ctx) }\n"
            "                                        .setNegativeButton(\"稍后\", null)\n"
            "                                        .show()\n"
            "                                    return@check\n"
            "                                }\n"
            "                                val msg = when (r) {\n"
            '                                    is UpdateChecker.Result.UpToDate -> "已是最新：${r.tag}"\n'
            '                                    else -> ""\n'
            "                                }\n"
            "                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()"
        ),
    },
    {
        "id": "F10 router includes",
        "file": A_ROUTER,
        "anchor": '#include "apxpc/api/action_router.hpp"',
        "new": (
            '#include "apxpc/api/action_router.hpp"\n'
            '#include "apxpc/app/update_check.hpp"  // update.check：查 releases/latest（同手机端算法）\n'
            '#include "apxpc/version.hpp"          // APXPC_VERSION_STRING：update.check 回带当前版本'
        ),
    },
    {
        "id": "F11 router 系统头",
        "file": A_ROUTER,
        "anchor": '#include "apxpc/sensors/sensor_reader.hpp"',
        "new": (
            "#if defined(_WIN32)\n"
            "#include <windows.h>\n"
            "#include <shellapi.h>  // update.open：ShellExecuteA 打开 Release 页（放 discovery 之后，避免 winsock 顺序问题）\n"
            "#endif\n"
            "#include <cstdlib>     // update.open（mac：system open）\n"
            '#include "apxpc/sensors/sensor_reader.hpp"'
        ),
    },
    {
        "id": "F12 update.check/open actions",
        "file": A_ROUTER,
        "anchor": "    // ---- 诊断 ----",
        "new": (
            "    // ---- 在线更新（v223.29.32）：概览页入口；此前只有 apxhost check-update CLI 与启动静默检查 ----\n"
            "    if (name == \"update.check\") {\n"
            "        const auto r = apxpc::app::checkForUpdate();\n"
            '        out["hasNewer"] = r.hasNewer;\n'
            '        out["latestTag"] = r.latestTag;\n'
            '        out["error"] = r.error;\n'
            '        out["netOk"] = r.ok;\n'
            '        out["current"] = APXPC_VERSION_STRING;\n'
            '        out["url"] = "https://github.com/guocheng1378/apx-android/releases/latest";\n'
            '        if (!r.ok) APX_LOGW("update.check 失败：{}", r.error.c_str());\n'
            "        return;\n"
            "    }\n"
            "    if (name == \"update.open\") {\n"
            "        const auto* u = p.find(\"url\");\n"
            "        const std::string url = u ? u->asString() : std::string();\n"
            "        // 白名单：只允许本项目 Release 页（后端代开外链，WebView2 对 window.open 不一定理）\n"
            '        if (url.rfind("https://github.com/guocheng1378/apx-android/releases", 0) != 0) {\n'
            '            out = fail("只允许打开本项目的 Release 页");\n'
            "            return;\n"
            "        }\n"
            "#if defined(_WIN32)\n"
            "        const bool did = static_cast<INT_PTR>(::ShellExecuteA(nullptr, \"open\", url.c_str(),\n"
            "                                                              nullptr, nullptr, SW_SHOWNORMAL)) > 32;\n"
            "#elif defined(__APPLE__)\n"
            "        const bool did = std::system((\"open '\" + url + \"'\").c_str()) == 0;\n"
            "#else\n"
            "        const bool did = false;\n"
            "#endif\n"
            '        out["ok"] = did;\n'
            '        if (!did) out["url"] = url;\n'
            "        return;\n"
            "    }\n"
            "\n"
            "    // ---- 诊断 ----"
        ),
    },
    {
        "id": "F13 Overview 更新态",
        "file": OVERVIEW,
        "anchor": "  const [manualOpen, setManualOpen] = React.useState(false);",
        "new": (
            "  const [manualOpen, setManualOpen] = React.useState(false);\n"
            "  // —— 在线更新（v223.29.32）：update.check 查 releases/latest；打开走后端\n"
            "  // update.open（WebView2 对 window.open 不一定理，后端代开最稳）。\n"
            "  const [upd, setUpd] = React.useState<{\n"
            "    checking?: boolean;\n"
            "    msg?: string;\n"
            "    newer?: string;\n"
            "    url?: string;\n"
            "  }>({});\n"
            "  const checkUpdate = async () => {\n"
            "    setUpd({ checking: true });\n"
            "    try {\n"
            "      const r: any = await bridge.act('update.check');\n"
            "      const d = r?.data ?? r;\n"
            "      if (d?.netOk === false || (d?.error && !d?.hasNewer)) {\n"
            "        setUpd({ msg: `检查失败：${d?.error || '网络不通'}` });\n"
            "      } else if (d?.hasNewer) {\n"
            "        setUpd({ newer: d.latestTag, url: d.url });\n"
            "      } else {\n"
            "        setUpd({ msg: `已是最新（${d?.current || '?'}）` });\n"
            "      }\n"
            "    } catch {\n"
            "      setUpd({ msg: '检查失败：面板未连接宿主' });\n"
            "    }\n"
            "  };\n"
            "  const openRelease = () => {\n"
            "    const url = upd.url || 'https://github.com/guocheng1378/apx-android/releases/latest';\n"
            "    bridge.act('update.open', { url }).catch(() => window.open(url, '_blank'));\n"
            "  };"
        ),
    },
    {
        "id": "F14 Overview 检查更新 Row",
        "file": OVERVIEW,
        "anchor": "          {state.tray && (",
        "new": (
            "          {/* ————— 在线更新（v223.29.32）————— */}\n"
            "          <Row\n"
            '            title="检查更新"\n'
            "            sub={\n"
            "              upd.checking\n"
            "                ? '正在查询 GitHub releases…'\n"
            "                : upd.msg ||\n"
            "                  (upd.newer ? `发现新版本 ${upd.newer}，点击打开下载页` : '点击检查是否有新版本')\n"
            "            }\n"
            "            onClick={() => {\n"
            "              if (upd.checking) return;\n"
            "              if (upd.newer) openRelease();\n"
            "              else checkUpdate();\n"
            "            }}\n"
            "          />\n"
            "          {state.tray && ("
        ),
    },
    {
        "id": "F15 CHANGELOG v223.29.32",
        "file": CHANGELOG,
        "anchor": "## v223.29.31（2026-11-08）",
        "new": (
            "## v223.29.32（2026-11-08）\n"
            "\n"
            "发现链路与更新通道四连修（触发线：电脑端发现不了手机 + 上一版在线更新不了 + PC 缺在线更新入口）。\n"
            "\n"
            "### 互传发现 · register 入表兕底\n"
            "\n"
            "- PC 接收端 POST /register 此前只回 200 不记设备；手机收到 PC announce 后本就会单播 register 应答，这条单播路径现在会把手机记进『附近设备』（60s 窗口 + onDevice 回调）—— 多播被路由器丢（Wi-Fi→有线方向）时的唯一发现通路就此打通。\n"
            "\n"
            "### 防火墙规则升版（53317）\n"
            "\n"
            "- 规则名升版 v2（AllPeriph apxdesktop ctrl v2 / AllPeriph apxhost ctrl v2）：旧名规则按名查重、绑旧 exe 路径即形同虚设 —— 升级换目录后 53317（互传 HTTP+多播）与 9501 入站被拦，症状正是『手机能连电脑、电脑发现不了手机』。换名强制所有升级机器首启重建。\n"
            "\n"
            "### 手机端在线更新修缮\n"
            "\n"
            "- 检查失败不再只吐一行『检查失败』：弹出『国内镜像打开 / GitHub 原页』两个手动出口（上一版『在线更新不了』的体验根因）。\n"
            "- API 回退由单镜像改镜像链（gh-proxy → ghfast.top → ghproxy.net）：单点挂掉不再拖死整条检查。\n"
            "\n"
            "### PC 面板在线更新（新增）\n"
            "\n"
            "- 概览页『本机』分组新增检查更新入口：update.check 查 GitHub releases/latest 与本机比对（与手机端同算法），发现新版一键 update.open 打开 Release 页（后端 ShellExecute 代开，WebView2 不理 window.open 的兕底）。\n"
            "\n"
            "---\n"
            "\n"
            "## v223.29.31（2026-11-08）"
        ),
    },
    {
        "id": "F16 使用说明防火墙口径",
        "file": USE_DOC,
        "anchor": "**放行 9501/9502/9511/9512**",
        "new": "**放行 9501/9502/9511/9512 及互传 53317（program 级全端口规则；v223.29.32 起规则名升版 v2 强制重建）**",
    },
]


def main() -> int:
    errors = []
    pending = {}
    for e in EDITS:
        path = e["file"]
        base = pending.get(path)
        if base is None:
            try:
                with open(path, encoding="utf-8") as f:
                    base = f.read()
            except OSError as ex:
                errors.append("open failed " + path + ": " + str(ex))
                continue
        if e["new"] in base:
            print("already applied, skip: " + e["id"])
            continue
        n = base.count(e["anchor"])
        if n != 1:
            errors.append(
                e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                + "\n---anchor---\n" + e["anchor"][:200]
            )
            continue
        pending[path] = base.replace(e["anchor"], e["new"], 1)

    if errors:
        body = "\n\n".join(errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as ex:
            print("write errlog failed: " + str(ex))
        print("ERRORS:\n" + body)
        return 1

    try:
        os.remove(ERRLOG)
    except OSError:
        pass

    if not pending:
        print("no changes")
        return 0

    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            changed.append(path)
        except OSError as ex:
            print("write failed " + path + ": " + str(ex))
            return 1

    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
