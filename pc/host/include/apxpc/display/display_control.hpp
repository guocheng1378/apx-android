#pragma once
// 副屏真实开关：IDisplayController 三后端抽象。
//   A 第三方已签名驱动（具备运行时插拔）  —— 本环境不可得，故默认走 C
//   B 自研 IddCx（需签名流程）            —— 仅当确有必要时启动
//   C 降级：无插拔能力，仅控推流，界面标注"显示器仍存在"
#include <cstdint>
#include <memory>
#include <string>

namespace apxpc::display {

struct VirtualDisplaySpec {
    uint32_t width = 1080;
    uint32_t height = 2400;
    uint32_t refreshHz = 60;
    uint8_t  orientation = 0; // 0 竖 1 横
};

// 后端能力枚举，供界面标注 A/B/C
enum class Backend { A, B, C };

class IDisplayController {
public:
    virtual ~IDisplayController() = default;

    // 后端是否具备真实插拔能力（A/B=true，C=false）
    virtual bool available() const = 0;
    // 供界面标注：A / B / C 与可读说明
    virtual Backend backend() const = 0;
    virtual std::string backendLabel() const = 0;

    // 插显示器。成功返回 true；后端 C 会"成功"但仅记录意图并在 note 中说明显示器不会消失
    virtual bool plug(const VirtualDisplaySpec& spec, std::string* err, std::string* note) = 0;
    virtual bool unplug(std::string* err, std::string* note) = 0;

    // 当前是否处于"已插"状态（推流目标存在）
    virtual bool plugged() const = 0;
    // 供 CaptureTarget 按名定位（后端 C 返回系统已有显示名）
    virtual bool current(std::wstring& deviceName) const = 0;
};

std::unique_ptr<IDisplayController> createDisplayController();

}  // namespace apxpc::display
