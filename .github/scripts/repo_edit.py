#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

本批（批 N-2 · 发版 CHANGELOG）：
  docs/CHANGELOG.md：在 v223.29.9 段之前插入 v223.29.10 段
  （悬浮球点通知不再直接关球）。

铁律：锚以双引号收尾先换行。两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys

EDITS = [
    ("docs/CHANGELOG.md", [
        ("## v223.29.9（2026-11-05）\n",
         """## v223.29.10（2026-11-05）

悬浮球（反馈#7 追修）：点通知栏里的悬浮球通知会把球直接关掉 —— 关闭动作不该
藏在通知点击里。本版点通知改为打开主界面，关闭只保留长按球 / 扇形菜单退出 /
设置页开关三处。

### 悬浮球点通知不再直接关球

- 旧版 `FloatingBallService.onCreate()` 的前台通知 `setContentIntent` 发的是
 `Intent(this, FloatingBallService::class.java).apply { action = ACTION_STOP }`
 配 `PendingIntent.getService` —— 通知栏上看着是「打开」，一点却执行了关闭，
 用户随手一点球就没了。
- 改为 `PendingIntent.getActivity` 拉起 `ApShellActivity`（`FLAG_ACTIVITY_NEW_TASK`），
 点通知 = 进主界面，与通知栏的常规预期一致。
- 关闭路径不变：长按球（`LONG_PRESS_MS = 800L`）/ 扇形菜单退出 / 设置页开关。
 `ACTION_STOP` 仍由这三处的派发链路使用，只是不再挂在通知点击上。
- 实现走 repo-edit 云端锚点补丁（批 N-1），锚点唯一命中才写盘，未动文件其余部分。

## v223.29.9（2026-11-05）
""", 1),
    ]),
]

def main() -> int:
    loaded = {}
    errors = []
    for path, entries in EDITS:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as e:
            errors.append(f"{path}: open failed: {e}")
            continue
        loaded[path] = (text, entries)
        for i, (old, _new, expected) in enumerate(entries, 1):
            n = text.count(old)
            if n != expected:
                errors.append(
                    f"{path} edit#{i}: anchor count={n} (expect {expected})\n---anchor---\n{old[:400]}\n---"
                )
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for path, (text, entries) in loaded.items():
        for old, new, _expected in entries:
            text = text.replace(old, new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"OK {path}: {len(entries)} edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
