#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""五路并行开发回报汇总器（REQ-REPORT-001）。

扫描 reports/L1..L5.json，按规范做 schema 校验与越权检查，
生成 reports/STATUS.json（机读）与 reports/SUMMARY.md（人读），
并在每次汇总前把上一版快照到 reports/history/<时间戳>/。

仅使用 Python 标准库，无第三方依赖。

用法：
    python scripts/aggregate.py [--root .] [--strict] [--no-history]

--strict   存在 INVALID 或未汇报的路时以非零退出码结束（供 CI 使用）
--no-history  不生成历史快照
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

REPORT_SCHEMA_VERSION = "1.0"

LANES: dict[str, dict[str, Any]] = {
    "L1": {
        "name": "协议与公共库",
        "agent": "core-proto",
        "owned": ["shared/", "android/app/src/main/cpp/"],
    },
    "L2": {
        "name": "Android 设备桥与 Gadget",
        "agent": "android-devices",
        "owned": [
            "android/app/src/main/java/com/allperiph/core/",
            "android/app/src/main/java/com/allperiph/sensor/",
            "android/app/src/main/java/com/allperiph/gadget/",
            "android/app/src/main/java/com/allperiph/gps/",
            "android/app/src/main/java/com/allperiph/vibe/",
            "scripts/",
        ],
    },
    "L3": {
        "name": "Android 副屏与 App UI",
        "agent": "android-screen-ui",
        "owned": [
            "android/app/src/main/java/com/allperiph/screen/",
            "android/app/src/main/java/com/allperiph/ui/",
            "android/app/src/main/res/",
            "android/app/src/main/AndroidManifest.xml",
        ],
    },
    "L4": {
        "name": "PC 副屏驱动与推流",
        "agent": "pc-display",
        "owned": ["pc/display/"],
    },
    "L5": {
        "name": "PC 宿主服务与工具",
        "agent": "pc-host",
        "owned": ["pc/host/", "pc/tools/"],
    },
}

# scripts/ 内的归属消歧：ConfigFS 相关脚本归 L2，汇总器归 main
SCRIPTS_RESERVED_FOR_MAIN = {"aggregate.py"}

VALID_STATUS = {"COMPLETED", "PARTIAL", "IN_PROGRESS", "BLOCKED", "FAILED"}
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$")
PATH_RE = re.compile(r"^(?!\.\.)[A-Za-z0-9._/\-]+$")


# ---------------------------------------------------------------- 校验 ----
def _is_under(path: str, prefix: str) -> bool:
    """判断 path 是否位于 prefix 之内（prefix 可为目录或具体文件）。"""
    norm = path.replace("\\", "/")
    if norm == prefix.rstrip("/") or norm == prefix:
        return True
    if prefix.endswith("/"):
        return norm.startswith(prefix)
    return False


