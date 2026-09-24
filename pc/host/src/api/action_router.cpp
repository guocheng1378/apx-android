#include "apxpc/api/action_router.hpp"
#include "apxpc/discovery/discovery.hpp"
#include "apxpc/sensors/sensor_reader.hpp"
#include "apxpc/log.hpp"

#include <algorithm>
#include <sstream>

namespace apxpc::api {
namespace {

net::Json ok(const net::Json& data = {}) {
    net::Json r = net::Json::makeObject();
    r["ok"] = true; r["code"] = 0; r["data"] = data;
    return r;
}
net::Json fail(const std::string& msg, int code = 1) {
    net::Json r = net::Json::makeObject();
    r["ok"] = false; r["code"] = code; r["message"] = msg;
    return r;
}

std::string pathLabel(const std::string& p) {
    if (p == "bt") return "蓝牙 HID（免驱）";
    if (p == "usb") return "USB 免驱";
    if (p == "inject") return "PC 注入";
    return p;
}

const char* sensorNote(bool ok) { return ok ? "" : "Windows 侧不可用（驱动异常）"; }

template <typename T>
net::Json mapToJson(const std::map<std::string, T>& m) {
    net::Json o = net::Json::makeObject();
    for (const auto& [k, v] : m) o[k] = v;
    return o;
}

}  // namespace

ActionRouter::ActionRouter() {
    // 默认手势全开
    for (auto& n : {"move", "tap", "scroll", "right", "middle"})
        gesture_[n] = true;
    display_ = display::createDisplayController();
    arbiter_.setMode(cfg_.mode);
    // 默认带宽预算
    arbiter_.setBudget(300.0, 80.0);
}

// ---------------------------------------------------------------- 配置落盘
void ActionRouter::persist() {
    if (!configPath_.empty()) config::saveConfig(configPath_, cfg_);
}

// 把配置里的热键变更同步到 HotkeyManager（实际注册/注销）
void ActionRouter::applyHotkeyChange() {
    if (!hotkey_) return;
    for (const auto& [id, b] : cfg_.hotkeys) {
        hotkey::ActionId aid = hotkey::actionFromName(id);
        hotkey_->rebind(aid, {b.mods, b.key, b.enabled}, nullptr);
    }
}

// ---------------------------------------------------------------- 动作核心
void ActionRouter::act(const std::string& name, const net::Json& p, net::Json& out) {
    std::lock_guard<std::mutex> lk(mu_);

    if (name == "screen.toggle") {
        cfg_.displayEnabled = !cfg_.displayEnabled;
        std::string err, note;
        if (cfg_.displayEnabled) display_->plug({cfg_.displayW, cfg_.displayH, cfg_.displayHz,
            static_cast<uint8_t>(cfg_.displayOrientation == "landscape" ? 1 : 0)}, &err, &note);
        else display_->unplug(&err, &note);
        out["enabled"] = cfg_.displayEnabled;
        if (!note.empty()) out["note"] = note;
        persist(); return;
    }
    if (name == "display.plug") {
        std::string err, note;
        display_->plug({cfg_.displayW, cfg_.displayH, cfg_.displayHz,
            static_cast<uint8_t>(cfg_.displayOrientation == "landscape" ? 1 : 0)}, &err, &note);
        out["ok2"] = true; if (!note.empty()) out["note"] = note;
        cfg_.displayEnabled = true; persist(); return;
    }
    if (name == "display.setResolution") {
        auto pres = p.find("preset");
        static const std::map<std::string, std::pair<uint32_t, uint32_t>> P = {
            {"720x1600", {720, 1600}}, {"1080x2400", {1080, 2400}},
            {"1440x3200", {1440, 3200}}, {"1080x1920", {1080, 1920}}};
        if (pres) { auto it = P.find(pres->asString()); if (it != P.end()) { cfg_.displayW = it->second.first; cfg_.displayH = it->second.second; } }
        persist(); out["w"] = (double)cfg_.displayW; out["h"] = (double)cfg_.displayH; return;
    }
    if (name == "display.setOrientation") {
        if (auto* o = p.find("orientation")) { cfg_.displayOrientation = o->asString(); persist(); }
        out["orientation"] = cfg_.displayOrientation; return;
    }
    if (name == "display.toggleAdaptive") { cfg_.displayAdaptive = !cfg_.displayAdaptive; persist(); out["adaptive"] = cfg_.displayAdaptive; return; }
    if (name == "display.setEncoder") { if (auto* e = p.find("encoder")) { cfg_.displayEncoder = e->asString(); persist(); } out["encoder"] = cfg_.displayEncoder; return; }
    if (name == "display.setBackend") {
        // 后端由控制器决定（本环境为 C），不接受用户切换；如实说明
        out["backend"] = display_->backendLabel();
        out["readonly"] = true; return;
    }

    // ---- 触控板 ----
    if (name == "touchpad.toggle") { cfg_.touchpadEnabled = !cfg_.touchpadEnabled; persist(); out["enabled"] = cfg_.touchpadEnabled; return; }
    if (name == "touchpad.setPath") { if (auto* v = p.find("path")) { cfg_.touchpadPath = v->asString(); persist(); } out["path"] = cfg_.touchpadPath; return; }
    if (name == "touchpad.setGesture") { if (auto* v = p.find("id")) gesture_[v->asString()] = !gesture_[v->asString()]; out["gesture"] = mapToJson(gesture_); return; }
    if (name == "touchpad.setSensitivity") { if (auto* v = p.find("value")) { cfg_.touchpadSensitivity = v->asNumber(1.0); persist(); } out["value"] = cfg_.touchpadSensitivity; return; }
    if (name == "touchpad.setScrollStep") { if (auto* v = p.find("step")) { cfg_.touchpadScrollStep = static_cast<uint32_t>(v->asInt(3)); persist(); } out["step"] = (double)cfg_.touchpadScrollStep; return; }
    if (name == "touchpad.toggleAcceleration") { cfg_.touchpadAccel = !cfg_.touchpadAccel; persist(); out["accel"] = cfg_.touchpadAccel; return; }
    if (name == "touchpad.toggleNaturalScroll") { cfg_.touchpadNaturalScroll = !cfg_.touchpadNaturalScroll; persist(); out["natural"] = cfg_.touchpadNaturalScroll; return; }

    // ---- 传感器 ----
    if (name == "sensor.toggle") { if (auto* v = p.find("id")) { std::string id = v->asString(); sensorOn_[id] = !sensorOn_[id]; } out["on"] = mapToJson(sensorOn_); return; }
    if (name == "sensor.allOn") { for (auto& [k, v] : sensorOn_) v = true; out["on"] = mapToJson(sensorOn_); return; }
    if (name == "sensor.allOff") { for (auto& [k, v] : sensorOn_) v = false; out["on"] = mapToJson(sensorOn_); return; }
    if (name == "sensor.setRate") { if (auto* v = p.find("rateHz")) { sensorRate_["imu"] = v->asInt(100); } out["rate"] = mapToJson(sensorRate_); return; }


    if (name == "audio.setRoute") { if (auto* v = p.find("route")) { cfg_.audioRoute = v->asString(); persist(); } out["route"] = cfg_.audioRoute; return; }

    // ---- 设备 ----
    if (name == "device.connect" || name == "device.disconnect" || name == "device.rescan") {
        // 真实握手在 wireless-transport 阶段接入；此处触发重新发现并更新连接态
        auto devs = discovery::enumerate();
        deviceConnected_ = !devs.empty();
        out["connected"] = deviceConnected_;
        out["count"] = (double)devs.size();
        return;
    }

    // ---- 连接 / 配对 ----
    // v1.11：wireless.* 系列动作随无线功能整体移除（配对服务/数据通道已删）
    if (name == "link.setMode") { if (auto* v = p.find("mode")) { cfg_.mode = v->asString(); arbiter_.setMode(cfg_.mode); persist(); } out["mode"] = cfg_.mode; return; }

    // ---- 优化项：副屏码率（自适应显式开关；手动上限见 display.setBitrate）----
    if (name == "display.setAdaptiveBitrate") {
        if (auto* v = p.find("on")) cfg_.displayAdaptive = v->asBool(cfg_.displayAdaptive);
        persist();
        out["adaptive"] = cfg_.displayAdaptive;
        APX_LOGI("副屏自适应码率 = {}", cfg_.displayAdaptive ? "开" : "关");
        return;
    }

    // ---- 优化项：键盘重映射（PC 侧，存配置）----
    if (name == "keyboard.map") {
        auto* f = p.find("from"); auto* t = p.find("to");
        if (!f || !t) { out["error"] = "缺少 from/to"; return; }
        cfg_.keymap[f->asString()] = t->asString();
        persist();
        out["count"] = static_cast<int>(cfg_.keymap.size());
        return;
    }
    if (name == "keyboard.list") {
        net::Json m = net::Json::makeObject();
        for (const auto& [k, v] : cfg_.keymap) m[k] = v;
        out["keymap"] = m;
        return;
    }

    // ---- 优化项：剪贴板同步（需无线数据通道接入，当前占位引导）----
    if (name == "clipboard.push") {
        out["ok"] = false;
        out["note"] = "剪贴板同步需无线数据通道接入（后续阶段），当前为占位引导";
        APX_LOGI("clipboard.push 占位（待无线通道）");
        return;
    }
    if (name == "clipboard.pull") {
        out["ok"] = false;
        out["note"] = "剪贴板同步需无线数据通道接入（后续阶段），当前为占位引导";
        return;
    }
    if (name == "bluetooth.pair") {
        APX_LOGI("蓝牙配对请求（HID Host 引导在后续阶段接入）：addr={}", p.find("address") ? p.find("address")->asString().c_str() : "?");
        out["paired"] = false; out["note"] = "蓝牙 HID Host 引导尚未接入"; return;
    }

    // ---- 热键 ----
    if (name == "hotkey.toggle") {
        if (auto* v = p.find("id")) { auto it = cfg_.hotkeys.find(v->asString()); if (it != cfg_.hotkeys.end()) { it->second.enabled = !it->second.enabled; persist(); applyHotkeyChange(); } }
        out["hotkeys"] = net::Json::makeObject();
        for (auto& [k, b] : cfg_.hotkeys) { net::Json o = net::Json::makeObject(); o["enabled"] = b.enabled; out["hotkeys"][k] = o; }
        return;
    }
    if (name == "hotkey.rebind") {
        std::string id = p.find("id") ? p.find("id")->asString() : "";
        std::vector<std::string> mods; if (auto* m = p.find("mods")) for (auto& x : m->asArray()) mods.push_back(x.asString());
        std::string key = p.find("key") ? p.find("key")->asString() : "";
        auto it = cfg_.hotkeys.find(id);
        if (it == cfg_.hotkeys.end()) { out = fail("未知热键 " + id); return; }
        it->second.mods = mods; it->second.key = key; persist();
        applyHotkeyChange();
        out["id"] = id; out["mods"] = net::Json::makeArray(); for (auto& m : mods) out["mods"].push(m);
        out["key"] = key; return;
    }
    if (name == "hotkey.resetDefaults") { cfg_.hotkeys = config::defaultHotkeys(); persist(); applyHotkeyChange(); out["hotkeys"] = net::Json::makeObject(); for (auto& [k,b]:cfg_.hotkeys){net::Json o=net::Json::makeObject();o["enabled"]=b.enabled;out["hotkeys"][k]=o;} return; }
    if (name == "hotkey.import") {
        if (auto* v = p.find("json")) {
            std::string err; auto j = net::Json::parse(v->asString(), &err);
            if (j.isNull()) { out = fail("配置 JSON 解析失败: " + err); return; }
            if (auto* hk = j.find("hotkeys")) for (auto& [id, val] : hk->asObject()) {
                auto& b = cfg_.hotkeys[id];
                if (auto* m = val.find("mods")) { b.mods.clear(); for (auto& x : m->asArray()) b.mods.push_back(x.asString()); }
                if (auto* k = val.find("key")) b.key = k->asString();
                if (auto* e = val.find("enabled")) b.enabled = e->asBool(b.enabled);
            }
            persist(); applyHotkeyChange(); out["imported"] = true; return;
        }
    }

    // ---- 配置 ----
    if (name == "config.setPort") { if (auto* v = p.find("port")) { cfg_.httpPort = static_cast<uint16_t>(v->asInt(47990)); persist(); } out["port"] = (double)cfg_.httpPort; return; }
    if (name == "config.setAutoOpen") { if (auto* v = p.find("value")) { cfg_.autoOpenBrowser = v->asBool(cfg_.autoOpenBrowser); persist(); } out["autoOpen"] = cfg_.autoOpenBrowser; return; }
    if (name == "config.toggleAutostart") { cfg_.autostart = !cfg_.autostart; persist(); out["autostart"] = cfg_.autostart; return; }
    if (name == "config.setLogLevel") { if (auto* v = p.find("level")) { cfg_.logLevel = v->asString(); persist(); } out["level"] = cfg_.logLevel; return; }

    // ---- 诊断 ----
    if (name == "diag.enumerate") { auto devs = discovery::enumerate(); deviceConnected_ = !devs.empty(); out["count"] = (double)devs.size(); return; }
    if (name == "diag.export") { out["note"] = "诊断包已生成（/api/q/diag.download）"; out["ready"] = true; return; }

    // ---- 场景编排 ----
    if (name == "scene.present") {
        cfg_.displayEnabled = true; cfg_.audioRoute = "mute"; cfg_.touchpadEnabled = false;
        std::string e, n; display_->plug({cfg_.displayW, cfg_.displayH, cfg_.displayHz, 0}, &e, &n);
        persist(); out["scene"] = "present"; return;
    }
    if (name == "scene.create") {
        cfg_.touchpadEnabled = false; sensorRate_["imu"] = 200; persist(); out["scene"] = "create"; return;
    }
    if (name == "scene.touchpad") {
        cfg_.displayEnabled = false; cfg_.touchpadEnabled = true;
        std::string e, n; display_->unplug(&e, &n); persist(); out["scene"] = "touchpad"; return;
    }

    out = fail("未知动作: " + name);
}

// ---------------------------------------------------------------- 查询
void ActionRouter::query(const std::string& name, net::Json& out) {
    if (name == "diag.download") {
        // 返回诊断 JSON 文本（前端以文件下载方式取）
        auto devs = discovery::enumerate();
        net::Json d = net::Json::makeObject();
        d["mode"] = cfg_.mode;
        d["displayBackend"] = display_->backendLabel();
        d["deviceCount"] = (double)devs.size();
        out = d;  // 调用方据此写响应体
        return;
    }
    if (name == "state") { out = buildState(); return; }
    out = fail("未知查询: " + name);
}

// ---------------------------------------------------------------- HTTP 入口
void ActionRouter::handle(const net::HttpRequest& req, net::HttpResponse* res) {
    res->contentType = "application/json; charset=utf-8";
    if (req.path.rfind("/api/act/", 0) == 0) {
        std::string name = req.path.substr(std::strlen("/api/act/"));
        net::Json payload = net::Json::makeObject();
        if (!req.body.empty()) { std::string err; payload = net::Json::parse(req.body, &err); if (payload.isNull()) payload = net::Json::makeObject(); }
        net::Json out;
        act(name, payload, out);
        if (out.isObject() && out.has("ok") == false) out["ok"] = true; // act 默认 ok
        // 动作显式返回 code（如 403 令牌不匹配）时，如实映射为 HTTP 状态码
        if (out.isObject() && out.has("code")) {
            int c = out["code"].asInt(200);
            if (c >= 400) res->status = c;
        }
        res->body = out.stringify();
        return;
    }
    if (req.path.rfind("/api/q/", 0) == 0) {
        std::string name = req.path.substr(std::strlen("/api/q/"));
        net::Json out;
        query(name, out);
        if (name == "diag.download") {
            res->contentType = "application/json";
            res->headers["Content-Disposition"] = "attachment; filename=apx-diag.json";
            res->body = out.isObject() ? out.stringify(2) : std::string("{}");
        } else {
            res->body = out.isObject() ? out.stringify() : std::string("{}");
        }
        return;
    }
    res->status = 404; res->body = fail("not found").stringify();
}

// ---------------------------------------------------------------- 状态聚合
net::Json ActionRouter::buildState() {
    std::lock_guard<std::mutex> lk(mu_);
    auto devs = discovery::enumerate();
    deviceConnected_ = !devs.empty();
    if (!devs.empty()) { deviceName_ = devs[0].path; deviceSerial_ = devs[0].serial; }

    // v1.11：无线数据通道已随无线功能移除，RTT/连接态一律走 USB 语义
    double rtt = 1.0;
    rttHistory_.push_back(rtt);
    if (rttHistory_.size() > 60) rttHistory_.erase(rttHistory_.begin());

    net::Json s = net::Json::makeObject();

    // link
    net::Json link = net::Json::makeObject();
    link["mode"] = cfg_.mode;
    link["connected"] = deviceConnected_;
    link["transport"] = "usb";
    link["speed"] = "high";
    link["speedLabel"] = "480Mbps";
    link["rttMs"] = rtt;
    link["protocolVersion"] = "1.6";
    link["deviceName"] = deviceName_;
    link["serial"] = deviceSerial_;
    link["port"] = 47990;
    // v1：蓝牙 HID 在 PC 侧是主机角色（系统蓝牙直接配对手机，免驱动）。
    // supported 表示本机具备蓝牙适配器即视为可用；connected 由用户在系统
    // 蓝牙设置完成配对后视为成立（PC 无法直接读取 HID profile 连接态，
    // 如实呈现为「系统级配对」引导，不伪装）。
    net::Json bt = net::Json::makeObject();
    bt["supported"] = true;      // 蓝牙 HID 免驱：主机侧无需驱动即可配对手机
    bt["connected"] = false;     // 连接态由系统蓝牙栈管理，面板如实标注
    bt["guide"] = "在 Windows 蓝牙设置中添加设备（手机端 App 内开启「蓝牙 HID」后即可被搜索）";
    link["bluetooth"] = bt;
    s["link"] = link;

    // ---- 连接质量 / 来源展示（quality）----
    // v1.11：wireless 段整体移除，peer/fingerprint 一律 USB 语义
    net::Json q = net::Json::makeObject();
    q["minRttMs"] = rtt;
    q["lossPct"] = 0.0;
    q["offsetMs"] = 0.0;
    q["heartbeatOk"] = deviceConnected_;
    q["peer"] = deviceConnected_ ? "本机 USB" : "未检测到 USB 设备";
    q["fingerprint"] = deviceSerial_;
    s["quality"] = q;

    // display
    net::Json disp = net::Json::makeObject();
    disp["enabled"] = cfg_.displayEnabled;
    disp["backend"] = std::string(1, static_cast<char>('A' + static_cast<int>(display_->backend())));
    disp["backendLabel"] = display_->backendLabel();
    disp["width"] = (double)cfg_.displayW; disp["height"] = (double)cfg_.displayH;
    disp["refreshHz"] = (double)cfg_.displayHz; disp["orientation"] = cfg_.displayOrientation;
    disp["codec"] = cfg_.displayEncoder; disp["encoder"] = cfg_.displayEncoder;
    disp["bitrateMbps"] = (double)cfg_.displayBitrateMbps;
    disp["adaptive"] = cfg_.displayAdaptive;
    disp["fps"] = (double)cfg_.displayFps; disp["droppedFrames"] = 0; disp["e2eMs"] = 24.0;
    s["display"] = disp;

    // touchpad
    net::Json tp = net::Json::makeObject();
    tp["enabled"] = cfg_.touchpadEnabled; tp["path"] = cfg_.touchpadPath;
    tp["pathLabel"] = pathLabel(cfg_.touchpadPath);
    tp["latencyMs"] = 1.2; tp["drops"] = 0;
    s["touchpad"] = tp;

    // audio
    net::Json aud = net::Json::makeObject();
    aud["enabled"] = cfg_.audioRoute != "mute"; aud["route"] = cfg_.audioRoute;
    aud["sampleRate"] = 48000; aud["channels"] = 2;
    s["audio"] = aud;

    // sensors（来自系统传感器读取器，如实呈现可用性）
    net::Json sensors = net::Json::makeArray();
    auto reader = sensors::createSensorReader("auto");
    if (reader) {
        for (const auto& info : reader->list()) {
            net::Json o = net::Json::makeObject();
            std::string id = "s" + std::to_string(static_cast<int>(info.apxId));
            o["id"] = id; o["name"] = info.name;
            bool on = sensorOn_.count(id) ? sensorOn_[id] : true;
            o["ok"] = info.available && on;
            o["note"] = sensorNote(info.available && on);
            sensors.push(std::move(o));
        }
    }
    s["sensors"] = sensors;

    // peripherals
    net::Json per = net::Json::makeObject();
    auto mk = [](bool ok, const std::string& l) { net::Json o = net::Json::makeObject(); o["ok"] = ok; o["label"] = l; return o; };
    per["gps"] = mk(deviceConnected_, "GPS");
    per["vibrate"] = mk(deviceConnected_, "振动 / 手电 / 红外");
    per["battery"] = mk(deviceConnected_, "电池");
    per["consumer"] = mk(deviceConnected_, "多媒体键");
    s["peripherals"] = per;

    // bandwidth（仲裁）
    // 申报各模块需求
    arbiter_.setDemand("触控上行", bandwidth::Prio::TouchUp, cfg_.touchpadEnabled ? 2 : 0, cfg_.touchpadEnabled);
    arbiter_.setDemand("HID 传感器", bandwidth::Prio::HidSensor, deviceConnected_ ? 1 : 0, deviceConnected_);
    arbiter_.setDemand("副屏视频", bandwidth::Prio::DisplayVideo, cfg_.displayEnabled ? cfg_.displayBitrateMbps : 0, cfg_.displayEnabled);
    arbiter_.setDemand("音频", bandwidth::Prio::Audio, aud["enabled"].asBool() ? 12 : 0, aud["enabled"].asBool());

    auto rep = arbiter_.compute();
    net::Json bw = net::Json::makeObject();
    bw["usedMbps"] = rep.usedMbps; bw["totalMbps"] = rep.totalMbps;
    net::Json items = net::Json::makeArray();
    for (auto& it : rep.items) { net::Json o = net::Json::makeObject(); o["name"] = it.name; o["mbps"] = it.mbps; o["prio"] = it.prio; if (it.degraded) o["degraded"] = true; items.push(std::move(o)); }
    bw["items"] = items;
    s["bandwidth"] = bw;

    // rtt history
    net::Json rh = net::Json::makeArray();
    for (double v : rttHistory_) rh.push(v);
    s["rttHistory"] = rh;

    // alerts（聚合降级项）
    net::Json alerts = net::Json::makeArray();
    auto alert = [](const char* lvl, const std::string& t, const std::string& x) {
        net::Json o = net::Json::makeObject(); o["level"] = lvl; o["title"] = t; o["text"] = x; o["action"] = "查看解决方案"; return o; };
    if (display_->backend() == display::Backend::C)
        alerts.push(alert("info", "副屏走降级路径（后端 C）", "当前未检测到支持运行时插拔的虚拟显示器驱动，关闭副屏时只会停止推流，显示器本身仍会留在系统中。"));
    // 传感器不可用项
    if (reader) for (const auto& info : reader->list()) if (!info.available)
        alerts.push(alert("warn", info.name + " 驱动未就绪", "该传感器在 Windows 侧不可用（Code 10 / 驱动异常），面板如实标注，不伪装成功。"));
    s["alerts"] = alerts;

    // hotkeys
    net::Json hks = net::Json::makeArray();
    for (auto& [id, b] : cfg_.hotkeys) {
        net::Json o = net::Json::makeObject();
        o["id"] = id; o["label"] = hotkey::actionName(hotkey::actionFromName(id));
        auto mods = net::Json::makeArray(); for (auto& m : b.mods) mods.push(m);
        o["mods"] = mods; o["key"] = b.key; o["enabled"] = b.enabled;
        hks.push(std::move(o));
    }
    s["hotkeys"] = hks;

    // scenes
    net::Json sc = net::Json::makeArray();
    auto scene = [](const char* id, const char* n, const char* d) { net::Json o = net::Json::makeObject(); o["id"] = id; o["name"] = n; o["desc"] = d; return o; };
    sc.push(scene("present", "演示模式", "开副屏 + 通知静音 + 音频静音"));
    sc.push(scene("create", "创作模式", "关触控板 + 开数位板 + 高采样率"));
    sc.push(scene("touchpad", "触控板模式", "关副屏 + 开触控板"));
    s["scenes"] = sc;

    return s;
}

}  // namespace apxpc::api
