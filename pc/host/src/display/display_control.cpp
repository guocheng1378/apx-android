#include "apxpc/display/display_control.hpp"
#include "apxpc/log.hpp"

namespace apxpc::display {

// 后端 C（降级）：不依赖任何第三方驱动，仅维护"已插/已拔"意图状态。
// 如实标注：关闭副屏时只停推流，虚拟显示器不会从系统消失。
class BackendCController : public IDisplayController {
public:
    bool available() const override { return false; }
    Backend backend() const override { return Backend::C; }
    std::string backendLabel() const override { return "C · 无插拔能力（仅停推流）"; }

    bool plug(const VirtualDisplaySpec& spec, std::string* err, std::string* note) override {
        spec_ = spec;
        plugged_ = true;
        APX_LOGI("后端C：记录副屏插入意图 {}x{}@{}Hz（不开真实虚拟显示器，仅推流）",
                 spec.width, spec.height, spec.refreshHz);
        if (note) *note = "当前未检测到支持运行时插拔的虚拟显示器驱动，开屏只会启动推流；"
                          "关闭后显示器不会从系统消失（后端 C 降级）。";
        return true;
    }
    bool unplug(std::string* err, std::string* note) override {
        plugged_ = false;
        APX_LOGI("后端C：记录副屏拔除意图（停止推流，系统显示器仍可能存在）");
        if (note) *note = "仅停止推流；如系统里仍有该显示器，请在 Windows 显示设置中手动断开。";
        return true;
    }
    bool plugged() const override { return plugged_; }
    bool current(std::wstring& deviceName) const override {
        // 后端 C 无自有虚拟显示器，交由系统已有主显示器承接
        deviceName = L"";
        return false;
    }

private:
    VirtualDisplaySpec spec_{};
    bool plugged_ = false;
};

std::unique_ptr<IDisplayController> createDisplayController() {
    return std::make_unique<BackendCController>();
}

}  // namespace apxpc::display
