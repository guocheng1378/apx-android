// ctrl_frame 纯逻辑单测：覆盖 VendorStatus 解析偏移与类型。
#include "apxpc/ctrl/ctrl_frame.hpp"
#include "apx/hid_layout.h"

#include <cstdio>
#include <cstring>

using namespace apxpc;
using namespace apxpc::ctrl;

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } } while (0)

static void testParseVendorStatus() {
    // 构造一个符合协议的 24B VendorStatus 报告
    uint8_t data[24] = {0};
    data[0] = apx::kReportVendor;   // reportId = 5
    data[1] = 1;                     // status = running
    data[2] = 3;                     // linkSpeed = super
    // moduleMask = 0x0001020304050607 (uint64_t, offset 3)
    for (int i = 0; i < 8; ++i) data[3 + i] = static_cast<uint8_t>(i);
    // errorCode = 0xDEADBEEF (uint32_t, offset 11)
    data[11] = 0xEF; data[12] = 0xBE; data[13] = 0xAD; data[14] = 0xDE;
    // uptimeMs = 0x0102030405060708 (uint64_t, offset 15)
    for (int i = 0; i < 8; ++i) data[15 + i] = static_cast<uint8_t>(i + 1);
    data[23] = 0x42;                 // lastSeq (不解析，但保留位置)

    VendorStatusInfo info;
    StatusEx s = parseVendorStatus(data, sizeof(data), info);
    CHECK(s.ok());
    CHECK(info.status == 1);
    CHECK(info.linkSpeed == 3);
    CHECK(info.moduleMask == 0x0706050403020100ULL);  // 小端
    CHECK(info.errorCode == 0xDEADBEEFu);
    CHECK(info.uptimeMs == 0x0807060504030201ULL);   // 小端
}

static void testParseVendorStatusTooShort() {
    uint8_t data[23] = {0};
    data[0] = apx::kReportVendor;
    VendorStatusInfo info;
    StatusEx s = parseVendorStatus(data, sizeof(data), info);
    CHECK(!s.ok());
}

static void testParseVendorStatusWrongReportId() {
    uint8_t data[24] = {0};
    data[0] = 0x09;  // not report 5
    VendorStatusInfo info;
    StatusEx s = parseVendorStatus(data, sizeof(data), info);
    CHECK(!s.ok());
}

static void testParseVendorStatusLargeModuleMask() {
    uint8_t data[24] = {0};
    data[0] = apx::kReportVendor;
    data[1] = 2;
    data[2] = 4;
    // moduleMask = 0xFFFFFFFFFFFFFFFF (高位全 1，验证 uint64_t 不会截断)
    for (int i = 0; i < 8; ++i) data[3 + i] = 0xFF;
    // errorCode = 0
    // uptimeMs = 0
    VendorStatusInfo info;
    StatusEx s = parseVendorStatus(data, sizeof(data), info);
    CHECK(s.ok());
    CHECK(info.moduleMask == 0xFFFFFFFFFFFFFFFFULL);
}

int main() {
    testParseVendorStatus();
    testParseVendorStatusTooShort();
    testParseVendorStatusWrongReportId();
    testParseVendorStatusLargeModuleMask();
    if (g_fail == 0) { std::printf("ctrl_frame_test: ALL PASS\n"); return 0; }
    std::printf("ctrl_frame_test: %d FAILED\n", g_fail);
    return 1;
}