def validate_report(data: Any, lane_id: str) -> list[str]:
    """轻量 schema 校验（draft-07 子集），返回错误列表；空列表表示通过。"""
    errs: list[str] = []
    if not isinstance(data, dict):
        return ["回报内容不是 JSON 对象"]

    required = [
        "schemaVersion", "laneId", "laneName", "agent", "generatedAt", "status",
        "scope", "deliverables", "verification", "contractDeviations",
        "risks", "blockers", "nextSteps",
    ]
    for key in required:
        if key not in data:
            errs.append(f"缺少必填字段：{key}")
        elif data[key] is None:
            errs.append(f"必填字段不允许为 null：{key}")

    if data.get("schemaVersion") not in (None, REPORT_SCHEMA_VERSION):
        errs.append(f"schemaVersion 不匹配：期望 {REPORT_SCHEMA_VERSION}，实际 {data.get('schemaVersion')}")

    if data.get("laneId") != lane_id:
        errs.append(f"laneId 与文件名不一致：文件为 {lane_id}，内容为 {data.get('laneId')}")

    status = data.get("status")
    if status is not None and status not in VALID_STATUS:
        errs.append(f"status 非法：{status}")

    ts = data.get("generatedAt")
    if isinstance(ts, str) and not TS_RE.match(ts):
        errs.append(f"generatedAt 格式应为 ISO8601 带时区：{ts}")

    scope = data.get("scope")
    if isinstance(scope, dict):
        for key in ("ownedDirs", "touchedFiles"):
            if key not in scope:
                errs.append(f"scope 缺少 {key}")
        for p in scope.get("touchedFiles", []) or []:
            if not isinstance(p, str) or not PATH_RE.match(p):
                errs.append(f"非法文件路径（禁止绝对路径或 ..）：{p}")
    elif scope is not None:
        errs.append("scope 应为对象")

    if not isinstance(data.get("verification"), list) or len(data.get("verification") or []) < 1:
        errs.append("verification 至少需 1 条记录")

    for idx, item in enumerate(data.get("verification") or []):
        if not isinstance(item, dict):
            errs.append(f"verification[{idx}] 应为对象")
            continue
        for key in ("command", "expected", "actual", "passed"):
            if key not in item:
                errs.append(f"verification[{idx}] 缺少字段 {key}")
        if "passed" in item and not isinstance(item["passed"], bool):
            errs.append(f"verification[{idx}].passed 应为布尔值")

    for name in ("deliverables", "contractDeviations", "risks", "blockers", "nextSteps"):
        value = data.get(name)
        if value is not None and not isinstance(value, list):
            errs.append(f"{name} 应为数组")

    for idx, item in enumerate(data.get("deliverables") or []):
        if not isinstance(item, dict):
            errs.append(f"deliverables[{idx}] 应为对象")
            continue
        if item.get("kind") not in {None, "source", "build", "doc", "script", "test"}:
            errs.append(f"deliverables[{idx}].kind 非法：{item.get('kind')}")
        if item.get("built") not in {None, "yes", "no", "untested"}:
            errs.append(f"deliverables[{idx}].built 非法：{item.get('built')}")

    for idx, item in enumerate(data.get("contractDeviations") or []):
        if isinstance(item, dict) and item.get("impact") not in {None, "none", "minor", "breaking"}:
            errs.append(f"contractDeviations[{idx}].impact 非法：{item.get('impact')}")

    for idx, item in enumerate(data.get("risks") or []):
        if isinstance(item, dict) and item.get("level") not in {None, "high", "medium", "low"}:
            errs.append(f"risks[{idx}].level 非法：{item.get('level')}")

    return errs


def check_violations(data: dict[str, Any], lane_id: str) -> list[str]:
    """越权检查：ownedDirs / touchedFiles / deliverables 必须落在 §2 归属目录内。

    按 REQ §9，每路额外被允许写自己的 reports/<laneId>.json 与 reports/<laneId>.md，
    这两项不计入越权（但会在 SUMMARY 中作为交付提示展示）。
    """
    if not isinstance(data, dict):
        return []

    owned = LANES[lane_id]["owned"]
    allowed = list(owned) + [f"reports/{lane_id}.json", f"reports/{lane_id}.md"]
    allowed_paths = {p.strip("/") for p in allowed}
    problems: list[str] = []

    def allowed_entry(candidate: str) -> bool:
        cand = str(candidate).replace("\\", "/").strip("/")
        if not cand:
            return True
        if cand in allowed_paths:
            return True
        if any(_is_under(cand, a) for a in allowed):
            return True
        # 反向：candidate 本身是某个被允许路径的父目录
        return any(a.strip("/").startswith(cand + "/") for a in allowed)

    for d in (data.get("scope") or {}).get("ownedDirs") or []:
        if not allowed_entry(d):
            problems.append(f"ownedDirs 越权：{d}")

    reserved_main = SCRIPTS_RESERVED_FOR_MAIN
    for f in (data.get("scope") or {}).get("touchedFiles") or []:
        norm = str(f).replace("\\", "/")
        if norm in reserved_main:
            problems.append(f"越权：{norm} 归属 main")
            continue
        if lane_id == "L2" and norm.startswith("scripts/") and Path(norm).name in reserved_main:
            problems.append(f"越权：{norm} 归属 main")
            continue
        if norm.startswith("docs/"):
            problems.append(f"越权：{norm} 归属 main（docs 只读）")
            continue
        if not allowed_entry(norm):
            problems.append(f"touchedFiles 超出归属目录：{norm}")

    for item in data.get("deliverables") or []:
        path = str((item or {}).get("path", "")).replace("\\", "/")
        if not path:
            continue
        if path.startswith("docs/"):
            problems.append(f"deliverables 越权写到 main 区域：{path}")
        elif not allowed_entry(path):
            problems.append(f"deliverables 超出归属目录：{path}")

    return problems


