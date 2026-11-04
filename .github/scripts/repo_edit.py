#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

本批（批 N-1 · 通知点击修复，2026-11-05）：
  FloatingBallService.kt（~75KB，无法整文件回传）：
    - import 补 ApShellActivity
    - onCreate() 前台通知 setContentIntent：PendingIntent.getService(ACTION_STOP)
      → PendingIntent.getActivity(ApShellActivity)，点通知 = 打开主界面，
      不再直接关球；关闭路径保留（长按球 / 扇形菜单退出 / 设置页开关）。

上一批（批 S-1 · IME 输入法核心对）未执行 —— 其锚点#5 的注释文案与当前
master 已漂移（锚点校验必失败），补丁原文保留在 git 历史（本文件上一版，
blob a1d8087f5bf7910b467bd5fc2fe68991dec71187），对齐后可恢复启用。

铁律：锚以双引号收尾先换行。两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys

EDITS = [
    ("android/app/src/main/java/com/allperiph/ui/FloatingBallService.kt", [
        # 1. import 补 ApShellActivity（锚带上下相邻 import，全文件唯一）
        ("import com.allperiph.ui.compose.ApTouchpadSurface\nimport com.allperiph.ui.miuix.ApComponents\n",
         "import com.allperiph.ui.compose.ApTouchpadSurface\nimport com.allperiph.ui.compose.ApShellActivity\nimport com.allperiph.ui.miuix.ApComponents\n", 1),
        # 2. 通知点击：关球 → 打开主界面（锚 = setContentIntent 整块，唯一）
        ("""                .setContentIntent(
                    PendingIntent.getService(
                        this, 0,
                        Intent(this, FloatingBallService::class.java).apply { action = ACTION_STOP },
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
""",
         """                .setContentIntent(
                    // 点通知 = 打开主界面（旧版发 ACTION_STOP，点一下球直接关了；
                    // 关闭路径：长按球 / 扇形菜单退出 / 设置页开关，不该藏在通知点击里）
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, ApShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
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
