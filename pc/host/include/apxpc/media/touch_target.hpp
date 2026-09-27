#pragma once

// v184: touch mapping shared state (DDA capture side writes, inject side reads)

#include <atomic>

namespace apxpc::media::touchtarget {

inline std::atomic<int> gx{0}, gy{0}, gw{0}, gh{0};
inline std::atomic<bool> ok{false};

inline void set(int x, int y, int w, int h) {
    gx.store(x); gy.store(y); gw.store(w); gh.store(h);
    ok.store(true);
}

inline bool get(int& x, int& y, int& w, int& h) {
    if (!ok.load()) return false;
    x = gx.load(); y = gy.load(); w = gw.load(); h = gh.load();
    return w > 0 && h > 0;
}

inline void clear() { ok.store(false); }

}  // namespace apxpc::media::touchtarget