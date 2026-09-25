// apxdisp —— L4（PC 副屏驱动与推流）命令行入口
//
// 常用命令：
//   apxdisp --self-test                 离线自测（无需虚拟显示器与手机）
//   apxdisp --list-encoders             探测本机可用编码后端
//   apxdisp --run --mode 1080x2400@60   推流（需要已安装 IddCx 虚拟显示器 + AOA 设备）
//   apxdisp --run --transport loopback --dump frames.bin
#include <cstdio>
#include <cstring>
#include <csignal>
#include <fstream>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <mmsystem.h>   // timeBeginPeriod（链接 winmm）
#include <tlhelp32.h>
#endif

#include "capture/i_capture.hpp"
#include "common/log.hpp"
#include "common/types.hpp"
#include "encode/i_encoder.hpp"
#include "encode/lz4.hpp"
#include "inject/touch_frame.hpp"
#include "pipeline/pipeline.hpp"
#include "protocol/frame_format.hpp"
#include "transport/frame_writer.hpp"
#include "transport/i_transport.hpp"

namespace {

using namespace apxdisp;

struct Options {
    bool selfTest = false;
    bool listEncoders = false;
    bool run = false;
    bool verbose = false;
    std::string mode = "1080x2400@60";
    std::string codec = "hevc";
    std::string backend = "auto";
    std::string transport = "auto";
    std::string dump;
    std::string capture = "auto";
    std::string tcpHost;        // --host：TCP 客户端连接目标（副屏无线/NCM/adb reverse）
    uint16_t tcpPort = 9502;    // --port（手机媒体服务端 TcpMediaChannel 端口）
    bool noHandshake = false;   // --no-handshake
    uint32_t bitrateKbps = 12000;
    uint32_t maxFps = 60;
    uint32_t seconds = 0;   // 0 = 一直跑
};

// v1.10：控制台中文输出统一走 GBK——源码字面量是 UTF-8，中文 Windows
// 控制台默认 GBK 代码页，直接 printf 必乱码（SetConsoleOutputCP 在部分
// 终端/字体组合下仍不生效）。全部中文 printf 换用本函数。
void gprintf(const char* fmt, ...) {
    char utf8[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(utf8, sizeof(utf8), fmt, ap);
    va_end(ap);
#ifdef _WIN32
    wchar_t wbuf[2048];
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wbuf, 2048);
    if (wlen > 0) {
        char gbk[4096];
        const int glen = WideCharToMultiByte(936, 0, wbuf, wlen, gbk, sizeof(gbk), nullptr, nullptr);
        if (glen > 0) {
            printf("%s", gbk);
            return;
        }
    }
#endif
    printf("%s", utf8);
}

void usage() {
    printf(
        "用法: apxdisp [选项]\n"
        "  --self-test               离线自测（帧格式/LZ4/组帧/触控解析，不需要设备）\n"
        "  --list-encoders           探测并打印本机可用编码后端\n"
        "  --run                     启动下行推流链路\n"
        "  --mode WxH@FPS            分辨率与刷新率（默认 1080x2400@60，手机竖屏）\n"
        "  --codec h264|hevc|av1|raw_lz4\n"
        "  --backend auto|nvenc|qsv|amf|mf|raw_lz4\n"
        "  --capture auto|dda|null\n"
        "  --transport auto|tcp|winusb|loopback\n"
        "  --host IP                TCP 模式连接的手机地址（副屏无线/NCM/adb reverse）\n"
        "  --port N                 TCP 端口（默认 9502，手机媒体通道）\n"
        "  --no-handshake           跳过 §4 控制面握手直接推流（手机端应答未实现时用）\n"
        "  --bitrate KBPS            目标码率（默认 12000）\n"
        "  --fps N                   最大帧率（默认 60）\n"
        "  --seconds N               运行时长（0 = 直到 Ctrl+C）\n"
        "  --dump PATH               把下行帧落盘（loopback 有效）\n"
        "  -v, --verbose             打开 Debug 日志\n"
        "  -h, --help\n");
}

bool parseMode(const std::string& s, uint32_t& w, uint32_t& h, uint32_t& fps) {
    // 形如 1080x2400@60
    size_t x = s.find('x');
    size_t at = s.find('@');
    if (x == std::string::npos || at == std::string::npos) return false;
    w = static_cast<uint32_t>(std::stoul(s.substr(0, x)));
    h = static_cast<uint32_t>(std::stoul(s.substr(x + 1, at - x - 1)));
    fps = static_cast<uint32_t>(std::stoul(s.substr(at + 1)));
    return w > 0 && h > 0 && fps > 0;
}

CodecId parseCodec(const std::string& s) {
    if (s == "h264" || s == "avc") return CodecId::H264;
    if (s == "hevc" || s == "h265") return CodecId::HEVC;
    if (s == "av1") return CodecId::AV1;
    if (s == "mjpeg") return CodecId::MJPEG;
    return CodecId::RawLz4;
}

EncoderBackend parseBackend(const std::string& s) {
    if (s == "nvenc") return EncoderBackend::NvEnc;
    if (s == "qsv") return EncoderBackend::QuickSync;
    if (s == "amf") return EncoderBackend::Amf;
    if (s == "mf") return EncoderBackend::MediaFoundation;
    if (s == "raw_lz4") return EncoderBackend::RawLz4;
    return EncoderBackend::None;
}

// --------------------------------------------------------------- 自测 ----
int g_failures = 0;

void check(const char* name, bool ok, const char* extra = nullptr) {
    printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", name,
           extra ? " -- " : "", extra ? extra : "");
    if (!ok) ++g_failures;
}

