# 需求规格：五路并行开发统一回报与汇总（REQ-REPORT-001）

> ⚠️ **历史文档**：本文记录的是当初**五路并行开发**的分工与回报格式，其中的
> **L3（Android 副屏）/ L4（PC 副屏驱动）两路已随副屏终止**（代码在
> `android/app/src/disabled/screen/` 与 `pc/display/`，均不参与编译），
> 目录里的 `sensor/ gps/ vibe/` 也从未落地。当前能力现状请看
> [`ROADMAP.md`](./ROADMAP.md)。

> 原意：五条并行开发工作线各自完成开发后，按**统一格式**回报结果，由主控（main）**一并汇总**并给出结论。
> 本文档在不改变该原意的前提下，补充目标系统、五路边界、回报字段、触发条件、汇总方式、输出目标、异常处理与审计约束，可直接用于代码实现。

---

## 1. 目标系统与目标模块

| 项 | 内容 |
|---|---|
| 系统 | 全能外设（allperiph）：Android 手机硬件元器件经 USB 3.0 虚拟化为 PC 本机设备 |
| 本需求作用的模块 | **回报与汇总子系统**（新增，非业务模块） |
| 主要角色 | 5 个并行开发线（Lane，由子 agent 承担）、主控 main（汇总与裁决）、最终用户（查看结论） |
| 代码落点 | 回报产物：`reports/`；汇总逻辑与校验脚本：`scripts/aggregate.py`（或等价实现）；状态机读接口：`reports/STATUS.json` |
| 关联文档 | `docs/ARCHITECTURE.md`、`docs/PROTOCOL.md`（均为只读真源，本需求不改变其内容） |

**目标**：把"口头/自由文本回报"变成**结构化、可校验、可汇总、可留存**的机器可读回报，使主控无需逐一追问即可判定里程碑是否达成。

---

## 2. "五路"的定义（回报通道与职责边界）

五路 = 五条并行开发工作线，每路是一个**独立回报源**，有固定的归属目录与固定 `laneId`。

| laneId | 名称 | 负责 agent | 归属目录（只写这些） | 主要输入 | 预期产出 |
|---|---|---|---|---|---|
| `L1` | 协议与公共库 | core-proto | `shared/`、`android/app/src/main/cpp/` | `docs/PROTOCOL.md` | C++17 协议库、HID 描述符生成、JNI 桥、自测程序 |
| `L2` | Android 设备桥与 Gadget | android-devices | `android/app/src/main/java/com/allperiph/{core,sensor,gadget,gps,vibe}/`、`scripts/` | `docs/PROTOCOL.md`、`shared/` 接口 | 传感器引擎、ConfigFS 管理器、GPS→ACM、振动/闪光/红外 |
| `L3` | Android 副屏与 App UI | android-screen-ui | `android/.../{screen,ui}/`、`android/app/src/main/res/`、`AndroidManifest.xml` | `docs/PROTOCOL.md` §2.5/§3.2、`shared/` 接口 | 副屏解码渲染、触控上行、UI 与编排 |
| `L4` | PC 副屏驱动与推流 | pc-display | `pc/display/` | `docs/ARCHITECTURE.md` §5、`shared/` | IddCx 虚拟显示器、DDA 抓屏、硬编、USB 推流、触控注入 |
| `L5` | PC 宿主服务与工具 | pc-host | `pc/host/`、`pc/tools/` | `docs/PROTOCOL.md` §4、`shared/` 接口 | 设备发现、控制面板、SDK、验证工具 |

**目录消歧**：`scripts/` 内 ConfigFS 挂载/卸载等脚本归 L2；`scripts/aggregate.py`（汇总器）归 main，其余路不得修改。

**跨路依赖**：L2/L3/L4/L5 均依赖 L1 的 `shared/` 接口。L1 未交付不阻塞其它路开工——其它路按 `PROTOCOL.md` 假设接口存在，缺失项记入 `contractDeviations`，由 main 裁决。

---

## 3. 触发条件（何时必须回报）

每路必须在下列**任一条件满足时**立即产出/更新回报：

1. **完成**：本路交付物全部落地并自测通过 → `status = COMPLETED`
2. **部分完成**：主体完成但有已知缺口/未验证项 → `status = PARTIAL`（可多次回报，后一次覆盖前一次）
3. **阻塞**：依赖缺失或权限不足导致无法继续 → `status = BLOCKED`，必须填写 `blockers`
4. **失败**：构建/验证无法通过且短期无法修复 → `status = FAILED`
5. **心跳**：开工后每完成一个子模块（如 L2 完成 sensor、完成 gadget）可发一次增量回报，`status` 用 `IN_PROGRESS`
6. **主控点名**：main 主动 `send_message` 索要状态 → 30 分钟内回报

