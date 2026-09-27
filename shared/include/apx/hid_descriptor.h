// ============================================================================
// HID 报告描述符生成（docs/PROTOCOL.md §2.1、ARCHITECTURE.md §4）
//
// f_hid 只能实例化一次，因此 6 个功能塞进同一份描述符的 6 个 TLC，
// PC 端（Windows）会为每个 TLC 枚举出独立子设备。
//
// 约定：
//   - 每个报告首字节为 Report ID
//   - 报告长度必须定长，故 IMU 报告按 N=16 发送（113B），实际样本数看 sampleCount
//   - 能被标准 Usage 表达的字段一律声明 UNIT + UNIT_EXPONENT
//   - 复用型载荷（一个 Report ID 承载多种传感器）声明为厂商自定义/填充，
//     语义与单位由 units.h 按 sensorId 决定，PC 侧按偏移解析（见 README「已知限制」）
// ============================================================================
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

#include "apx/hid_layout.h"

namespace apx {

// ---- Usage Page（§4 表格）------------------------------------------------
enum : uint16_t {
    kPageGenericDesktop = 0x0001,
    kPageKeyboard       = 0x0007,
    kPageLed            = 0x0008,
    kPageButton         = 0x0009,
    kPageSensor         = 0x0020,
    kPageDigitizer      = 0x000D,
    kPageConsumer       = 0x000C,
    kPageVendor         = 0xFF00,
    kPageVendorFF       = 0x00FF,  // v1.8：PTP 认证 blob 用的厂商定义页（低字节 0xFF）