int runSelfTest() {
    gprintf("== apxdisp 自测 ==\n");

    // 1) 帧格式布局（PROTOCOL §3 / §3.1）
    gprintf("[1] 帧格式布局\n");
    check("ApxFrameHeader == 16B", sizeof(apx::ApxFrameHeader) == 16);
    check("VideoExtHeader == 20B (5 words)", sizeof(VideoExtHeader) == 20);
    check("ApxDirtyRect == 8B", sizeof(ApxDirtyRect) == 8);
    check("kVideoExtWords == 5", kVideoExtWords == 5);
    check("magic 'APX1' LE == 0x31585041", kFrameMagicLE == 0x31585041u);

    // 2) CRC32
    printf("[2] CRC32\n");
    const char* hello = "hello apx";
    const uint32_t c1 = crc32Of(hello, strlen(hello));
    const uint32_t c2 = crc32Of(hello, strlen(hello));
    check("同输入同输出", c1 == c2);
    std::string mutated = hello;
    mutated[0] = 'H';
    check("改动可检出", crc32Of(mutated.data(), mutated.size()) != c1);

    // 3) LZ4 往返
    gprintf("[3] LZ4 往返\n");
    std::vector<uint8_t> src;
    for (int i = 0; i < 4096; ++i) src.push_back(static_cast<uint8_t>((i * 31 % 251)));
    std::vector<uint8_t> packed(lz4CompressBound(src.size()), 0);
    const size_t packedLen = lz4Compress(src.data(), src.size(), packed.data(), packed.size());
    check("压缩成功", packedLen > 0 && packedLen < src.size());
    std::vector<uint8_t> back(src.size(), 0);
    const size_t backLen = lz4Decompress(packed.data(), packedLen, back.data(), back.size());
    check("解压长度一致", backLen == src.size());
    check("解压内容一致", backLen == src.size() && memcmp(back.data(), src.data(), src.size()) == 0);

    // 4) 抓屏 → 编码 → 组帧 → 回环 → 解帧
    gprintf("[4] 抓屏/编码/组帧/解帧 全链路\n");
    auto cap = createCapture(CaptureKind::Null);
    check("NullCapture 打开", cap && cap->open(CaptureTarget{}));
    auto enc = EncoderFactory::create(EncoderBackend::RawLz4, VideoParams{});
    check("RawLz4 编码器就绪", enc != nullptr);

    auto transport = createTransport(TransportKind::Loopback, UsbFilter{});
    check("Loopback 传输就绪", transport != nullptr);
    FrameWriter writer(transport->videoChannel());

    RawFrame frame{};
    check("抓到一帧", cap && cap->acquireFrame(frame, 100));

    EncodedPacket pkt{};
    check("编码成功", enc && enc->encode(frame, pkt));
    check("codecId == RAW_LZ4(4)", pkt.codec == CodecId::RawLz4);
    check("DirtyRects 非空", !pkt.dirty.rects.empty());

    // 强制分片，验证多片拼接
    writer.setMaxFragment(64);
    const size_t frames = writer.writeVideo(pkt, 0);
    check("分片写入成功", frames >= 2, ("片数=" + std::to_string(frames)).c_str());

    // 从回环通道读回并校验
    size_t totalPayload = 0;
    std::vector<uint8_t> reassembled;
    size_t parsedCount = 0;
    bool lastSeen = false;
    for (int i = 0; i < 64; ++i) {
        std::vector<uint8_t> buf(1u << 20);
        const size_t n = transport->videoChannel()->read(buf.data(), buf.size(), 10);
        if (n == 0) break;
        ParsedFrame pf{};
        if (!parseFrame(buf.data(), n, pf)) {
            check("解帧校验(magic/CRC/长度自洽)", false);
            break;
        }
        ++parsedCount;
        check("streamId == 0(video)", pf.header.streamId == apx::kStreamVideo);
        check("headerExtWords == 5", pf.header.headerExtWords == kVideoExtWords);
        check("dirtyRectCount 与 rects 一致",
              pf.rects.size() == pf.video.dirtyRectCount);
        check("codecId 回读一致",
              pf.video.codecId == static_cast<uint16_t>(CodecId::RawLz4));
        check("宽高回读一致",
              pf.video.width == pkt.width && pf.video.height == pkt.height);
        reassembled.insert(reassembled.end(), pf.payload, pf.payload + pf.payloadLen);
        totalPayload += pf.payloadLen;
        if (pf.header.flags & apx::kFlagLastFragment) { lastSeen = true; break; }
    }
    check("收到 last_fragment 标记", lastSeen);
    check("分片拼回原码流", reassembled == pkt.bytes,
          ("拼回=" + std::to_string(reassembled.size()) + " 原始=" + std::to_string(pkt.bytes.size())).c_str());

    // 5) 触控上行解析（PROTOCOL §2.5 / §3.2）
    gprintf("[5] 触控上行解析\n");
    std::vector<uint8_t> payload;
    payload.push_back(0x03);  // flags: 笔在量程内 + 笔尖接触
    payload.push_back(0x02);  // contactCount = 2
    uint8_t ts[8];
    putU64(ts, 123456789ull);
    payload.insert(payload.end(), ts, ts + 8);
    for (int i = 0; i < 2; ++i) {
        uint8_t c[8];
        putU16(c + 0, static_cast<uint16_t>(i + 1));
        putU16(c + 2, static_cast<uint16_t>(32767 + i));
        putU16(c + 4, static_cast<uint16_t>(1000 + i));
        putU16(c + 6, 0x7FFF);
        payload.insert(payload.end(), c, c + 8);
    }
    uint8_t pen[10] = {0};
    putU16(pen + 0, static_cast<uint16_t>(-10));  // tiltX = -10
    putU16(pen + 2, 20);                          // tiltY = 20
    putU16(pen + 4, 90);                          // orientation
    payload.insert(payload.end(), pen, pen + 10);

    TouchFrame tf{};
    check("触控帧解析成功", parseTouchFrame(payload.data(), payload.size(), tf));
    check("触点数 == 2", tf.contacts.size() == 2);
    check("触点坐标回读一致", tf.contacts.size() == 2 && tf.contacts[1].x == 32768);
    check("笔在量程且笔尖按下", tf.penInRange() && tf.penTipDown());
    check("笔倾斜回读一致", tf.hasPen && tf.pen.tiltX == -10 && tf.pen.tiltY == 20);

    // 6) 坐标映射
    gprintf("[6] 坐标映射\n");
    InjectTarget tgt{};
    tgt.originX = 1920; tgt.originY = 0; tgt.width = 1080; tgt.height = 2400;
    auto p = mapToVirtualScreen(65535, 32767, tgt);
    check("右上角映射", p.x == 1920 + 1079, ("x=" + std::to_string(p.x)).c_str());
    check("Y 中值映射", p.y == 1199, ("y=" + std::to_string(p.y)).c_str());

    gprintf("== 自测结束：%s（失败 %d 项）==\n", g_failures == 0 ? "全部通过" : "存在失败", g_failures);
    return g_failures == 0 ? 0 : 1;
}