**汇总触发**：main 在① 五路均回报且无 `IN_PROGRESS`/`BLOCKED`；② 或到达约定截止时间；③ 或用户主动要求时，执行汇总。任一条件满足即汇总，**不因单路未回报而无限等待**。

---

## 4. 回报内容（字段定义）

每路产出**两个文件**（人读 + 机读各一份），路径固定：

```
reports/<laneId>.json     ← 机读，必须符合 §5 schema
reports/<laneId>.md       ← 人读摘要，内容与 json 一致，供快速浏览
```

字段说明（json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | ✅ | 固定 `"1.0"` |
| `laneId` | string | ✅ | `L1`..`L5` |
| `laneName` | string | ✅ | 见 §2 |
| `agent` | string | ✅ | 子 agent 名，如 `core-proto` |
| `generatedAt` | string | ✅ | ISO8601 本地时间，如 `2026-09-20T15:04:05+08:00` |
| `status` | enum | ✅ | `COMPLETED` / `PARTIAL` / `IN_PROGRESS` / `BLOCKED` / `FAILED` |
| `scope.ownedDirs` | string[] | ✅ | 实际写入的目录，必须是 §2 归属目录的子集；越权即判违规 |
| `scope.touchedFiles` | string[] | ✅ | 新建或修改的文件相对路径列表 |
| `deliverables[]` | object[] | ✅ | 每项：`path`、`kind`（`source`/`build`/`doc`/`script`/`test`）、`built`（`yes`/`no`/`untested`）、`notes` |
| `verification[]` | object[] | ✅ | 每项：`command`、`expected`、`actual`、`passed`(bool)；无法执行的写 `passed:false` 并说明原因 |
| `contractDeviations[]` | object[] | ✅（可空数组） | 对 `PROTOCOL.md`/`ARCHITECTURE.md` 的偏离或**缺失接口请求**：`target`、`desc`、`proposedChange`、`impact` |
| `risks[]` | object[] | ✅ | `level`（`high`/`medium`/`low`）、`desc`、`mitigation` |
| `blockers[]` | object[] | ✅ | `desc`、`neededFrom`（如 `L1`）、`owner`；非阻塞状态填空数组 |
| `metrics` | object | ➖ | 本路关键量化指标，如 L4 的延迟预算分解、L1 的自测通过率 |
| `nextSteps[]` | string[] | ✅ | 建议的后续动作（可为空数组表示无） |

**硬性要求**：
- 不得省略必填字段，无内容用空数组 `[]` 或空字符串，禁止 `null`
- `deliverables[].path` 必须是工作区内的相对路径，禁止绝对路径与 `..`
- 每份回报至少包含**一条可复现的验证命令**（编译、运行或 adb 命令均可）

---

