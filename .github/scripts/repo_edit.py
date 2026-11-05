#!/usr/bin/env python3
"""repo-edit：批 M7-c cam-fix1（Build #1235 两处编译错 + 一处宏警告，2026-11-06）。

#1235 pc job 原文（用户贴回的完整日志）：
  · L83  error C2665: Gdiplus::Bitmap(IStream*, nullptr) —— 第二参是 BOOL，
         nullptr 不能隐转（C2383）→ 改传 FALSE；
  · L362 error C2660: IMFVirtualCamera::Start 不收 0 参 —— 官方签名
         HRESULT Start(IMFAsyncCallback* pCallback)（参数可空，learn.microsoft.com 实锤）
         → 改 Start(nullptr)；
  · L18  warning C4005: WIN32_LEAN_AND_MEAN 重定义（构建命令行已带）→ #ifndef 守卫。
两阶段：①按期望次数全量校验；②全过才写盘；已修则幂等退出。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


PATH = "pc/host/src/media/camera_view.cpp"

EDITS = [
    (T('    Gdiplus::Bitmap src(stm, nullptr);'),
     T('    Gdiplus::Bitmap src(stm, FALSE);  // BOOL 形参：nullptr 不能隐转（MSVC C2665）'), 1),
    (T('        hr = cam->Start(); // 真正创建并注册到系统'),
     T('        hr = cam->Start(nullptr); // 真正创建并注册到系统（签名 Start(IMFAsyncCallback* 可空)）'), 1),
    (T('#define WIN32_LEAN_AND_MEAN', '#include <windows.h>'),
     T('#ifndef WIN32_LEAN_AND_MEAN',
       '#define WIN32_LEAN_AND_MEAN',
       '#endif',
       '#include <windows.h>'), 1),
]


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    if "cam->Start(nullptr)" in text:
        print("already fixed, nothing to do")
        return 0
    errors = []
    for i, (old, _new, expected) in enumerate(EDITS, 1):
        n = text.count(old)
        if n != expected:
            errors.append(f"edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for old, new, _e in EDITS:
        text = text.replace(old, new)
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"OK {PATH}: 3 edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