int listEncoders() {
    gprintf("== 编码后端探测 ==\n");
    const auto caps = EncoderFactory::probe();
    for (const auto& c : caps) {
        printf("  %-8s %-10s H264=%d HEVC=%d AV1=%d  %s\n",
               backendName(c.backend), c.available ? "可用" : "不可用",
               c.supportsH264, c.supportsHevc, c.supportsAv1, c.detail.c_str());
    }
    return 0;
}

// v1.10：Ctrl+C 优雅退出（此前 --seconds 0 被写死成 5 秒，副屏无法常驻）
static volatile std::sig_atomic_t g_run = 1;
void onCtrlC(int) { g_run = 0; }

int runPipeline(const Options& opt) {
    uint32_t w = 1080, h = 2400, fps = 60;
    if (!parseMode(opt.mode, w, h, fps)) {
        gprintf("无法解析 --mode %s（应为 宽x高@帧率）\n", opt.mode.c_str());
        return 2;
    }

    PipelineConfig cfg{};
    cfg.video.width = w;
    cfg.video.height = h;
    cfg.video.frameRateX100 = fps * 100;
    cfg.video.bitrateKbps = opt.bitrateKbps;
    cfg.video.codec = parseCodec(opt.codec);
    cfg.video.gop = 1;
    cfg.video.bFrames = false;
    cfg.backend = parseBackend(opt.backend);
    cfg.captureKind = (opt.capture == "null") ? CaptureKind::Null
                    : (opt.capture == "dda") ? CaptureKind::DesktopDuplication
                                             : CaptureKind::Auto;
    cfg.transportKind = (opt.transport == "loopback") ? TransportKind::Loopback
                      : (opt.transport == "winusb") ? TransportKind::WinUsb
                                                    : TransportKind::Auto;
    // v1.10：TCP 承载接入（PipelineConfig.transportSpec 优先于 transportKind）
    if (opt.transport == "tcp") {
        cfg.transportSpec = TransportSpec::tcpClient(opt.tcpHost, opt.tcpPort);
    }
    cfg.enableHandshake = !opt.noHandshake;
    // --no-handshake 同时禁用心跳：手机端无心跳应答实现，开着会触发假断线
    // 自愈重连循环（真机实测：旧连接被弃、新连接无人 accept，数据黑洞）。
    cfg.enableHeartbeat = !opt.noHandshake;
    // v1.10：取消分片（4MB 上限=协议 MAX_PAYLOAD）——IDR 超过旧 256KB 限制时
    // 会被切片，而手机端慢路径重组会把各片的 rects 字节混进码流（真机实测
    // 硬解零输出）。TCP 无 MTU 顾虑，单包整帧走手机快路径，一并绕开该缺陷。
    cfg.maxFragmentBytes = 4u * 1024u * 1024u;
    cfg.maxFps = opt.maxFps;
    cfg.injectTarget.width = w;
    cfg.injectTarget.height = h;

    Pipeline pipeline;
    std::string err;
    if (!pipeline.start(cfg, &err)) {
        gprintf("启动失败：%s\n", err.c_str());
        return 1;
    }
    gprintf("链路已启动：%ux%u@%u codec=%s backend=%s transport=%s\n", w, h, fps,
           codecName(cfg.video.codec), opt.backend.c_str(), opt.transport.c_str());
    gprintf("按 Ctrl+C 停止。\n");

    std::signal(SIGINT, onCtrlC);
    uint32_t elapsed = 0;
    while (g_run && (opt.seconds == 0 || elapsed < opt.seconds)) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        ++elapsed;
        const auto& s = pipeline.stats();
        gprintf("  抓屏 %llu 编码 %llu 发送 %llu 丢弃 %llu  抓屏 %.2fms 编码 %.2fms 传输 %.2fms  %.1f fps  %.2f MB\n",
               (unsigned long long)s.framesCaptured, (unsigned long long)s.framesEncoded,
               (unsigned long long)s.framesSent, (unsigned long long)s.framesDropped,
               s.captureMsAvg, s.encodeMsAvg, s.transportMsAvg, s.fpsActual,
               s.bytesSent / 1048576.0);
        fflush(stdout);
    }
    pipeline.stop();
    return 0;
}

}  // namespace