# ---------------------------------------------------------------- 收集 ----
def load_lane(root: Path, lane_id: str) -> dict[str, Any]:
    path = root / "reports" / f"{lane_id}.json"
    info = LANES[lane_id]
    entry: dict[str, Any] = {
        "laneId": lane_id,
        "laneName": info["name"],
        "agent": info["agent"],
        "status": "UNKNOWN",
        "deliverables": 0,
        "verificationTotal": 0,
        "verificationPassed": 0,
        "blockers": 0,
        "deviations": 0,
        "breaking": 0,
        "generatedAt": None,
        "highlight": [],
        "errors": [],
        "violations": [],
    }
    if not path.exists():
        entry["errors"].append("未找到回报文件")
        return entry

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        entry["status"] = "INVALID"
        entry["errors"].append(f"JSON 解析失败：{exc}")
        return entry

    errs = validate_report(data, lane_id)
    entry["errors"] = errs
    entry["status"] = "INVALID" if errs else str(data.get("status", "UNKNOWN")).upper()
    if entry["status"] not in VALID_STATUS | {"INVALID"}:
        entry["status"] = "INVALID"

    entry["violations"] = check_violations(data, lane_id)
    entry["generatedAt"] = data.get("generatedAt")

    entry["deliverables"] = len(data.get("deliverables") or [])
    verifications = data.get("verification") or []
    entry["verificationTotal"] = len(verifications)
    entry["verificationPassed"] = sum(1 for v in verifications if isinstance(v, dict) and v.get("passed") is True)
    entry["blockers"] = len(data.get("blockers") or [])
    entry["deviations"] = len(data.get("contractDeviations") or [])
    entry["breaking"] = sum(
        1 for d in (data.get("contractDeviations") or []) if isinstance(d, dict) and d.get("impact") == "breaking"
    )
    entry["highlight"] = [str(s) for s in (data.get("nextSteps") or [])][:5]
    entry["blockersDetail"] = [
        {
            "desc": str((b or {}).get("desc", "")),
            "neededFrom": str((b or {}).get("neededFrom", "")),
            "owner": str((b or {}).get("owner", "")),
        }
        for b in (data.get("blockers") or [])
    ]
    entry["deviationsDetail"] = [
        {
            "target": str((d or {}).get("target", "")),
            "desc": str((d or {}).get("desc", "")),
            "proposedChange": str((d or {}).get("proposedChange", "")),
            "impact": str((d or {}).get("impact", "")),
        }
        for d in (data.get("contractDeviations") or [])
    ]
    entry["failedVerification"] = [
        {"command": str((v or {}).get("command", "")), "actual": str((v or {}).get("actual", ""))}
        for v in verifications
        if isinstance(v, dict) and v.get("passed") is not True
    ]
    entry["metrics"] = data.get("metrics") or {}
    return entry


