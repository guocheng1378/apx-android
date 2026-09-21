// shared 协议库自测：不依赖任何第三方库，任一有 C++17 工具链的机器均可一键复验。
//
// 覆盖：结构体尺寸（编译期）、CRC32 标准向量、小端读写、分片与组装、单位换算往返、
//       报告打包/解析往返、描述符字节长度自洽、时钟偏移与漂移。
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>

#include "apx/clock.h"
#include "apx/frame.h"
#include "apx/hid_descriptor.h"
#include "apx/hid_layout.h"
#include "apx/sensors_id.h"
#include "apx/units.h"

namespace {

int g_pass = 0;
int g_fail = 0;

void report(bool ok, const char* file, int line, const char* expr) {
    if (ok) {
        ++g_pass;
    } else {
        ++g_fail;
        std::printf("FAIL %s:%d  %s\n", file, line, expr);
    }
}

#define CHECK(expr) report((expr), __FILE__, __LINE__, #expr)

void checkEq(long long got, long long want, const char* file, int line, const char* what) {
    if (got == want) {
        ++g_pass;
    } else {
        ++g_fail;
        std::printf("FAIL %s:%d  %s: got %lld, want %lld\n", file, line, what, got, want);
    }
}

#define CHECK_EQ(got, want) checkEq(static_cast<long long>(got), static_cast<long long>(want), __FILE__, __LINE__, #got)

// ---------------------------------------------------------- 编译期尺寸约束 --
static_assert(sizeof(apx::ApxFrameHeader) == 16, "帧头必须 16B");
static_assert(sizeof(apx::ImuSampleReport) == 9, "IMU 单样本报告 9B");
static_assert(sizeof(apx::LowFreqReport) == 24, "低频报告 24B");
static_assert(sizeof(apx::DigitizerHeader) == 12, "Digitizer 头 12B");
static_assert(sizeof(apx::DigitizerContact) == 8, "触点 8B");
static_assert(sizeof(apx::PenExtra) == 10, "笔附加 10B");
static_assert(sizeof(apx::VendorCommandHeader) == 8, "Vendor 命令头 8B");
static_assert(sizeof(apx::VendorStatus) == 24, "Vendor 状态 24B（v1.1：u64 掩码 + lastSeq）");
static_assert(sizeof(apx::BatteryReport) == 13, "电池 13B");
static_assert(sizeof(apx::ImuFeatureReport) == 10, "Feature 报告 10B（v1.6 补 Power State）");

// ------------------------------------------------------------ 描述符解析器 --
// 极简 HID item 解析：只统计每个 Report ID 的 Input/Output/Feature 位数
struct DescStats {
    uint32_t inputBits[256];
    uint32_t outputBits[256];
    uint32_t featureBits[256];

    // 必须整块清零：只清 inputBits 会让 outputBits/featureBits 读到栈垃圾，
    // 导致统计值随机偏高（曾表现为 featureBits 假失败）。
    DescStats() { std::memset(this, 0, sizeof(*this)); }
};

DescStats parseDescriptor(const std::vector<uint8_t>& d) {
    DescStats s;
    uint32_t reportSize = 0, reportCount = 0;
    uint8_t reportId = 0;
    size_t i = 0;
    while (i < d.size()) {
        const uint8_t prefix = d[i++];
        const uint8_t sizeCode = prefix & 0x03u;
        const uint8_t type = (prefix >> 2) & 0x03u;
        const uint8_t tag = prefix >> 4;
        const uint32_t bytes = (sizeCode == 3) ? 4u : static_cast<uint32_t>(sizeCode);
        if (i + bytes > d.size()) break;
        uint32_t val = 0;
        for (uint32_t k = 0; k < bytes; ++k) val |= static_cast<uint32_t>(d[i + k]) << (8 * k);
        i += bytes;

        if (type == 1) {  // Global
            if (tag == 7) reportSize = val;
            else if (tag == 9) reportCount = val;
            else if (tag == 8) reportId = static_cast<uint8_t>(val);
        } else if (type == 0) {  // Main
            const uint32_t bits = reportSize * reportCount;
            if (tag == 8) s.inputBits[reportId] += bits;
            else if (tag == 9) s.outputBits[reportId] += bits;
            else if (tag == 11) s.featureBits[reportId] += bits;
        }
    }
    return s;
}

}  // namespace