// v1.10：用同目录 adb.exe 探测手机 IP（USB 连接时权威；失败返回空用 ini 旧值）
static std::string detectPhoneIp(const std::string& dir) {
    char cmd[512];
    snprintf(cmd, sizeof cmd, "\"%sadb.exe\" shell ip addr show wlan0 2>nul", dir.c_str());
    FILE* f = _popen(cmd, "r");
    if (!f) return "";
    char buf[4096] = {};
    const size_t n = fread(buf, 1, sizeof buf - 1, f);
    _pclose(f);
    const std::string out(buf, n);
    const auto pos = out.find("inet ");
    if (pos == std::string::npos) return "";
    const auto s = pos + 5;
    const auto e = out.find(' ', s);
    if (e == std::string::npos || e <= s) return "";
    return out.substr(s, e - s);
}

// v1.10：双击 exe（无参数）= 读同目录 apxdisp.ini 自动推流——
// 此前无参数只打印帮助就退出（控制台一闪而过，用户以为「打不开」）。
// ini 不存在时生成模板；连接失败自动重试（手机 App 随时接入都能接上）。
int autoRun() {
    char exePath[MAX_PATH] = {};
#ifdef _WIN32
    GetModuleFileNameA(nullptr, exePath, MAX_PATH);
#endif
    std::string dir(exePath);
    dir = dir.substr(0, dir.find_last_of('\\') + 1);
    const std::string iniPath = dir + "apxdisp.ini";

    std::string host;
    uint16_t port = 9502;
    {
        std::ifstream in(iniPath);
        if (!in) {
            std::ofstream out(iniPath);
            out << "# 全能外设副屏配置\n# 手机 IP（手机和电脑需同一 WiFi；IP 变了改这里）\nhost=192.168.2.182\nport=9502\n";
            out.close();
            std::ifstream in2(iniPath);
            std::string line;
            while (std::getline(in2, line)) {
                if (line.rfind("host=", 0) == 0) host = line.substr(5);
                else if (line.rfind("port=", 0) == 0) port = static_cast<uint16_t>(std::stoul(line.substr(5)));
            }
        } else {
            std::string line;
            while (std::getline(in, line)) {
                if (line.rfind("host=", 0) == 0) host = line.substr(5);
                else if (line.rfind("port=", 0) == 0 && !line.substr(5).empty())
                    port = static_cast<uint16_t>(std::stoul(line.substr(5)));
            }
        }
    }
    if (host.empty()) { gprintf("apxdisp.ini 缺少 host 配置\n"); return 2; }

    // v1.10：**USB 直连优先**——adb forward 把 PC 的 127.0.0.1:9502 转发到
    // 手机 9502：延迟最低（~30ms）、不依赖 WiFi、手机 IP 变化免疫（用户
    // 「推不上去」的根因就是手机 WiFi 断开后 ini IP 失效）。forward 注册
    // 失败（没插线/无设备）才回退 WiFi 直连（detectPhoneIp / ini）。
    {
        const std::string fwdCmd = "\"" + dir + "adb.exe\" forward tcp:9502 tcp:9502 >nul 2>&1";
        if (system(fwdCmd.c_str()) == 0) {
            // v1.10：forward 注册成功即无条件走 USB 直连（此前 --list 探测在
            // 部分 adb 版本下误判，回退 WiFi 后 connect 超时——真机实测）。
            host = "127.0.0.1";
            gprintf("承载：USB 直连（127.0.0.1:9502 -> 手机 9502 媒体通道）\n");
        } else {
            const std::string live = detectPhoneIp(dir);
            if (!live.empty() && live != host) {
                gprintf("手机 WiFi IP 已更新：%s -> %s\n", host.c_str(), live.c_str());
                host = live;
                std::ofstream out(iniPath);
                out << "# AllPeriph display config\nhost=" << host << "\nport=" << port << "\n";
            }
        }
    }

    Options opt;
    opt.run = true;
    opt.transport = "tcp";
    opt.tcpHost = host;
    opt.tcpPort = port;
    opt.noHandshake = true;
    // v1.10：双击场景固定走 **MF 软编 H264**——手机端解码器只认 H264/HEVC，
    // 而默认 codec=HEVC 会先探测 HEVC 再降级（慢且日志噪音）；本机无 NVENC/
    // QSV/AMF SDK，backend=auto 的探测也是空转。直接锁定最快可用路径。
    opt.codec = "h264";
    opt.backend = "mf";
    opt.maxFps = 30;
    opt.bitrateKbps = 8000;

    gprintf("全能外设副屏推流：%s:%u（Ctrl+C 停止）\n", host.c_str(), port);
    for (;;) {
        const int rc = runPipeline(opt);
        gprintf("推流结束（rc=%d），3 秒后自动重试（请在手机上打开副屏页面）…\n", rc);
        std::this_thread::sleep_for(std::chrono::seconds(3));
    }
}