# ------------------------------------------------------------ 里程碑判定 ----
def evaluate_milestones(lanes: dict[str, dict[str, Any]]) -> list[dict[str, str]]:
    """依据 REPORT 需求 §6.7 给出 MS1–MS6 达成结论（保守判定：未验证不算通过）。"""
    def st(lane: str) -> str:
        return lanes[lane]["status"]

    def verified_pass(lane: str) -> bool:
        return lanes[lane]["verificationTotal"] > 0 and lanes[lane]["verificationPassed"] == lanes[lane]["verificationTotal"]

    results: list[dict[str, str]] = []

    ms1_ready = (
        st("L1") == "COMPLETED" and verified_pass("L1")
        and st("L2") in {"COMPLETED", "PARTIAL"} and lanes["L2"]["deliverables"] > 0
    )
    results.append({
        "id": "MS1",
        "name": "全链路 Hello World（加速度计 → HID → 传感器面板）",
        "verdict": "可进入真机验证" if ms1_ready else "未达成",
        "reason": "依赖 L1 协议与 L2 传感器/Gadget；需 L1 已验证通过且 L2 有交付物",
    })

    ms2_ready = st("L2") == "COMPLETED" and verified_pass("L2")
    results.append({
        "id": "MS2",
        "name": "传感器全家桶 + 电池 + 按键",
        "verdict": "可进入真机验证" if ms2_ready else "未达成",
        "reason": "依赖 L2 全部完成且验证全通过",
    })

    ms3_ready = st("L2") == "COMPLETED" and lanes["L2"]["deliverables"] > 0
    results.append({
        "id": "MS3",
        "name": "GPS over CDC ACM → Location API / gpsd",
        "verdict": "待验证" if ms3_ready else "未达成",
        "reason": "依赖 L2 的 GPS 模块（PC 端 OS 原生识别，无额外依赖）",
    })

    ms4_ready = st("L3") in {"COMPLETED", "PARTIAL"} and st("L4") in {"COMPLETED", "PARTIAL"}
    results.append({
        "id": "MS4",
        "name": "副屏闭环（虚拟显示器 + 推流 + 触控上行）",
        "verdict": "待集成" if ms4_ready else "未达成",
        "reason": "依赖 L3 渲染/触控与 L4 驱动/推流两端同时可用",
    })

    ms5_ready = st("L2") == "COMPLETED"
    results.append({
        "id": "MS5",
        "name": "相机 / 音频（优先复用 Android 14+ DeviceAsWebcam）",
        "verdict": "待评估" if ms5_ready else "未达成",
        "reason": "依赖厂商内核是否启用 UVC / UAC2",
    })

    ms6_ready = all(st(l) == "COMPLETED" for l in LANES)
    results.append({
        "id": "MS6",
        "name": "产品化（控制面板、自启、自愈、SDK）",
        "verdict": "可启动" if ms6_ready else "未达成",
        "reason": "依赖五路全部完成",
    })
    return results


# ---------------------------------------------------------------- 输出 ----
def check_protocol_duplication(root: Path) -> list[dict[str, str]]:
    """检测 shared/ 之外的 apx/ 头文件副本。

    架构前提是 shared/ 为协议唯一真源；任何 lane 私存一份副本都会导致两端
    二进制布局漂移，且漂移在真机联调前不可见，因此这里做强制拦截。
    """
    canonical_dir = root / "shared" / "include" / "apx"
    canonical = {p.name for p in canonical_dir.glob("*.h")} if canonical_dir.is_dir() else set()
    issues: list[dict[str, str]] = []

    for path in root.rglob("apx/*.h"):
        rel = path.relative_to(root).as_posix()
        if rel.startswith("shared/include/apx/"):
            continue
        if path.name not in canonical:
            continue
        duplicate = "内容与真源不一致（已发生布局漂移）"
        try:
            if (canonical_dir / path.name).read_bytes() == path.read_bytes():
                duplicate = "内容与真源当前一致（仍应删除，改为引用 shared/）"
        except OSError:
            pass
        issues.append({
            "laneId": "GLOBAL",
            "desc": f"协议双源：{rel} 是 shared/include/apx/{path.name} 的副本 —— {duplicate}",
        })

    return issues