int main() {
    // ============================ §2.3/§2.4 传感器编号 ======================
    CHECK_EQ(apx::lowFreqToGlobal(0), apx::kSensorLight);
    CHECK_EQ(apx::globalToLowFreqRaw(apx::kSensorHeartRate), 9);
    CHECK(apx::isImuSensor(apx::kSensorMagUncal));
    CHECK(!apx::isImuSensor(apx::kSensorLight));
    CHECK(apx::isLowFreqSensor(apx::kSensorPressure));
    CHECK(!apx::isValidSensorId(0x06));
    CHECK_EQ(apx::kMaskImuAll, 0x3FULL);
    CHECK((apx::kMaskLowFreqAll >> 16) == 0x3FFULL);
    CHECK((apx::kMaskAll & (1ULL << apx::kSensorAccel)) != 0ULL);
    CHECK(std::strcmp(apx::sensorName(apx::kSensorGyro), "gyroscope") == 0);
    CHECK(std::strcmp(apx::moduleName(apx::kModuleTouch), "touch") == 0);
    CHECK_EQ(apx::kModuleTouch, 1ULL << 32);          // §2.9 触控
    CHECK_EQ(apx::kModuleDisplay, 1ULL << 37);        // §2.9 副屏视频
    CHECK_EQ(apx::kModuleTouchpad, 1ULL << 38);       // v1.4 触控板（相对位移）
    CHECK_EQ(apx::kModuleCamera, 1ULL << 39);         // v1.4 摄像头
    CHECK_EQ(apx::kMaskModules >> 32, 0xFFULL);       // bit32..39 全模块
    CHECK((apx::kMaskModules & apx::kMaskAll) == 0ULL);  // 模块位与传感器位不重叠

    // ================================ §3 帧头与 CRC =========================
    const char* kText = "123456789";
    CHECK_EQ(apx::crc32(kText, 9), 0xCBF43926u);  // CRC32 标准校验向量

    uint8_t hdr[16];
    apx::ApxFrameHeader h;
    h.magic[0] = 'A'; h.magic[1] = 'P'; h.magic[2] = 'X'; h.magic[3] = '1';
    h.streamId = apx::kStreamControl;
    h.flags = apx::kFlagKeyFrame;
    h.headerExtWords = 2;
    h.payloadLen = 0x01020304u;
    h.seq = 0x05060708u;
    CHECK(apx::writeHeader(hdr, sizeof(hdr), h));
    CHECK(hdr[0] == 'A' && hdr[3] == '1');
    CHECK_EQ(apx::getU32(hdr + 8), 0x01020304u);
    CHECK_EQ(apx::getU32(hdr + 12), 0x05060708u);
    CHECK_EQ(apx::getU16(hdr + 6), 2);

    apx::ApxFrameHeader h2;
    CHECK(apx::readHeader(hdr, sizeof(hdr), h2));
    CHECK_EQ(h2.payloadLen, 0x01020304u);
    CHECK_EQ(h2.streamId, apx::kStreamControl);
    hdr[0] = 'X';
    CHECK(!apx::readHeader(hdr, sizeof(hdr), h2));  // magic 错误必须失败

    // 载荷 CRC 追加与校验
    uint8_t payload[64];
    for (int i = 0; i < 32; ++i) payload[i] = static_cast<uint8_t>(i);
    const uint32_t total = apx::appendPayloadCrc(payload, 32);
    CHECK_EQ(total, 36u);
    CHECK(apx::verifyPayload(payload, total));
    payload[0] ^= 0xFF;
    CHECK(!apx::verifyPayload(payload, total));

    // ============================== §3 分片与组装 ===========================
    std::vector<uint8_t> body(1000);
    for (size_t i = 0; i < body.size(); ++i) body[i] = static_cast<uint8_t>(i & 0xFF);
    const size_t mtu = 512;
    const size_t count = apx::fragmentCountFor(body.size(), mtu);
    CHECK_EQ(apx::fragmentCapacity(mtu), 492u);
    CHECK_EQ(count, 3u);

    apx::FrameAssembler asmblr;
    std::vector<uint8_t> got;
    apx::ApxFrameHeader outHdr;
    bool finished = false;
    uint8_t frag[512];
    for (size_t i = 0; i < count; ++i) {
        size_t used = 0;
        bool isLast = false;
        CHECK(apx::buildFragment(frag, sizeof(frag), used, isLast, apx::kStreamVideo,
                                 apx::kFlagDropable, 77u, body.data(), body.size(), i, mtu));
        CHECK_EQ(isLast, (i + 1 == count));
        CHECK(apx::FrameAssembler().buffered() == 0u);
        CHECK(asmblr.push(frag, used, outHdr, got) == isLast);
        if (isLast) finished = true;
    }
    CHECK(finished);
    CHECK_EQ(outHdr.seq, 77u);
    CHECK_EQ(got.size(), 1000u);
    CHECK(got == body);
    CHECK(!asmblr.inProgress());

    // 坏 CRC 的分片必须整帧丢弃
    apx::FrameAssembler asm2;
    std::vector<uint8_t> got2;
    apx::ApxFrameHeader outHdr2;
    size_t used0 = 0;
    bool last0 = false;
    CHECK(apx::buildFragment(frag, sizeof(frag), used0, last0, apx::kStreamVideo, 0, 78u,
                             body.data(), body.size(), 0, mtu));
    frag[20] ^= 0x5A;  // 破坏载荷
    CHECK(!asm2.push(frag, used0, outHdr2, got2));
    CHECK(!asm2.inProgress());

    // ================================ §2.2 单位换算 =========================
    CHECK_EQ(apx::quantizeI16(apx::kSensorAccel, 9.80665f), 1000);          // 1g
    CHECK_EQ(apx::quantizeI16(apx::kSensorGyro, 1.0f), 5730);               // 1 rad/s
    CHECK_EQ(apx::quantizeI16(apx::kSensorMag, 50.0f), 5000);               // 50 µT
    CHECK_EQ(apx::quantizeI32(apx::kSensorLight, 1000.0f), 100000);         // 1000 lux
    CHECK_EQ(apx::quantizeI32(apx::kSensorPressure, 1013.0f), 1013);        // hPa
    CHECK_EQ(apx::quantizeI32(apx::kSensorDeviceTemp, 20.0f), 29315);       // 20°C
    CHECK_EQ(apx::quantizeI32(apx::kSensorHumidity, 55.5f), 5550);          // 55.5%
    CHECK_EQ(apx::quantizeI32(apx::kSensorStepCounter, 1234.0f), 1234);

    // 往返：定标 -> 物理值 -> Android 原始值
    CHECK(std::fabs(apx::dequantize(apx::kSensorAccel, 1000) - 9.80665) < 0.001);
    CHECK(std::fabs(apx::dequantize(apx::kSensorDeviceTemp, 29315) - 20.0) < 0.01);
    CHECK(std::fabs(apx::dequantize(apx::kSensorPressure, 1013) - 1013.0) < 0.001);

    // 饱和
    CHECK_EQ(apx::quantizeI16(apx::kSensorAccel, 1000.0f), 32767);
    CHECK_EQ(apx::quantizeI16(apx::kSensorAccel, -1000.0f), -32768);

    // 单位规格
    CHECK(apx::unitSpec(apx::kSensorAccel).exponent == -3);
    CHECK(apx::unitSpec(apx::kSensorMagUncal).hidUnit == apx::kUnitGauss);
    CHECK(apx::unitSpec(apx::kSensorDeviceTemp).hidUnit == apx::kUnitKelvin);

    // 轴向对齐
    const float in[3] = {1.0f, 2.0f, 3.0f};
    float out[3] = {0, 0, 0};
    apx::alignAxes(in, apx::AxisRotation::kNone, out);
    CHECK(out[0] == 1.0f && out[1] == 2.0f && out[2] == 3.0f);
    apx::alignAxes(in, apx::AxisRotation::kCw90, out);
    CHECK(out[0] == 2.0f && out[1] == -1.0f && out[2] == 3.0f);
    apx::alignAxes(in, apx::AxisRotation::kCw180, out);
    CHECK(out[0] == -1.0f && out[1] == -2.0f && out[2] == 3.0f);

    // ==================== §2.3 IMU 报告打包（v1.3 单样本格式）==============
    uint8_t rep[apx::kSizeImuBatchReport];
    const float xyz[3] = {9.80665f, 0.0f, -9.80665f};
    const size_t n1 = apx::packImuBatch(rep, sizeof(rep), apx::kSensorAccel, 0, 5000000u,
                                        1234567890123ULL, xyz, 1, 3);
    CHECK_EQ(n1, apx::kSizeImuBatchReport);
    CHECK_EQ(rep[0], apx::kReportImuBatch);
    CHECK_EQ(rep[1], 1);   // state = ready（accuracy=3 high）
    CHECK_EQ(rep[2], 0);   // event = unknown
    CHECK_EQ(static_cast<int16_t>(apx::getU16(rep + 3)), 1000);   // x = 1g（指数 -3）
    CHECK_EQ(static_cast<int16_t>(apx::getU16(rep + 5)), 0);      // y
    CHECK_EQ(static_cast<int16_t>(apx::getU16(rep + 7)), -1000);  // z
    // accuracy=0（unreliable）→ state = not_available(2)
    apx::packImuBatch(rep, sizeof(rep), apx::kSensorAccel, 0, 0, 0, xyz, 1, 0);
    CHECK_EQ(rep[1], 2);
    // 多样本时取最后一个（实时性优先）
    const float xyz3[9] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 9.80665f, 0.0f, 0.0f};
    apx::packImuBatch(rep, sizeof(rep), apx::kSensorAccel, 0, 0, 0, xyz3, 3, 3);
    CHECK_EQ(static_cast<int16_t>(apx::getU16(rep + 3)), 1000);   // 第 3 个样本的 x = 1g
    // 非法入参
    CHECK_EQ(apx::packImuBatch(rep, sizeof(rep), apx::kSensorLight, 0, 0, 0, xyz, 1, 0), 0u);
    CHECK_EQ(apx::packImuBatch(rep, sizeof(rep), apx::kSensorAccel, 0, 0, 0, xyz, 17, 0), 0u);

    // ============ §2.4 低频报告打包（v1.2：按 reportId 区分传感器）============
    uint8_t lf[apx::kSizeLowFreqReport];
    const size_t n2 = apx::packLowFreqById(lf, sizeof(lf), apx::kReportAls, apx::kStateReady,
                                           apx::kEventChange, 42ULL, 1000.0f, 0.0f, 0.0f);
    CHECK_EQ(n2, apx::kSizeLowFreqReport);
    CHECK_EQ(lf[0], apx::kReportAls);           // 类别由 Report ID 承载
    CHECK_EQ(lf[1], apx::kStateReady);
    CHECK_EQ(lf[2], apx::kEventChange);
    CHECK_EQ(lf[3], 0);                          // reserved
    CHECK_EQ(apx::getU32(lf + 12), 100000u);     // 环境光 lux × 100
    // 非低频 Report ID 必须被拒绝
    CHECK_EQ(apx::packLowFreqById(lf, sizeof(lf), apx::kReportImuBatch, 0, 0, 0, 0, 0, 0), 0u);
    CHECK_EQ(apx::packLowFreqById(lf, sizeof(lf), apx::kReportBattery, 0, 0, 0, 0, 0, 0), 0u);

    // Report ID ↔ 传感器编号映射。注意 0x16(电池温度) **不占**独立 Report，
    // 它含在 §2.8 电池报告里，所以映射表在此处断开 —— 不能用算术偏移代替。
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kReportAls), apx::kSensorLight);
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kReportAmbientTemp), apx::kSensorDeviceTemp);
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kReportHumidity), apx::kSensorHumidity);
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kReportHeartRate), apx::kSensorHeartRate);
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kSensorBatteryTemp), 0xFF);  // 不占 Report
    CHECK_EQ(apx::sensorIdOfLowFreqReport(apx::kReportImuBatch), 0xFF);
    CHECK_EQ(apx::lowFreqReportOfSensorId(apx::kSensorLight), apx::kReportAls);
    CHECK_EQ(apx::lowFreqReportOfSensorId(apx::kSensorHumidity), apx::kReportHumidity);
    CHECK_EQ(apx::lowFreqReportOfSensorId(apx::kSensorBatteryTemp), 0);
    // 双向一致：9 个低频传感器全部可往返
    for (size_t i = 0; i < apx::kLowFreqReportCount; ++i) {
        const uint8_t rid = apx::kLowFreqReportIds[i];
        CHECK_EQ(apx::lowFreqReportOfSensorId(apx::sensorIdOfLowFreqReport(rid)), rid);
    }

    // ============================ §2.7 状态与命令 ===========================
    uint8_t st[apx::kSizeVendorStatusRep];
    const uint64_t mask = apx::kModuleTouch | apx::kModuleVibrate | (1ULL << apx::kSensorAccel);
    CHECK_EQ(apx::packVendorStatus(st, sizeof(st), apx::kStatusRunning, apx::kSpeedSuper,
                                   mask, 7u, 60000ULL, 0x42),
             apx::kSizeVendorStatusRep);
    CHECK_EQ(st[0], apx::kReportVendor);
    CHECK_EQ(st[1], apx::kStatusRunning);
    CHECK_EQ(st[2], apx::kSpeedSuper);
    uint64_t gotMask = 0;
    for (int k = 0; k < 8; ++k) gotMask |= static_cast<uint64_t>(st[3 + k]) << (8 * k);
    CHECK_EQ(gotMask, mask);
    CHECK_EQ(apx::getU32(st + 11), 7u);
    CHECK_EQ(st[23], 0x42);  // lastSeq

    uint8_t outRep[apx::kSizeVendorOutReport];
    std::memset(outRep, 0, sizeof(outRep));
    outRep[0] = apx::kReportVendor;
    outRep[1] = apx::kCmdSetSensorMask;
    outRep[2] = 0x07;
    outRep[3] = 0;
    outRep[4] = 8;
    for (int i = 0; i < 8; ++i) outRep[8 + i] = static_cast<uint8_t>(i + 1);
    uint8_t pl[apx::kMaxVendorPayload];
    uint8_t cmd = 0, seq = 0;
    size_t plen = 0;
    CHECK(apx::parseVendorOut(outRep, sizeof(outRep), cmd, seq, pl, sizeof(pl), plen));
    CHECK_EQ(cmd, apx::kCmdSetSensorMask);
    CHECK_EQ(seq, 0x07);
    CHECK_EQ(plen, 8u);
    CHECK_EQ(pl[0], 1);
    outRep[0] = 0x09;  // 错误 Report ID
    CHECK(!apx::parseVendorOut(outRep, sizeof(outRep), cmd, seq, pl, sizeof(pl), plen));

    // ============================ §2.8 / §2.6 电池与按键 ====================
    uint8_t bat[apx::kSizeBatteryReport];
    CHECK_EQ(apx::packBattery(bat, sizeof(bat), 78, apx::kStateReady, 3900, -500, 305,
                              apx::kRemainingMinUnknown),
             apx::kSizeBatteryReport);
    CHECK_EQ(bat[0], apx::kReportBattery);
    CHECK_EQ(bat[1], 78);
    CHECK_EQ(apx::getU32(bat + 9), 0xFFFFFFFFu);
    uint8_t key[apx::kSizeConsumerReport];
    // v1.2：位图。同时按下 音量+ 与 静音
    const uint16_t kbm =
        static_cast<uint16_t>((1u << apx::kBitVolumeUp) | (1u << apx::kBitMute));
    CHECK_EQ(apx::packConsumerBitmap(key, sizeof(key), kbm), apx::kSizeConsumerReport);
    CHECK_EQ(key[0], apx::kReportConsumer);
    CHECK_EQ(apx::getU16(key + 1), kbm);
    CHECK_EQ((apx::getU16(key + 1) >> apx::kBitVolumeDown) & 1u, 0u);  // 未按下的位为 0
    CHECK_EQ(key[3], 0);  // reserved

    // ============================ 报告长度与描述符 ==========================
    CHECK_EQ(apx::reportSizeById(apx::kReportImuBatch), 9u);
    // v1.2：9 个低频 TLC 共用同一份 24 字节布局
    for (size_t i = 0; i < apx::kLowFreqReportCount; ++i) {
        CHECK_EQ(apx::reportSizeById(apx::kLowFreqReportIds[i]), 24u);
    }
    CHECK_EQ(apx::reportSizeById(apx::kReportDigitizer), 102u);
    CHECK_EQ(apx::reportSizeById(apx::kReportConsumer), 4u);   // v1.2：位图
    CHECK_EQ(apx::reportSizeById(apx::kReportVendor), 264u);
    CHECK_EQ(apx::reportSizeById(apx::kReportBattery), 13u);
    CHECK_EQ(apx::reportSizeById(0x7F), 0u);
    CHECK(apx::reportSizeById(apx::kReportImuBatch) <= apx::kMaxReportSize);
    CHECK(apx::reportSizeById(apx::kReportDigitizer) <= apx::kMaxReportSize);

    const std::vector<uint8_t> desc = apx::buildReportDescriptor();
    CHECK(!desc.empty());
    // 描述符总长必须 < 4096：f_hid 经 ConfigFS 写 report_desc 有内核侧上限。
    // 余量是**硬约束**：新增 TLC（如触控板的 Mouse TLC）前必须看这个数字，
    // 逼近上限时只能压缩低频传感器 TLC 的冗余位，不得减小关键报告长度。
    std::printf("INFO descriptor=%zu bytes, limit=4096, headroom=%zu\n",
                desc.size(), static_cast<size_t>(4096) - desc.size());
    CHECK(desc.size() < 4096u);
    const DescStats s = parseDescriptor(desc);
    // 每项 Main item 位数 / 8 + 1(Report ID) 必须等于 hid_layout.h 的长度常量
    // v1.11：传感器（IMU + 低频 7..15）与数位屏（rid 3）已移出 USB 描述符
    // （hidparse 除零蓝屏），断言改为「rid 不存在于描述符」（0 bits → 1）。
    CHECK_EQ(s.inputBits[apx::kReportImuBatch] / 8 + 1, 1);
    CHECK_EQ(s.inputBits[apx::kReportMouse] / 8 + 1, 6);   // v1.4 触控板
    for (size_t i = 0; i < apx::kLowFreqReportCount; ++i) {
        const uint8_t rid = apx::kLowFreqReportIds[i];
        CHECK_EQ(s.inputBits[rid] / 8 + 1, 1);
    }
    CHECK_EQ(s.inputBits[apx::kReportDigitizer] / 8 + 1, 1);
    // v1.11：USB 快捷键键盘（rid 21）：修饰 1 + reserved 1 + 按键数组 6 = 9B
    CHECK_EQ(s.inputBits[apx::kReportKeyboard] / 8 + 1, 9);
    CHECK(s.inputBits[apx::kReportKeyboard] % 8 == 0);
    CHECK_EQ(s.inputBits[apx::kReportConsumer] / 8 + 1, 4);
    CHECK_EQ(s.inputBits[apx::kReportVendor] / 8 + 1, 24);
    CHECK_EQ(s.outputBits[apx::kReportVendor] / 8 + 1, 264);
    CHECK_EQ(s.featureBits[apx::kReportVendor] / 8 + 1, 24);
    CHECK_EQ(s.inputBits[apx::kReportBattery] / 8 + 1, 13);
    CHECK_EQ(apx::maxReportLength(), 264u);

    // v1.11：TLC 元数据表增至 16 个（新增 USB 键盘，Report ID 21）
    size_t tlcCount = 0;
    const uint8_t* ids = apx::tlcReportIds(tlcCount);
    CHECK_EQ(tlcCount, 16u);
    CHECK_EQ(ids[0], apx::kReportImuBatch);
    CHECK_EQ(ids[1], apx::kReportMouse);
    CHECK_EQ(apx::usagePageOf(apx::kReportImuBatch), apx::kPageSensor);
    CHECK_EQ(apx::usagePageOf(apx::kReportDigitizer), apx::kPageDigitizer);
    CHECK_EQ(apx::usagePageOf(apx::kReportConsumer), apx::kPageConsumer);
    CHECK_EQ(apx::usagePageOf(apx::kReportVendor), apx::kPageVendor);
    CHECK_EQ(apx::usagePageOf(apx::kReportBattery), apx::kPageBattery);
    // 9 个低频 TLC 都声明在 Sensors Page，且都有非零 Usage 与可读名称
    for (size_t i = 0; i < apx::kLowFreqReportCount; ++i) {
        const uint8_t rid = apx::kLowFreqReportIds[i];
        CHECK_EQ(apx::usagePageOf(rid), apx::kPageSensor);
        CHECK(apx::usageOf(rid) != 0);
        CHECK(std::strcmp(apx::tlcName(rid), "unknown") != 0);
    }
    CHECK(std::strcmp(apx::tlcName(apx::kReportVendor), "vendor-ctrl") == 0);
    CHECK(std::strcmp(apx::tlcName(apx::kReportAls), "sensor-light") == 0);

    // 14 个 TLC 后描述符明显变大，缓冲区留足（4096 同时是 ConfigFS 的内核侧上限）
    uint8_t descBuf[4096];
    const size_t need = apx::writeReportDescriptor(descBuf, sizeof(descBuf));
    CHECK_EQ(need, desc.size());
    CHECK(need <= sizeof(descBuf));
    CHECK(descBuf[0] == desc[0] && descBuf[need - 1] == desc[need - 1]);
    CHECK_EQ(apx::writeReportDescriptor(descBuf, 8), desc.size());  // 缓冲区不足只返回需求长度

    // ================= 蓝牙 HID 版描述符（无线模式，v1.4）===================
    // 只含 Mouse(1) / Keyboard(2) / Consumer(3)，Report ID 与 USB 版无关。
    const std::vector<uint8_t> btDesc = apx::buildBtReportDescriptor();
    CHECK(!btDesc.empty());
    std::printf("INFO bt_descriptor=%zu bytes\n", btDesc.size());
    // 不含 9 个传感器 TLC，必然显著小于 USB 版
    CHECK(btDesc.size() < desc.size());
    const DescStats bt = parseDescriptor(btDesc);
    CHECK_EQ(bt.inputBits[1] / 8 + 1, 6);   // Mouse：按键1 + XY2 + 滚轮1 + 水平1 + ID
    CHECK_EQ(bt.inputBits[2] / 8 + 1, 8);   // Keyboard：修饰1 + 按键数组6 + ID
    CHECK_EQ(bt.outputBits[2] / 8, 1);      // LED 输出 1 字节（Report ID 不计入 output 位）
    CHECK_EQ(bt.inputBits[3] / 8 + 1, 4);   // Consumer：位图 2 + reserved 1 + ID
    CHECK(bt.inputBits[1] % 8 == 0);
    CHECK(bt.inputBits[2] % 8 == 0);
    CHECK(bt.inputBits[3] % 8 == 0);
    uint8_t btBuf[512];
    CHECK_EQ(apx::writeBtReportDescriptor(btBuf, sizeof(btBuf)), btDesc.size());
    CHECK(btBuf[0] == btDesc[0]);

    // ================================ §1 时钟同步 ===========================
    CHECK(apx::steadyNowNs() >= 0);
    apx::ClockSync sync(0.0);  // alpha=0：不做平滑，便于精确断言
    CHECK(!sync.valid());
    sync.addSample(1000, 5000, 3000);    // rtt=2000, off=-3000
    CHECK(sync.valid());
    CHECK_EQ(sync.offsetNs(), -3000);
    CHECK_EQ(sync.phoneToPc(5000), 2000);
    CHECK_EQ(sync.pcToPhone(2000), 5000);
    sync.addSample(12000, 15000, 14000);  // rtt=2000, off=-2000 → 1000ns/10000ns 漂移
    CHECK_EQ(sync.offsetNs(), -2000);
    const apx::ClockSync::Result r = sync.result();
    CHECK(r.valid);
    CHECK_EQ(r.samples, 2u);
    CHECK_EQ(r.minRttNs, 2000);
    CHECK_EQ(r.lastRttNs, 2000);
    CHECK(std::fabs(r.driftPpm - 100000.0) < 1.0);
    sync.addSample(1, 2, 0);  // rtt<0 的异常样本必须丢弃
    CHECK_EQ(sync.result().samples, 2u);
    sync.reset();
    CHECK(!sync.valid());
    CHECK_EQ(sync.result().offsetNs, 0);

    std::printf("apx_selftest: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
