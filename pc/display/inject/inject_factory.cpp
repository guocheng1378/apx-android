// 注入器工厂：档 3（需驱动，未安装则跳过）→ 档 2 → 档 1
#include "inject/i_inject.hpp"

#include "common/log.hpp"

namespace apxdisp {

#ifdef _WIN32
std::unique_ptr<IInjector> createSendInputInjector();
std::unique_ptr<IInjector> createSyntheticPointerInjector();
#else
std::unique_ptr<IInjector> createSendInputInjector() { return nullptr; }
std::unique_ptr<IInjector> createSyntheticPointerInjector() { return nullptr; }
#endif

std::unique_ptr<IInjector> createInjector(InjectTier prefer) {
    auto tryCreate = [](InjectTier t) -> std::unique_ptr<IInjector> {
        switch (t) {
            case InjectTier::SyntheticPointer: return createSyntheticPointerInjector();
            case InjectTier::SendInput:       return createSendInputInjector();
            case InjectTier::KmdfHid:
                // 档 3 为 KMDF HID minidriver：驱动未安装时无用户态对象可创建，
                // 由 pc/display/inject/hid/README.md 描述安装后如何切换到该档。
                APX_LOG_W("档3（KMDF HID minidriver）需先安装并签名驱动，本轮仅提供设计与骨架");
                return nullptr;
            default: return nullptr;
        }
    };

    if (prefer != InjectTier::Auto) {
        auto inj = tryCreate(prefer);
        if (!inj) APX_LOG_W("指定档位不可用，回退自动选择");
        return inj;
    }

    // 自动：档 2 可用（uiAccess + 签名）就用档 2，否则档 1（免驱，必须可用）
    if (auto tier2 = tryCreate(InjectTier::SyntheticPointer)) {
        InjectCaps c = tier2->caps();
        // 真正可用与否要在 init() 时才知道（CreateSyntheticPointerDevice 需要权限），
        // 这里先返回，由调用方 init 失败后显式降级。
        (void)c;
        return tier2;
    }
    auto tier1 = tryCreate(InjectTier::SendInput);
    if (!tier1) APX_LOG_E("无可用注入器（档1 应始终可用，请检查平台）");
    return tier1;
}

bool injectorSupportsMouse(InjectTier prefer) {
    auto inj = createInjector(prefer);
    return inj && inj->caps().mouse;
}

}  // namespace apxdisp
