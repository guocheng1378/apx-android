#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 ball-mac-2（修 macOS Build #584：ball_mac.mm 首次编译的 ObjC 作用域错误，2026-11-06）：
  · pc/host/src/ball/ball_mac.mm —— 3 组编辑：
    ① 匿名层闭合后关 ball/apxpc + 全局 using（@interface/@implementation 只允许在全局作用域）；
    ② 实现段 @end 后重开 apxpc/ball（createBall/destroyBall/toggleBallFan 必须回 apxpc::ball，
       否则符号与 ball_win32.hpp 声明错位、desktop_main 链接再次缺符号）；
    ③ 显式 #include <algorithm>（tick 内 std::min/max，原靠传递包含兜底）。
根因：M4-b 的 ball_mac.mm 从未进过构建（CMake 未挂），补挂后首编暴露
`Objective-C declarations may only appear in global scope` 7 连错。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import os
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


PATH = "pc/host/src/ball/ball_mac.mm"

EDITS = [
    # ① 命名空间出口 + 全局 using（锚点：匿名层闭合 + 空行 + @interface，全文件唯一）
    (T('}  // namespace', '', '@interface BallView : NSView'),
     T('}  // namespace',
       '',
       '}  // namespace ball',
       '}  // namespace apxpc',
       '',
       '// clang：@interface/@implementation 只允许出现在全局作用域（M4-b 从未编译，补挂后首编暴露）。',
       '// 实现正文的 C++ 引用（g/hitItem/k*/fx::/registry…）全在 apxpc::ball，经此 using 全局可见——正文零改动。',
       'using namespace apxpc::ball;',
       '',
       '@interface BallView : NSView'), 1),
    # ② 实现段后重开命名空间（createBall/destroyBall/toggleBallFan 必须回 apxpc::ball）
    (T('@end', '', 'bool createBall() {'),
     T('@end',
       '',
       'namespace apxpc {',
       'namespace ball {',
       '',
       'bool createBall() {'), 1),
    # ③ std::min/max 显式头
    (T('#include <cmath>'),
     T('#include <cmath>',
       '#include <algorithm>  // std::min/max（tick 内；防传递包含变化踩空）'), 1),
]


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    if "using namespace apxpc::ball;" in text and "namespace ball {\n\nbool createBall" in text:
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