def build_summary_md(status: dict[str, Any], root: Path) -> str:
    lines: list[str] = []
    lines.append("# 五路并行开发汇报汇总")
    lines.append("")
    lines.append(f"- 生成时间：{status['generatedAt']}")
    lines.append("- 规范依据：`docs/REQ-五路回报.md`（REQ-REPORT-001）")
    lines.append("")

    lines.append("## 状态矩阵")
    lines.append("")
    lines.append("| laneId | 工作线 | agent | 状态 | 交付数 | 验证通过 | 阻塞 | 协议偏离(breaking) | 越权 |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    for lane_id, lane in status["lanes"].items():
        lines.append(
            f"| {lane_id} | {lane['laneName']} | {lane['agent']} | **{lane['status']}** "
            f"| {lane['deliverables']} | {lane['verificationPassed']}/{lane['verificationTotal']} "
            f"| {lane['blockers']} | {lane['deviations']}({lane['breaking']}) | {len(lane['violations'])} |"
        )
    lines.append("")

    lines.append("## 缺口清单")
    lines.append("")
    gaps = status["openIssues"]
    if gaps:
        for gap in gaps:
            lines.append(f"- **[{gap['laneId']}] {gap['type']}**：{gap['desc']}" + (f"（需 {gap['neededFrom']} 提供）" if gap.get("neededFrom") else ""))
    else:
        lines.append("- 无")
    lines.append("")

    lines.append("## 合规问题")
    lines.append("")
    compliance = status["compliance"]
    if compliance:
        for item in compliance:
            lines.append(f"- **[{item['laneId']}]** {item['desc']}")
    else:
        lines.append("- 无")
    lines.append("")

    lines.append("## 里程碑判定")
    lines.append("")
    lines.append("| 里程碑 | 内容 | 结论 | 依据 |")
    lines.append("|---|---|---|---|")
    for ms in status["verdict"]["milestones"]:
        lines.append(f"| {ms['id']} | {ms['name']} | {ms['verdict']} | {ms['reason']} |")
    lines.append("")

    lines.append("## 下一步建议")
    lines.append("")
    for step in status["verdict"]["nextActions"]:
        lines.append(f"- {step}")
    lines.append("")
    lines += protocol_change_trail(root)
    return "\n".join(lines)


def protocol_change_trail(root: Path) -> list[str]:
    """§10.4：SUMMARY 必须留痕 docs/PROTOCOL.md 的版本状态与变更记录位置。

    每次协议修订的「原因 + 影响范围 + 受影响 lane」由 main 记录在
    reports/MAIN-INTERVENTIONS.md 与 PROTOCOL.md §5 变更记录表，本节只做指针，
    避免汇总器复述维护两份漂移源。
    """
    lines = ["---", "", "## 协议变更留痕（main 范围，§10.4）", ""]
    proto = root / "docs" / "PROTOCOL.md"
    if proto.exists():
        text = proto.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"协议版本字段\s*`u16`，当前\s*\*\*(\d+\.\d+)\*\*", text)
        if m:
            lines.append(f"- 当前协议版本 **{m.group(1)}**（`docs/PROTOCOL.md` §5）")
        else:
            lines.append("- ⚠️ 未能从 `docs/PROTOCOL.md` §5 解析出「当前版本」字段，请 main 检查")
    lines.append(
        "- 每次协议修订的变更原因、影响范围与受影响 lane 记录于 "
        "`docs/PROTOCOL.md` §5 变更记录表与 `reports/MAIN-INTERVENTIONS.md`，本汇总不复述"
    )
    lines.append("")
    return lines