## 5. 格式（JSON Schema，可直接用于校验）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "LaneReport",
  "type": "object",
  "required": ["schemaVersion","laneId","laneName","agent","generatedAt","status","scope","deliverables","verification","contractDeviations","risks","blockers","nextSteps"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "laneId":  { "enum": ["L1","L2","L3","L4","L5"] },
    "laneName": { "type": "string", "minLength": 1 },
    "agent": { "type": "string", "minLength": 1 },
    "generatedAt": { "type": "string", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$" },
    "status": { "enum": ["COMPLETED","PARTIAL","IN_PROGRESS","BLOCKED","FAILED"] },
    "scope": {
      "type": "object",
      "required": ["ownedDirs","touchedFiles"],
      "properties": {
        "ownedDirs":   { "type": "array", "items": { "type": "string" } },
        "touchedFiles":{ "type": "array", "items": { "type": "string", "pattern": "^(?!\\.\\.)[A-Za-z0-9._/\\-]+$" } }
      }
    },
    "deliverables": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["path","kind","built"],
        "properties": {
          "path":  { "type": "string" },
          "kind":  { "enum": ["source","build","doc","script","test"] },
          "built": { "enum": ["yes","no","untested"] },
          "notes": { "type": "string" }
        }
      }
    },
    "verification": {
      "type": "array", "minItems": 1,
      "items": {
        "type": "object",
        "required": ["command","expected","actual","passed"],
        "properties": {
          "command":  { "type": "string", "minLength": 1 },
          "expected": { "type": "string" },
          "actual":   { "type": "string" },
          "passed":   { "type": "boolean" }
        }
      }
    },
    "contractDeviations": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["target","desc","proposedChange","impact"],
        "properties": {
          "target": { "type": "string" },
          "desc": { "type": "string" },
          "proposedChange": { "type": "string" },
          "impact": { "enum": ["none","minor","breaking"] }
        }
      }
    },
    "risks": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["level","desc","mitigation"],
        "properties": {
          "level": { "enum": ["high","medium","low"] },
          "desc": { "type": "string" },
          "mitigation": { "type": "string" }
        }
      }
    },
    "blockers": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["desc","neededFrom","owner"],
        "properties": {
          "desc": { "type": "string" },
          "neededFrom": { "type": "string" },
          "owner": { "type": "string" }
        }
      }
    },
    "metrics": { "type": "object" },
    "nextSteps": { "type": "array", "items": { "type": "string" } }
  }
}
```

**最小示例**（节选）：

```json
{
  "schemaVersion": "1.0",
  "laneId": "L1",
  "laneName": "协议与公共库",
  "agent": "core-proto",
  "generatedAt": "2026-09-20T15:04:05+08:00",
  "status": "COMPLETED",
  "scope": {
    "ownedDirs": ["shared/", "android/app/src/main/cpp/"],
    "touchedFiles": ["shared/include/apx/frame.h", "shared/src/frame.cpp", "shared/CMakeLists.txt"]
  },
  "deliverables": [
    { "path": "shared/include/apx/frame.h", "kind": "source", "built": "yes", "notes": "ApxFrameHeader 16B + CRC32" }
  ],
  "verification": [
    { "command": "cmake -S shared -B shared/build && cmake --build shared/build && ./shared/build/apx_selftest",
      "expected": "all tests passed", "actual": "12/12 passed", "passed": true }
  ],
  "contractDeviations": [],
  "risks": [ { "level": "low", "desc": "MSVC 与 NDK 对齐差异", "mitigation": "结构体显式 packed + 静态断言" } ],
  "blockers": [],
  "metrics": { "selftestPassRate": "12/12" },
  "nextSteps": ["提供 hid_descriptor 生成结果供 L2 挂载使用"]
}
```

---

## 6. 汇总方式

主控（main）执行 `scripts/aggregate.py`（无第三方依赖，标准库实现），流程：

1. **收集**：扫描 `reports/L*.json`，缺失的 laneId 记为 `UNKNOWN`（空占位，不报错中断）
2. **校验**：逐份按 §5 schema 校验；校验失败的文件保留原文，在汇总中标红，并要求该路重新回报
3. **越权检查**：比对 `scope.ownedDirs` 与 §2 归属目录，越权项列入「合规问题」
4. **状态矩阵**：生成五路 × (status / 交付数 / 验证通过数 / 阻塞数) 的矩阵
5. **缺口清单**：汇总所有 `blockers`、`contractDeviations`、`verification.passed=false` 项，标注 `neededFrom`（L1..L5 / main / 外部）
6. **裁决与写回**：`impact=breaking` 的协议偏离，由 main 修改 `docs/PROTOCOL.md` 并按 §10 递增版本号后**广播**通知五路；其余偏离记入待办
7. **里程碑判定**：依据 MS1–MS6（`ARCHITECTURE.md` §8）给出可达成/不可达成结论与理由
8. **产出**：`reports/STATUS.json`（机读）、`reports/SUMMARY.md`（人读）、控制台摘要

---

## 7. 输出目标

| 输出 | 形式 | 消费者 | 说明 |
|---|---|---|---|
| `reports/<laneId>.json` | 文件 | 汇总脚本、CI | 每路原始回报，机读 |
| `reports/<laneId>.md` | 文件 | 人 | 每路摘要，含验证命令 |
| `reports/STATUS.json` | 文件 | 控制面板、CI | 汇总状态：`{generatedAt, lanes:[{laneId,status,deliverables,verificationPassed,blockers}], verdict:{MS1..MS6}, openIssues[]}` |
| `reports/SUMMARY.md` | 文件 | 人 | 状态矩阵 + 缺口清单 + 里程碑判定 + 下一步 |
| 控制台 / 会话摘要 | 文本 | 用户 | main 向用户输出结论，不超过一屏 |
| `pc/host` 控制面板（后续） | 界面 | 用户 | 读取 `reports/STATUS.json` 展示五路状态；本需求只要求**预留读取接口**，不要求本轮实现 |
| 通知 | 会话消息 | main → 用户 | 仅在① 全路完成 ② 出现 `BLOCKED`/`FAILED` ③ 出现 breaking 偏离时主动通知 |

---

## 8. 异常与缺失数据处理

| 情形 | 处理 |
|---|---|
| 某路未回报 / 超时 | 记 `UNKNOWN`，在 SUMMARY 中单列，**不阻塞汇总与其它路**；main 点名索要一次，仍无响应则标注「需人工介入」 |
| JSON 解析失败或不符合 schema | 保留原文件于 `reports/`，汇总中标记 `INVALID`，要求重发；不得以自由文本替代 |
| `status = BLOCKED` | 提取 `blockers[].neededFrom`，按 L1→L5 顺序优先解除依赖；无法自解的转 main 或上报用户 |
| `verification.passed = false` | 计入「未验证清单」，该路 `status` 最高只能判 `PARTIAL`；MS 判定不得依赖未验证项 |
| 接口缺失（`contractDeviations`） | 汇总去重后由 main 裁决：能改 `shared/` 的派给 L1，需改协议的走版本递增；裁决结果广播 |
| 内容互相矛盾（两路对同一字段定义不一致） | 以 `docs/PROTOCOL.md` 为准；冲突项列入「待裁决」并暂停相关 MS 判定，不擅自取舍 |
| 越权修改（`ownedDirs` 超出归属 / 改了 `docs/` 或他人目录） | 列入「合规问题」，要求该路回滚越权部分并在回报中说明 |
| 交付物构建失败（`built = no`） | 允许交付，但必须在 `verification` 中给出失败输出原文，不得静默 |
| 数据缺失字段 | 必填字段缺失即判 `INVALID`（见上）；可选字段缺失用默认值，不推断、不编造 |

---

## 9. 权限要求

| 主体 | 权限 |
|---|---|
| 各路（L1–L5） | 仅可写：本路归属目录 + `reports/<laneId>.json` + `reports/<laneId>.md`；**只读** `docs/`、`shared/`（除 L1）、他人目录 |
| L1 | 唯一可写 `shared/` 与 `android/app/src/main/cpp/` 的一路 |
| main | 唯一可写 `docs/`（协议与架构）、`reports/STATUS.json`、`reports/SUMMARY.md`、`scripts/aggregate.py` |
| 协议版本号 | 仅 main 可变更；各路不得自行递增或另起字段命名 |
| `scripts/aggregate.py` | 归 main（汇总器），与 L2 在 `scripts/` 内的 Gadget 脚本共存但互不修改 |
| 删除 | 任何一路**不得删除**他人产物与历史回报；仅可覆盖自己的 `reports/<laneId>.*` |
| 外部网络 | 本需求不涉及；禁止在回报中引入需要下载才能验证的依赖 |

---

## 10. 审计与记录留存

1. **全量留存**：`reports/` 下所有文件保留，不做清理；`.gitignore` 不得忽略 `reports/`
2. **历史快照**：每次汇总前，将上一版 `STATUS.json`/`SUMMARY.md` 复制到 `reports/history/<yyyyMMdd-HHmmss>/`，追加式，不覆盖
3. **可追溯**：每份回报必须含 `generatedAt` 与 `touchedFiles`，使任意文件可追溯到产生它的 lane
4. **变更留痕**：`docs/PROTOCOL.md` 每次修改需递增版本号并在 `reports/SUMMARY.md` 记录「变更原因 + 影响范围 + 受影响 lane」
5. **审计视图**：SUMMARY 必须包含「合规问题」章节（越权、schema 无效、未验证项），即使为空也要显式声明「无」
6. **只读真源**：`ARCHITECTURE.md` / `PROTOCOL.md` 的修改权归 main，任何修改都必须有对应回报条目作为依据，禁止无据改动

---

## 11. 验收标准（DoD）

- [ ] 五份 `reports/L1..L5.json` 齐备（或以 `UNKNOWN` 显式标注），全部通过 §5 schema 校验
- [ ] 每份回报含 ≥1 条可复现验证命令，且 `actual` 为真实执行结果
- [ ] `reports/STATUS.json` 与 `reports/SUMMARY.md` 已生成，含状态矩阵、缺口清单、合规章节、里程碑判定
- [ ] 所有 `BLOCKED`/`FAILED`/`INVALID` 项均有 `owner` 与下一步动作
- [ ] 无越权修改；`docs/` 变更均有版本递增与留痕
- [ ] main 已向用户输出不超过一屏的结论摘要（含 MS1 是否可进入真机验证）
