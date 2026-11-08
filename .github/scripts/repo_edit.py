#!/usr/bin/env python3
"""repo-edit：修「open-file dummy 超时」—— 系统文件对话框被 bridge 10s 硬超时误杀。

根因（实测报错 + 日志 14:59:53 open-file 后无 send 坐实）：
  bridge.request() 对所有请求统一 10s 超时；openFile 是用户交互（找文件多久都正常），
  超 10s 前端 reject「open-file dummy 超时」→ pending 删除 → 宿主稍后回的选中路径被
  丢弃 → sendTo 不执行，发送根本没开始。

修法：request 加可选 timeoutMs（默认仍 10s；0=不启用），openFile 传 600_000（10 分钟）。
锚点唯一命中；同文件两条**累积替换**（run #97 覆盖 bug 已修）；幂等。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

EDITS = [
    {
        "id": "E6 request 支持自定义超时",
        "file": "pc/web/src/api/bridge.ts",
        "kind": "replace",
        "anchor": """  private request(kind: Kind, name: string, payload?: Record<string, unknown>): Promise<any> {
    const id = `req-${Date.now()}-${++this.seq}`;
    return new Promise((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${kind} ${name} 超时`));
      }, 10_000);""",
        "new": """  /** timeoutMs 默认 10s；传 0 = 不启用超时。openFile 单独放宽 —— 系统文件对话框
   *  是用户交互，找文件超 10 秒就被前端判死（报错「open-file dummy 超时」），
   *  宿主稍后回的选中路径因 pending 已删被丢弃，发送根本不会启动。 */
  private request(kind: Kind, name: string, payload?: Record<string, unknown>, timeoutMs = 10_000): Promise<any> {
    const id = `req-${Date.now()}-${++this.seq}`;
    return new Promise((resolve, reject) => {
      const timeout = timeoutMs > 0 ? window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${kind} ${name} 超时`));
      }, timeoutMs) : 0;""",
    },
    {
        "id": "E7 openFile 10 分钟",
        "file": "pc/web/src/api/bridge.ts",
        "kind": "replace",
        "anchor": """  openFile(filter: string): Promise<any> {
    return this.request('open-file', 'dummy', { filter });
  }""",
        "new": """  openFile(filter: string): Promise<any> {
    // 给 10 分钟：慢慢找文件不该被判死（默认 10s 是实测「open-file dummy 超时」根因）
    return this.request('open-file', 'dummy', { filter }, 600_000);
  }""",
    },
]


def main() -> int:
    errors = []
    pending = {}
    for e in EDITS:
        path = e["file"]
        base = pending.get(path)
        if base is None:
            try:
                with open(path, encoding="utf-8") as f:
                    base = f.read()
            except OSError as ex:
                errors.append("open failed " + path + ": " + str(ex))
                continue
        if e["new"] in base:
            print("already applied, skip: " + e["id"])
            continue
        n = base.count(e["anchor"])
        if n != 1:
            errors.append(
                e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                + "\n---anchor---\n" + e["anchor"][:200]
            )
            continue
        # 累积替换：同文件后一条基于前一条的结果继续改
        pending[path] = base.replace(e["anchor"], e["new"], 1)

    if errors:
        body = "\n\n".join(errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as ex:
            print("write errlog failed: " + str(ex))
        print("ERRORS:\n" + body)
        return 1

    try:
        os.remove(ERRLOG)
    except OSError:
        pass

    if not pending:
        print("no changes")
        return 0

    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            changed.append(path)
        except OSError as ex:
            print("write failed " + path + ": " + str(ex))
            return 1

    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