def decide_next_actions(lanes: dict[str, dict[str, Any]]) -> list[str]:
    actions: list[str] = []
    unknown = [k for k, v in lanes.items() if v["status"] == "UNKNOWN"]
    invalid = [k for k, v in lanes.items() if v["status"] == "INVALID"]
    blocked = [k for k, v in lanes.items() if v["status"] in {"BLOCKED", "FAILED"}]

    if invalid:
        actions.append(f"要求 {', '.join(invalid)} 按 §5 schema 重新生成回报（禁止用自由文本替代）")
    if unknown:
        actions.append(f"向 {', '.join(unknown)} 点名索要回报；超时仍无响应则标注「需人工介入」")
    if blocked:
        actions.append(f"优先解除 {', '.join(blocked)} 的阻塞项，按 L1→L5 顺序处理依赖")

    unresolved_gap = [k for k, v in lanes.items() if v["verificationTotal"] and v["verificationPassed"] < v["verificationTotal"]]
    if unresolved_gap:
        actions.append(f"补齐未验证项后才能提升里程碑判定：{', '.join(unresolved_gap)}")

    breaking = [k for k, v in lanes.items() if v["breaking"] > 0]
    if breaking:
        actions.append(f"由 main 裁决 breaking 级协议偏离并递增 PROTOCOL.md 版本号：{', '.join(breaking)}")
    if not actions:
        actions.append("五路齐备且通过校验，安排 MS1 真机验证")
    return actions


def main() -> int:
    parser = argparse.ArgumentParser(description="五路回报汇总器")
    parser.add_argument("--root", default=".", help="项目根目录，默认当前目录")
    parser.add_argument("--strict", action="store_true", help="存在 INVALID/UNKNOWN 时以非零码退出")
    parser.add_argument("--no-history", action="store_true", help="不生成历史快照")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    reports = root / "reports"
    reports.mkdir(parents=True, exist_ok=True)

    # 汇总前快照上一版
    if not args.no_history:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        snapshot_dir = reports / "history" / stamp
        snapshot_dir.mkdir(parents=True, exist_ok=True)
        for name in ("STATUS.json", "SUMMARY.md"):
            src = reports / name
            if src.exists():
                shutil.copy2(src, snapshot_dir / name)

    lanes = {lane_id: load_lane(root, lane_id) for lane_id in LANES}

    open_issues: list[dict[str, Any]] = []
    compliance: list[dict[str, Any]] = []
    for lane_id, lane in lanes.items():
        for block in lane.get("blockersDetail", []):
            open_issues.append({**block, "laneId": lane_id, "type": "BLOCKER"})
        for dev in lane.get("deviationsDetail", []):
            open_issues.append({**dev, "laneId": lane_id, "type": "DEVIATION"})
        for fail in lane.get("failedVerification", []):
            open_issues.append({**fail, "laneId": lane_id, "type": "UNVERIFIED", "desc": fail.get("actual") or fail.get("command", "")})
        for err in lane["errors"]:
            open_issues.append({"laneId": lane_id, "type": "REPORT_ERROR", "desc": err})
        for vol in lane["violations"]:
            compliance.append({"laneId": lane_id, "desc": vol})

    compliance.extend(check_protocol_duplication(root))

    milestones = evaluate_milestones(lanes)
    status: dict[str, Any] = {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "generatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "lanes": lanes,
        "openIssues": open_issues,
        "compliance": compliance,
        "verdict": {
            "milestones": milestones,
            "nextActions": decide_next_actions(lanes),
        },
    }

    (reports / "STATUS.json").write_text(
        json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (reports / "SUMMARY.md").write_text(build_summary_md(status, root) + "\n", encoding="utf-8")

    # 控制台一屏摘要
    print("== 五路回报汇总 ==")
    for lane_id, lane in lanes.items():
        print(
            f"  {lane_id} {lane['laneName']}: {lane['status']}"
            f" | 交付 {lane['deliverables']}"
            f" | 验证 {lane['verificationPassed']}/{lane['verificationTotal']}"
            f" | 阻塞 {lane['blockers']}"
            + (f" | 越权 {len(lane['violations'])}" if lane["violations"] else "")
        )
    print("-- 下一步 --")
    for action in status["verdict"]["nextActions"]:
        print(f"  - {action}")
    print(f"产出：reports/STATUS.json, reports/SUMMARY.md")

    if args.strict:
        bad = [k for k, v in lanes.items() if v["status"] in {"UNKNOWN", "INVALID"}]
        if bad:
            print(f"[strict] 存在未完成/无效回报：{', '.join(bad)}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