// v1.10：单实例——旧实例占着手机端的唯一 accept 位，新实例永远连不上
//（「只有第一次有画面」的根因）。启动时终止其他同名进程。
static void killOtherInstances() {
#ifdef _WIN32
    wchar_t selfName[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, selfName, MAX_PATH);
    const wchar_t* selfBase = wcsrchr(selfName, L'\\');
    selfBase = selfBase ? selfBase + 1 : selfName;
    // taskkill + PID 过滤：杀掉所有同名旧实例，保留自己
    char cmd[512];
    snprintf(cmd, sizeof cmd,
             "taskkill /f /im \"%ls\" /FI \"PID ne %lu\" >nul 2>&1",
             selfBase, static_cast<unsigned long>(GetCurrentProcessId()));
    system(cmd);
    Sleep(200);  // 让旧实例的 TCP 连接完成断开（手机端 EOF → 重新 accept）
#endif
}

int main(int argc, char** argv) {
#ifdef _WIN32
    // v1.10：控制台中文修复——日志已转 GBK 输出，原生匹配中文控制台
    SetConsoleOutputCP(936);
    // v1.10：把系统定时器粒度从默认 15.6ms 提到 1ms。抓屏线程按 maxFps 做
    // sleep 节流，粗粒度会让「睡 26ms」变成「睡 31ms」——实测 30fps 目标
    // 只能跑出 ~21fps。进程退出时系统自动恢复，无需 timeEndPeriod。
    timeBeginPeriod(1);
#endif
    killOtherInstances();
    Options opt;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> std::string { return (i + 1 < argc) ? argv[++i] : std::string(); };
        if (a == "--self-test") opt.selfTest = true;
        else if (a == "--list-encoders") opt.listEncoders = true;
        else if (a == "--run") opt.run = true;
        else if (a == "--mode") opt.mode = next();
        else if (a == "--codec") opt.codec = next();
        else if (a == "--backend") opt.backend = next();
        else if (a == "--capture") opt.capture = next();
        else if (a == "--transport") opt.transport = next();
        else if (a == "--bitrate") opt.bitrateKbps = static_cast<uint32_t>(std::stoul(next()));
        else if (a == "--fps") opt.maxFps = static_cast<uint32_t>(std::stoul(next()));
        else if (a == "--seconds") opt.seconds = static_cast<uint32_t>(std::stoul(next()));
        else if (a == "--host") opt.tcpHost = next();
        else if (a == "--port") opt.tcpPort = static_cast<uint16_t>(std::stoul(next()));
        else if (a == "--no-handshake") opt.noHandshake = true;
        else if (a == "--dump") opt.dump = next();
        else if (a == "-v" || a == "--verbose") opt.verbose = true;
        else if (a == "-h" || a == "--help") { usage(); return 0; }
        else { gprintf("未知参数：%s\n", a.c_str()); usage(); return 2; }
    }

    // v1.10：默认 Warn——诊断/就绪等 Info 日志只在 -v 时显示，
    // 双击场景控制台保持干净（仅统计行与真错误）。
    logSetLevel(opt.verbose ? LogLevel::Debug : LogLevel::Warn);

    if (opt.selfTest) return runSelfTest();
    if (opt.listEncoders) return listEncoders();
    if (opt.run) return runPipeline(opt);
    return autoRun();   // v1.10：无参数 = 读 ini 自动推流（双击即用）
}