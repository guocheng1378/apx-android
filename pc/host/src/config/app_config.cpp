#include "apxpc/config/app_config.hpp"
#include "apxpc/net/json.hpp"
#include "apxpc/log.hpp"

#include <cstdlib>
#include <filesystem>

namespace apxpc::config {
namespace {

std::map<std::string, HotkeyBinding> makeDefaults() {
    std::map<std::string, HotkeyBinding> m;
    m["screen.toggle"]   = {{"ctrl", "alt"}, "F1", true};
    m["screen.cycle"]    = {{"ctrl", "alt"}, "F2", true};
    m["touchpad.toggle"] = {{"ctrl", "alt"}, "F3", true};
    m["sensor.toggle"]   = {{"ctrl", "alt"}, "F4", true};

    m["audio.route"]     = {{"ctrl", "alt"}, "F6", true};
    m["device.toggle"]   = {{"ctrl", "alt"}, "F7", true};
    return m;
}

std::string expandPath(const std::string& p) {
    if (!p.empty()) return p;
    return defaultConfigPath();
}

}  // namespace

std::map<std::string, HotkeyBinding> defaultHotkeys() { return makeDefaults(); }

std::string defaultConfigPath() {
    const char* local = std::getenv("LOCALAPPDATA");
    std::string base = local ? local : ".";
    return (std::filesystem::path(base) / "AllPeriph" / "config.json").string();
}

AppConfig loadConfig(const std::string& path) {
    AppConfig cfg;
    cfg.hotkeys = makeDefaults();
    std::error_code ec;
    if (!std::filesystem::exists(expandPath(path), ec)) return cfg;

    std::FILE* f = std::fopen(expandPath(path).c_str(), "rb");
    if (!f) return cfg;
    std::string text;
    char buf[4096];
    while (size_t n = std::fread(buf, 1, sizeof buf, f)) text.append(buf, n);
    std::fclose(f);

    std::string err;
    auto j = net::Json::parse(text, &err);
    if (j.isNull()) {
        APX_LOGW("配置解析失败（{}），使用默认值", err.c_str());
        return cfg;
    }
    auto num = [&](const char* k, int def) {
        auto* v = j.find(k); return v ? v->asInt(def) : def;
    };
    auto str = [&](const char* k, const std::string& def) {
        auto* v = j.find(k); return v ? v->asString(def) : def;
    };
    auto bol = [&](const char* k, bool def) {
        auto* v = j.find(k); return v ? v->asBool(def) : def;
    };

    cfg.schemaVersion   = num("schemaVersion", kSchemaVersion);
    cfg.httpPort        = static_cast<uint16_t>(num("httpPort", 47990));
    cfg.autoOpenBrowser = bol("autoOpenBrowser", true);
    cfg.enableTray      = bol("enableTray", true);
    cfg.autostart       = bol("autostart", false);
    cfg.logLevel        = str("logLevel", "info");
    cfg.mode            = str("mode", "wired");

    cfg.displayEnabled  = bol("displayEnabled", false);
    cfg.displayW        = static_cast<uint32_t>(num("displayW", 1080));
    cfg.displayH        = static_cast<uint32_t>(num("displayH", 2400));
    cfg.displayHz       = static_cast<uint32_t>(num("displayHz", 60));
    cfg.displayOrientation = str("displayOrientation", "portrait");
    cfg.displayEncoder  = str("displayEncoder", "HEVC");
    cfg.displayBitrateMbps = static_cast<uint32_t>(num("displayBitrateMbps", 12));
    cfg.displayFps      = static_cast<uint32_t>(num("displayFps", 60));
    cfg.displayAdaptive = bol("displayAdaptive", true);

    cfg.touchpadEnabled = bol("touchpadEnabled", false);
    cfg.touchpadPath    = str("touchpadPath", "bt");
    cfg.touchpadSensitivity = j.find("touchpadSensitivity") ? j.find("touchpadSensitivity")->asNumber(1.0) : 1.0;
    cfg.touchpadScrollStep  = static_cast<uint32_t>(num("touchpadScrollStep", 3));
    cfg.touchpadAccel   = bol("touchpadAccel", true);
    cfg.touchpadNaturalScroll = bol("touchpadNaturalScroll", false);


    cfg.audioRoute      = str("audioRoute", "speaker");

    // 热键：仅覆盖文件中出现的项，缺失项保留默认
    if (auto* hk = j.find("hotkeys")) {
        for (const auto& [id, v] : hk->asObject()) {
            HotkeyBinding b;
            if (auto* m = v.find("mods")) {
                for (const auto& mm : m->asArray()) b.mods.push_back(mm.asString());
            }
            if (auto* k = v.find("key")) b.key = k->asString();
            if (auto* e = v.find("enabled")) b.enabled = e->asBool(true);
            cfg.hotkeys[id] = std::move(b);
        }
    }
    if (auto* sc = j.find("scenes")) {
        for (const auto& [id, v] : sc->asObject()) cfg.scenes[id] = v.asString();
    }
    if (auto* km = j.find("keymap")) {
        for (const auto& [k, v] : km->asObject()) cfg.keymap[k] = v.asString();
    }
    return cfg;
}

void saveConfig(const std::string& path, const AppConfig& cfg) {
    net::Json j = net::Json::makeObject();
    j["schemaVersion"]   = cfg.schemaVersion;
    j["httpPort"]        = cfg.httpPort;
    j["autoOpenBrowser"] = cfg.autoOpenBrowser;
    j["enableTray"]      = cfg.enableTray;
    j["autostart"]       = cfg.autostart;
    j["logLevel"]        = cfg.logLevel;
    j["mode"]            = cfg.mode;

    j["displayEnabled"]  = cfg.displayEnabled;
    j["displayW"]        = static_cast<double>(cfg.displayW);
    j["displayH"]        = static_cast<double>(cfg.displayH);
    j["displayHz"]       = static_cast<double>(cfg.displayHz);
    j["displayOrientation"] = cfg.displayOrientation;
    j["displayEncoder"]  = cfg.displayEncoder;
    j["displayBitrateMbps"] = static_cast<double>(cfg.displayBitrateMbps);
    j["displayFps"]      = static_cast<double>(cfg.displayFps);
    j["displayAdaptive"] = cfg.displayAdaptive;

    j["touchpadEnabled"] = cfg.touchpadEnabled;
    j["touchpadPath"]    = cfg.touchpadPath;
    j["touchpadSensitivity"] = cfg.touchpadSensitivity;
    j["touchpadScrollStep"]  = static_cast<double>(cfg.touchpadScrollStep);
    j["touchpadAccel"]   = cfg.touchpadAccel;
    j["touchpadNaturalScroll"] = cfg.touchpadNaturalScroll;


    j["audioRoute"]      = cfg.audioRoute;

    auto hk = net::Json::makeObject();
    for (const auto& [id, b] : cfg.hotkeys) {
        net::Json o = net::Json::makeObject();
        auto mods = net::Json::makeArray();
        for (const auto& m : b.mods) mods.push(std::string(m));
        o["mods"] = std::move(mods);
        o["key"] = b.key;
        o["enabled"] = b.enabled;
        hk[id] = std::move(o);
    }
    j["hotkeys"] = std::move(hk);

    auto sc = net::Json::makeObject();
    for (const auto& [id, v] : cfg.scenes) sc[id] = v;
    j["scenes"] = std::move(sc);

    auto km = net::Json::makeObject();
    for (const auto& [k, v] : cfg.keymap) km[k] = v;
    j["keymap"] = std::move(km);

    std::string p = expandPath(path);
    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(p).parent_path(), ec);
    std::FILE* f = std::fopen(p.c_str(), "wb");
    if (!f) { APX_LOGE("无法写入配置 {}", p.c_str()); return; }
    auto s = j.stringify(2);
    std::fwrite(s.data(), 1, s.size(), f);
    std::fclose(f);
}

}  // namespace apxpc::config
