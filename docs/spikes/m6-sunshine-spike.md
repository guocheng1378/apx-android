# M6 Sunshine 推屏 spike（第一阶段）

> 2026-11-05。范围：调研 + 选型方案 + 可编译的子进程实测脚手架。
> 真机实测**未执行**（云端无显示环境、无 Sunshine 运行时）——步骤在 §4，需在装有
> Sunshine 的 PC + 手机上按清单跑；没跑过的一律标「未实测」，不假装完成。
> 红线：不发版不打 tag、不内嵌 Moonlight 客户端（GPLv3 与 MIT 冲突，ROADMAP §2.5）。

## 1. 侦察结论（仓库现状）

| 证据 | 位置 | 说明 |
|---|---|---|
| Sunshine 只读检测已交付 | `pc/host/src/screen/sunshine_detect.cpp` + `include/apxpc/screen/sunshine_detect.hpp` | 「装没装」（配置目录存在）与「跑没跑」（47990 端口有人听）两条证据分开报 |
| 面板接线 | `src/ui/panel_http.cpp`、`pc/web/src/components/ScreenCard.tsx`、`pc/web/src/api/bridge.ts` | mac/Linux 显示检测结果 + 指引，不假装能开 |
| 球动作注册表 | `src/ball/registry.{hpp,cpp}` | `sunshine_running` 已是副屏动作的 enabled 条件 |
| 方案评估文档 | `docs/INTEGRATIONS.md` §1 | 结论：做「档 1 托管 + 引导」（1–2 天），不做内置 Moonlight 客户端 |
| 路线结论 | `docs/ROADMAP.md` §2.5 | mac/Linux 不自研副屏（缺虚拟显示器驱动），出路 = 用户自装 Sunshine + Moonlight |
| scrcpy | `pc/display/transport/tcp_transport_win.cpp` | 仅两处设计注释（多 socket 对照），无任何代码依赖 |
| spike 计划文档 | 此前无 | 本文件即首份 |

## 2. 三条候选路线评估

| 路线 | 做法 | 优点 | 代价 | 判定 |
|---|---|---|---|---|
| ① Sunshine 作子进程（**采纳**） | PC 端只管起/停/日志/配对引导；采集、编码（NVENC/AMF/QSV/VAAPI）、网络全由 Sunshine 自带 | 零协议风险、延迟成熟、INTEGRATIONS §1 已评估为 1–2 天档 | 体验「外挂」、需用户自装 | **M6-a，本阶段** |
| ② 自研 FFmpeg/GDI 采集 + RTSP/WebRTC 推流 | 自己拼采集/编码/传输 | 全可控、理论上可复用自研副屏管线 | 数周级工作量；与已交付的 Windows 自研副屏链路（`screen_push`/`FrameWriter`）重复造轮子 | 不做 |
| ③ scrcpy 子进程 | 拉起 scrcpy 把手机画面投到 PC | 免 root 看画面 | 方向是「手机 → PC」，不是 PC 副屏推屏；协议无稳定 API | 不适用本目标（互控场景见 INTEGRATIONS §2 档 1） |

**接入点**：不碰自研副屏编码管线（`pc/display` 的 `FrameWriter` 那条照旧跑 Windows 虚拟显示器）；
复用的是**检测与指引埋点**（`sunshine_detect` + ScreenCard 指引 + 球 `sunshine_running` 使能），
子进程托管是新增执行面，落在 `sunshine_launch.*` + `apxhost sunshine-spike`。

## 3. 本阶段交付（脚手架）

- `pc/host/include/apxpc/screen/sunshine_launch.hpp` + `pc/host/src/screen/sunshine_launch.cpp`
  - `findSunshineExecutable`：显式路径 → PATH → 各平台常见安装位，找不到返回空串
  - `startSunshineProcess`：stdout/stderr 追加到临时目录日志；pid 写 pid 文件；POSIX 侧 200ms 复核秒退
  - `stopSunshineProcess`：SIGTERM → 2s → SIGKILL（Windows 直接 Terminate）；只按 pid 记录杀，不按进程名扫
  - `spikeChildAlive` / `forgetSpikeChild`（子进程已自退时只清记录，防 pid 复用误杀）
- `apxhost sunshine-spike` 子命令：`start [exe路径] [秒数]` / `stop` / `status`
- CMake：`sunshine_launch.cpp` 进 `APXPC_COMMON_SRC`（core），apxhost 直接可用，不进面板 TU

## 4. 真机实测步骤（需用户在 PC + 手机上执行）

前置：一台 PC（与手机同一局域网）已装 Sunshine（官方仓库：https://github.com/LizardByte/Sunshine ，
按其 README/Releases 安装）；手机装 Moonlight（https://moonlight-stream.org ）。

1. PC 上取 apxhost：本地 `cmake --build` 或 CI 构建产物。
2. `apxhost sunshine-spike status` → 预期打印「Sunshine 可执行文件：<路径>」。
   未找到就显式给路径：`apxhost sunshine-spike start <exe路径>`。
3. `apxhost sunshine-spike start 60` → 预期打印 pid 与日志路径，60 秒内轮询显示「子进程存活」。
   - 失败判据：打印的启动失败原因（exe 找不到 / 日志打不开 / 立即退出 code N）；日志在临时目录 `apx_sunshine_spike.log`。
4. 浏览器打开 `https://localhost:47990`（`sunshine_detect.hpp` 的默认 https 端口）完成 Sunshine 首次配置。
5. 手机 Moonlight → 添加主机 → 输入 Sunshine 页面给出的 PIN 完成配对 → 连接，确认能看到 PC 画面。
6. 回收：`start` 到时/按 Enter 会自动停；异常残留时 `apxhost sunshine-spike stop`；
   只读复核用 `apxhost sunshine-spike status`（running 看 sunshine_detect 的端口证据）。
7. 把结果（哪步过、哪步卡、日志摘录）回传 —— 这是 M6-a 验收的最后一步。

## 5. 未完成 / 边界

- **真机实测：未执行**（本阶段在无显示环境里完成，只到「脚手架可编译 + 实测步骤文档」）。
- 内网穿透：按 INTEGRATIONS §1 结论，不做一键开；后续若做也必须显式开关 + 提示，默认只走局域网。
- 配对自动化、延迟/码率指标：属 M6 后续阶段；本 spike 只到「子进程起得来、停得掉、有日志、有指引」。
- 已知坑：若 Sunshine 自身守护化（父进程秒退），`spike 子进程` 会显示无 —— 以 `status` 里
  sunshine_detect 的 running 证据为准（端口有人听 = 在跑）。
