// 动态库加载封装（Windows LoadLibrary / POSIX dlopen）
// 厂商 SDK 一律走这里，进程启动时绝不因缺 DLL 而失败。
#pragma once

#include <string>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace apxdisp {

class DynLib {
public:
    DynLib() = default;
    explicit DynLib(const char* name) { load(name); }

    ~DynLib() { unload(); }

    DynLib(const DynLib&) = delete;
    DynLib& operator=(const DynLib&) = delete;

    bool load(const char* name) {
        unload();
        if (!name || !*name) return false;
#ifdef _WIN32
        handle_ = reinterpret_cast<void*>(LoadLibraryA(name));
#else
        handle_ = dlopen(name, RTLD_NOW | RTLD_LOCAL);
#endif
        return handle_ != nullptr;
    }

    void unload() {
        if (!handle_) return;
#ifdef _WIN32
        FreeLibrary(reinterpret_cast<HMODULE>(handle_));
#else
        dlclose(handle_);
#endif
        handle_ = nullptr;
    }

    bool loaded() const { return handle_ != nullptr; }

    template <typename Fn>
    Fn symbol(const char* name) const {
        if (!handle_) return nullptr;
#ifdef _WIN32
        return reinterpret_cast<Fn>(GetProcAddress(reinterpret_cast<HMODULE>(handle_), name));
#else
        return reinterpret_cast<Fn>(dlsym(handle_, name));
#endif
    }

    static std::string lastErrorText() {
#ifdef _WIN32
        const DWORD e = GetLastError();
        char buf[64];
        snprintf(buf, sizeof(buf), "GetLastError=%lu", static_cast<unsigned long>(e));
        return std::string(buf);
#else
        const char* e = dlerror();
        return e ? std::string(e) : std::string("unknown");
#endif
    }

private:
    void* handle_ = nullptr;
};

}  // namespace apxdisp