    // ---- Digitizer Page（0x0D）Usage（v1.8 PTP 新增；v1.3 已有 TipSwitch/
    //      ContactId/ContactCount/ContactMax/ScanTime 的不在此重复）----------
    kUsageTouchPad      = 0x0005,
    kUsageFinger        = 0x0022,
    kUsageConfidence    = 0x0047,
    kUsagePadType       = 0x0059,
    kUsageInputMode     = 0x0052,
    kUsageSurfaceSwitch = 0x0057,
    kUsageButtonSwitch  = 0x0058,
    kUsageConfiguration = 0x000E,
    kPageBattery        = 0x0085,
};

// ---- Mouse / Keyboard（§2.10 触控板与蓝牙键盘）----------------------------
// Generic Desktop Page (0x01)
enum : uint16_t {
    kUsagePointer = 0x0001,
    kUsageMouse   = 0x0002,
    kUsageGamepad = 0x0005,  // Game Pad（游戏手柄，v1.x）
    // kUsageAxisX(0x30) / kUsageAxisY(0x31) 已在下方「绝对坐标」段声明并复用
    kUsageWheel   = 0x0038,
    kUsageAcPan   = 0x0238,
    kUsageKbLeftCtrl = 0x00E0,  // 修饰键 0xE0..0xE7 连续，按偏移使用
};

// Keyboard Page (0x07)
enum : uint16_t {
    kUsageKeyboard = 0x0006,
};

// Button Page (0x09)：鼠标按键 1..3 连续
enum : uint16_t {
    kUsageButton1 = 0x0001,
};

// ---- Sensor Page（0x20）Usage（HID Sensor Usage Tables）-------------------
enum : uint16_t {
    kUsageSensor            = 0x0001,
    kUsageSensorCollection  = 0x0020,
    kUsageAls               = 0x0041,
    kUsageProximity         = 0x0011,
    kUsagePressure          = 0x0031,
    kUsageTemperature       = 0x0033,
    kUsageHumidity          = 0x0032,
    kUsageAccel3D           = 0x0073,
    kUsageGyro3D            = 0x0076,
    kUsageCompass3D         = 0x0083,
    kUsageInclinometer3D    = 0x0086,
    kUsageDeviceOrientation = 0x008A,
    kUsageAccelAxisX        = 0x0453,
    kUsageAccelAxisY        = 0x0454,
    kUsageAccelAxisZ        = 0x0455,
    kUsageSensorTimestamp   = 0x0529,
    kUsageSensorState       = 0x0201,
    kUsageSensorEvent       = 0x0202,
    kUsagePropReportState   = 0x0316,
    kUsagePropSensitivityAbs = 0x030F,
    kUsagePropReportInterval = 0x030E,
    kUsagePropSensorStatus  = 0x0304,
    kUsagePropPowerState    = 0x0319,
    kUsageLightIllum        = 0x04D1,
    kUsageHumanPresence     = 0x04B1,
    kUsageAtmPressure       = 0x0430,
    kUsageEnvTemperature    = 0x0434,
    kUsageAtmHumidity       = 0x0433,
    kUsageMagnFluxX         = 0x0485,
    kUsageMagnFluxY         = 0x0486,
    kUsageMagnFluxZ         = 0x0487,
    kUsageTiltX             = 0x047F,
    kUsageTiltY             = 0x0480,
    kUsageTiltZ             = 0x0481,
    kUsageCustomValue0      = 0x0543,
    kUsageCustomValue1      = 0x0544,
};

// ---- Digitizer Page（0x0D）Usage ------------------------------------------
enum : uint16_t {
    kUsageTouchScreen   = 0x0004,
    kUsageStylus        = 0x0020,
    kUsageTipPressure   = 0x0030,
    kUsageInRange       = 0x0032,
    kUsageXTilt         = 0x003D,
    kUsageYTilt         = 0x003E,
    kUsageAzimuth       = 0x003F,
    kUsageTipSwitch     = 0x0042,
    kUsageBarrelSwitch  = 0x0044,
    kUsageEraser        = 0x0045,
    kUsageContactId     = 0x0051,
    kUsageContactCount  = 0x0054,
    kUsageContactMax    = 0x0055,
    kUsageScanTime      = 0x0056,
};

// ---- Generic Desktop Page（0x01）Usage（绝对坐标用）-----------------------
enum : uint16_t {
    kUsageAxisX  = 0x0030,
    kUsageAxisY  = 0x0031,
    kUsageAxisRx = 0x0033,  // 右摇杆 X（v1.x 游戏手柄）
    kUsageAxisRy = 0x0034,  // 右摇杆 Y（v1.x 游戏手柄）
};

// ---- Consumer Page（0x0C）Usage -------------------------------------------
enum : uint16_t {
    kUsageConsumerControl = 0x0001,
    kUsageVolumeUp        = 0x00E9,
    kUsageVolumeDown      = 0x00EA,
    kUsageMute            = 0x00E2,
    kUsagePower           = 0x0030,
    kUsagePlayPause       = 0x00CD,
    kUsageScanPrevTrack   = 0x00B6,
    kUsageScanNextTrack   = 0x00B5,
    kUsageZoomIn          = 0x0227,
    kUsageZoomOut         = 0x0228,
};

// ---- 报告描述符 -----------------------------------------------------------
std::vector<uint8_t> buildReportDescriptor();
size_t writeReportDescriptor(uint8_t* dst, size_t cap);

// ---- 蓝牙 HID 版描述符（无线模式）------------------------------------------
// Mouse / Keyboard / Consumer / Gamepad 四个 TLC。
//
// **Report ID 与 USB 版无关**：蓝牙 HID 是独立设备，编号从 1 重新开始
// （1=Mouse 2=Keyboard 3=Consumer 4=Gamepad），因此不能复用 USB 版的常量。
std::vector<uint8_t> buildBtReportDescriptor();
size_t writeBtReportDescriptor(uint8_t* dst, size_t cap);

uint32_t maxReportLength();

uint16_t   usagePageOf(uint8_t reportId);
uint16_t   usageOf(uint8_t reportId);
const char* tlcName(uint8_t reportId);

const uint8_t* tlcReportIds(size_t& count);

}  // namespace apx