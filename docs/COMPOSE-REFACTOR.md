# Android UI 全量重构为 Compose + Miuix —— 方案

> 定案：2026-10-28。口径见 `docs/ROADMAP.md` §5；本文是**做法**。
> 需求原文：「是整个软件重构」「不留以前的 ui」。
> 参考工程：MiuixGuiExample（katiusu/MiuixGuiExample）、Miuix（compose-miuix-ui/miuix）。

---

## 1. 目标与范围

**目标**：Android 端（手机 + 电视）的全部界面重写为 Compose + Miuix，**旧 UI 全部删除**。

**范围内**：`android/app`（手机端 UI）、`android/tv`（电视端 UI）。
**范围外**（不动）：协议与传输（core / hid / audio / bt / gadget / wireless / localsend / controlled 的非 UI 部分）、
NDK 与 `shared/` C++、PC 端。

**旧 UI 的处置规则（不留以前的 UI）**：

| 规则 | 说明 |
|---|---|
| 同一提交内替换并删除 | 某页的 Compose 版合入的**同一个提交**里删掉对应 Views 类与布局 XML |
| 不双轨 | 同一时刻只有一个实现是活的；不做「新旧并存 + 开关切换」 |
| 不冻结 | 旧代码不留在仓库里当参考 —— 参考靠 git 历史与本方案 |
| 回退靠 git | 每个阶段打 tag（`compose-refactor/p1` 等），出问题回滚 tag，不靠运行时开关 |

**工作量基数**（实测行数，`android/app/src/main/java/com/allperiph/`）：

| 分区 | 文件数 | 行数 | 说明 |
|---|---|---|---|
| `ui/`（主界面、设置、状态、组件） | 45 | 10 946 | 含 MainActivity 2 310、SettingsFragment 1 041、StatusFragment 635 |
| `touchpad/` | 5 | 2 673 | 触控板（手势面） |
| `screen/` | 2 | 463 | 副屏 |
| `localsend/` + `controlled/` 中的 UI Activity | — | 470 | SendActivity 132、RemoteInputActivity 338 |
| `res/layout/*.xml` | 12 | — | 全部删除 |
| `android/tv/.../ui/` | 11 | 1 639 | 电视端 |
| **合计（待重写）** | **≈ 60** | **≈ 16 000** | 另有主界面 2 310 行内混着编排逻辑，需拆分 |

---

## 2. 目标架构

```
android/app/src/main/java/com/allperiph/
├── ui/                      # ← 旧 Views 实现，迁移完成即整目录删除
├── ui/miuix/                # ← 新：Compose 脚手架层（本批已建）
│   ├── MiuixForm.kt         #    形态层：宽度断点 + 手动覆盖
│   ├── MiuixTokens.kt       #    主题层：md_* → Miuix Colors 的唯一映射 + override 钩子
│   └── MiuixShowcaseActivity.kt  # 组件总览（开发页）
├── feature/<screen>/        # ← 新：按屏幕分包，每包一个 Composable 屏幕
│   ├── home/ status/ settings/ touchpad/ gamepad/
│   ├── remote/ files/ send/ screen/ shortcuts/
└── core/ ...                # 不动
```

**分层**：

| 层 | 职责 | 关键类型 |
|---|---|---|
| 主题层 | 明暗 / 系统取色 / 皮肤强调色 → Miuix `Colors` | `MiuixTokens`、`ApMiuixTheme` |
| 导航层 | 页面栈、返回、predictive back | Miuix `miuix-nav` 的 `NavController` / `NavDisplay` / `NavKey` |
| 形态层 | 手机 / 平板 / 宽屏 / 电视 的骨架选择 | `MiuixForm`、`LocalMiuixFormOverride` |
| 状态层 | 把 `AgentController` / `PrefsStore` / 协议状态喂给 Compose | `rememberXxxState` + `StateFlow`（`androidx.lifecycle`） |
| 屏幕层 | 具体 Composable | `feature/**` |

---

## 3. 页面清单（整屏重写 + 同提交删除）

| # | 现有实现（Views） | 行数 | 新实现 | 阶段 | 删除清单 |
|---|---|---|---|---|---|
| 1 | `ui/MainActivity.kt` | 2 310 | Compose 主壳（首页 + 导航 + 状态区） | P1 | `ui/MainActivity.kt`、`activity_main.xml`、`nav_tabs.xml`、`header_bar.xml` |
| 2 | `ui/StatusFragment.kt` + `ui/StatusRows.kt` | 635 + 104 | 状态页（Compose） | P1 | 同名文件、`page_status.xml`、`item_module_row.xml` |
| 3 | `ui/SettingsFragment.kt` | 1 041 | 设置页（Compose，偏好行数据驱动） | P1 | `ui/SettingsFragment.kt`、`page_settings.xml` |
| 4 | `ui/TouchpadFragment.kt` + `ui/TouchpadSplit.kt` + `ui/TouchCursorView.kt` | 300 + 203 + 233 | 触控板页 + 手势面 | P2 | 同名文件、`view_touchpad_panel.xml` |
| 5 | `touchpad/TouchpadActivity.kt` | 1 505 | 全屏触控板 Activity（Compose） | P2 | 同名文件 |
| 6 | `ui/GamepadActivity.kt` + `ui/JoystickView.kt` + `ui/GamepadController.kt` | 236 + 85 | 手柄页 + 虚拟摇杆 | P2 | `ui/GamepadActivity.kt`、`ui/JoystickView.kt`（控制器逻辑留，视图层换） |
| 7 | `ui/RemoteActivity.kt` | 573 | 远控页 | P3 | 同名文件 |
| 8 | `ui/FilePanelActivity.kt` | 334 | 文件面板 | P3 | 同名文件 |
| 9 | `localsend/SendActivity.kt` | 132 | 发送页 | P3 | 同名文件、`page_localsend_send.xml` |
| 10 | `controlled/RemoteInputActivity.kt` | 338 | 被控输入页 | P3 | 同名文件 |
| 11 | `ui/ControlShortcutActivity.kt` | 41 | 快捷键浮层 | P3 | 同名文件 |
| 12 | `screen/ScreenActivity.kt` | 370 | 副屏 | P3 | 同名文件、`activity_screen.xml` |
| 13 | `ui/CustomKeyboardDialog.kt` + `ui/HotkeyBoard.kt` + `ui/KeyboardPanels.kt` | 145 + 505 + 525 | 键盘面板（Compose） | P3 | 同名文件 |
| 14 | `ui/ComboChip.kt` + `ui/NavBarDrawable.kt` + `ui/Backdrop.kt` + `ui/GlassRipple.kt` + `ui/Feedback.kt` + `ui/AllPeriphWidget.kt` | 115 + 123 + 49 + 102 + 64 + 69 | 由 Miuix 组件替代（无对应新文件） | P3 / P5 | 全部删除 |
| 15 | `ui/kit/ApKit.kt`（Views 设计系统，78 行） | 78 | 由 `MiuixTokens` + Miuix 组件取代 | P5 | 整个 `ui/kit/` 删除 |
| 16 | `ui/ThemeSkin.kt` + `ui/ThemePref.kt` | 274 + 42 | 迁入 Miuix `ThemeController` / `ColorSchemeMode` + Monet | P1 | 迁移完成后删除（皮肤强调色降级为 override） |
| 17 | `ui/KeyLayouts.kt` + `ui/KeyPref.kt` | 98 + 128 | 键位数据层（数据保留，UI 换 Compose） | P3 | 仅改 UI 侧 |
| 18 | `tv/ui/*`（MainActivity / TvScreenActivity / TvOverlay / RemoteInputOverlay / TvFilePrompt / TvNotificationPrompt / TvFileActivity / TvUi / TvFiles / TvInputDispatcher / ApxFileProvider） | 1 639 | 电视端 Compose（10-foot 焦点 + 侧栏导航） | P4 | 旧文件逐个删除（`ApxFileProvider` 是零依赖实现，需先解决 FileProvider 依赖） |
| 19 | `res/layout/*.xml`（12 个） | — | 由 Compose 取代 | 随各阶段 | 全部删除 |
| 20 | `res/values/colors.xml` 的 `md_*` 令牌 | — | Miuix `Colors` 成为唯一真源 | P5 | `md_*` 令牌删除（迁移期保留作 override 源） |

> 逻辑层（`AgentController` / `EnvChecks` / `GamepadController` / `KeyPref` 数据 / 协议回调）**保留**，
> 只换「怎么显示」；Compose 侧通过 `StateFlow` / `remember` 订阅，不改变线程约定
> （`apx-ui-io` 单线程的阻塞操作规则照旧）。

---

## 4. 关键决策与依据

| # | 决策 | 依据 |
|---|---|---|
| D1 | Miuix **0.9.4**（多模块） | 0.9.x 才有 `NavigationRail`、`ColorSchemeMode`/`ThemeController`（Monet 取色）、`textureBlur`、`miuix-nav`；0.5.0 只有 NavigationBar、没有 Monet 入口（v0.5.0 源码核对） |
| D2 | compileSdk **37** | MiuixGuiExample `app/build.gradle.kts` 注释：Miuix 0.9.4 的 AAR 元数据要求依赖方 compileSdk ≥ 37 |
| D3 | AGP 9.4.1 / Gradle 9.7.1 / Kotlin 2.4.21 | Miuix 自身与 MiuixGuiExample 的版本表一致 |
| D4 | 导航用 **Miuix 自带 `miuix-nav`**，不引 androidx.navigation | 该模块已提供 `NavController` / `NavDisplay` / `NavKey` / `PredictiveBackHandler` / `WindowNavigationEventBridge` |
| D5 | 主题真源迁到 Miuix `Colors`；系统取色用 `ColorSchemeMode.Monet*` | 与 MiuixGuiExample 的 `Theme.kt` 一致（`ThemeController(themeMode)`） |
| D6 | 形态层自研 `MiuixForm`（600/840/1200dp） | 断点直接取自 MiuixGuiExample 的 `shouldShowSplitPane()`（≥840dp 或 ≥600dp 且高宽比<1.2）与 `shouldExpandNavigationRail()`（≥1200dp） |
| D7 | 组件级自定义入口 = `MiuixTokens.scheme(dark, override)` | 保持「全局主题唯一真源 + 局部覆盖」的结构，避免各处写死色值 |
| D8 | **不引 Haze**；模糊材质先用 Miuix 自带 `miuix-blur` / `miuix-shader` | 少一个第三方；MiuixGuiExample 的 Haze 是它自己选的，可换 |
| D9 | 旧 UI **同提交删除**，不做双轨 | 需求明确「不留以前的 ui」；避免双份 UI 长期漂移 |

**与 MiuixGuiExample 的差异**：示例是「纯 GUI 示例工程」（有 `bridge/` 桩），本仓是真实业务
（协议、输入输出、编排），所以示例的页面代码只能借鉴**形态与组件用法**，业务逻辑全部自写。

---

## 5. 分阶段里程碑

### P0 工具链与基础设施
- 抬 AGP/Gradle/Kotlin/compileSdk/build-tools，加 Compose BOM、activity-compose、Miuix 0.9.4 多模块；
- 建主题层（`MiuixTokens` 迁到 0.9 API：Monet 取色 + 皮肤 override）、导航层（`miuix-nav` 接入）、形态层；
- CI：sdkmanager 增 android-37 与 build-tools 37；wrapper 改 9.7.1。
- **出口**：新旧两套能同时构建（此时旧 UI 仍在），CI 绿，APK 体积基线记录在案。
- **回退**：tag `compose-refactor/p0`。

### P1 主壳：首页 / 状态 / 设置
- Compose 主壳（`MainActivity` 换 `ComponentActivity`）+ 底部导航 / 侧栏（形态自适应）；
- 状态页、设置页（46 项 / 7 组，偏好行由数据驱动）；
- 主题：明暗、Monet 取色、皮肤强调色。
- **出口**：三页功能对齐（逐项对照旧版），**同提交删除** `MainActivity`/`StatusFragment`/`SettingsFragment`/`StatusRows` 及 5 个布局 XML。
- **回退**：tag `compose-refactor/p1`。

### P2 手势面：触控板 / 虚拟摇杆
- `TouchCursorView` / `JoystickView` 用 `pointerInput` 重写（多指、滑动惯性、按下态、边界回弹）；
- 触控板页（Fragment 版）与全屏 Activity 版（`TouchpadActivity`）。
- **出口**：真机手势与端到端延迟达标（与旧实现对比测量），**同提交删除**旧 View 与旧 Activity。
- **风险最高**：手势时序与手感无法靠单测覆盖，必须真机回归。
- **回退**：tag `compose-refactor/p2`。

### P3 次级页面
文件面板 / 发送 / 远控 / 被控输入 / 快捷键 / 副屏 / 键盘面板 / 自绘组件替代。
- **出口**：逐页替换 + 删除；副屏（`screen/`）要额外验证「另一个 Activity 进程」的显示路径。

### P4 电视端
- TV 端 Compose 重写：10-foot 焦点系统（焦点可见性、焦点放大、按压态）、方向键导航；
- 形态钉死 `MiuixForm.TV`（TV 屏幕形态固定，不跟随窗口抖动）；
- `ApxFileProvider` 的零依赖实现需要替换（Compose 不解决 FileProvider 依赖问题，仍需 `androidx.core` 或自写 —— **本仓维持非 UI 层零依赖，故保留自写实现**）。
- **出口**：遥控器全流程可用（焦点、返回、长按），**同提交删除**旧 TV UI。

### P5 收口
- 删 `ui/kit/ApKit.kt`、`res/layout/*`、`md_*` 令牌、`ThemeSkin`（若已并入 override）、`combo`/`NavBarDrawable` 等自绘组件；
- 全仓 grep 确认无 Views UI 残留（`setContentView` / `findViewById` / `LayoutInflater`）；
- CI 加一条「UI 层不得出现 `setContentView`」的检查，防止旧写法回流。

---

## 6. 风险登记册

| 风险 | 影响 | 缓解 |
|---|---|---|
| AGP 9 / Kotlin 2.4 / compileSdk 37 跃迁 | 构建链、NDK/CMake 兼容、CI 时长与稳定性 | P0 单独一个阶段，先只升工具链不换 UI；保留旧 Android CI 任务对照 |
| Miuix 官方自述 experimental，API 可能无预告变更 | 升级即返工 | 版本钉死；只经 `MiuixTokens` / 脚手架层接触 MIUIX API，换版本只改这一层 |
| 手势面重写 | 触控板手感与延迟退化 | P2 单独排期；真机对比测量；必要时该页保留 Views 实现直到达标（但最终必须删） |
| TV 焦点系统 | 遥控器不可用 | P4 单独排期；焦点策略用 Compose 原生 focus + Miuix 组件，不用自绘 |
| APK 体积 / 冷启动 | Compose 显著增大包体 | 参考 MiuixGuiExample：release 走 R8 + `baseline.prof`；本仓 release 需重新定基线并量 |
| 迁移期范围漂移（边改边加功能） | 工期失控 | 重构期**功能冻结**：只重构不加功能，新需求等重构收口 |
| 团队学习曲线（Compose） | 交付节奏 | 以脚手架层封装主题/导航/形态，业务同学只写 Composable |

---

## 7. CI 与工具链改造清单

| 文件 | 改动 |
|---|---|
| `android/build.gradle.kts` | AGP 8.7.3 → 9.4.1；Kotlin → 2.4.21；compose 插件同版本 |
| `android/gradle/wrapper/gradle-wrapper.properties` | Gradle 8.9 → 9.7.1 |
| `android/app/build.gradle.kts`、`android/tv/build.gradle.kts` | compileSdk 35 → 37；Compose BOM + activity-compose + Miuix 0.9.4 多模块 |
| `.github/workflows/build.yml` | sdkmanager 增 `platforms;android-37`、`build-tools;37.0.0`；`gradle wrapper --gradle-version 9.7.1` |
| `scripts/check_ui_tokens.py` | 中文文案棘轮改为条件基线（演示页在时放宽，主线不变） |
| 新增 `scripts/check_no_views_ui.py` | P5 起：UI 层禁止 `setContentView` / `findViewById` / `LayoutInflater` |

---

## 8. 验收标准（可勾选）

- [ ] `./gradlew :app:assembleRelease :tv:assembleRelease` 通过，CI 四条任务（build / test / lint / publish）全绿；
- [ ] 全仓无旧 Views UI 残留（`setContentView` 等为 0），`res/layout` 清空；
- [ ] 手机端三形态（手机 / 平板 / 宽屏）布局正确，形态切换即时生效；
- [ ] 电视端遥控器焦点全流程可用（无「焦点丢失」与「焦点不可见」）；
- [ ] 触控板 / 虚拟摇杆：手势与延迟不劣于旧实现（真机对照测量）；
- [ ] 协议与输出面回归：所有输入出口（USB / 蓝牙 / 网络 / 音频 / 副屏）行为不变；
- [ ] 主题：明暗切换、系统取色、皮肤强调色三者叠加表现正确；
- [ ] 文案全部走 `strings.xml`（Compose 侧 `stringResource`），中文文案棘轮回到 ≤ 530；
- [ ] APK 体积与冷启动有基线数据并与旧版对比留档。

---

## 9. 本批（2026-10-28）已交付

### 9.1 P0 工具链与基础设施（已落地）

| 文件 | 改动 | 依据 |
|---|---|---|
| `android/build.gradle.kts` | AGP 8.7.3 → **9.4.1**；Kotlin 2.1.21 → **2.4.21**；compose 插件同版本 | Miuix 自身 `gradle/libs.versions.toml`（agp 9.4.1 / kotlin 2.4.20~21） |
| `android/gradle/wrapper/gradle-wrapper.properties` | Gradle 8.9 → **9.7.1** | AGP 9 要求 Gradle 9+；MiuixGuiExample 同值 |
| `android/app/build.gradle.kts` | compileSdk 35 → **37**；依赖换成 Compose BOM **2026.09.00** + activity-compose **1.13.0** + Miuix **0.9.4**（core/ui/preference/icons/squircle/nav） | Miuix 0.9.4 的 AAR 元数据要求 compileSdk ≥ 37；MiuixGuiExample 的依赖集 |
| `android/tv/build.gradle.kts` | compileSdk 34 → **37**；**minSdk 23 → 24** | Miuix 的 `build-plugins/BuildConfig.kt`：COMPILE_SDK 37 / TARGET_SDK 37 / **MIN_SDK 24** |
| `.github/workflows/build.yml` | JDK **17 → 21**；sdkmanager 装 `platforms;android-37` + `build-tools;37.0.0`；wrapper 生成 9.7.1 | AGP 9.4.1 + Kotlin 2.4.21 的组合按 JDK 21 跑（Miuix 自身 CI 即 zulu 21） |

### 9.2 脚手架三层（已落地）

| 层 | 文件 | 内容 |
|---|---|---|
| 主题层 | `ui/miuix/MiuixTokens.kt` | md_* 令牌 → Miuix `Colors` 的**唯一映射**（`remember` 缓存，值相等即命中）；`scheme(dark, override)` 是组件级自定义入口 |
| 主题层 | `ui/miuix/ApTheme.kt` | `ThemeMode`（系统 / 浅色 / 深色 + 三档 Monet 取色）→ Miuix `ColorSchemeMode`；持久化写 `apx_theme/compose_mode`（与旧 `mode` key 不冲突）；`ApMiuixTheme` 根主题 |
| 形态层 | `ui/miuix/MiuixForm.kt` | 形态枚举 + 600/840/1200dp 断点 + `LocalMiuixFormOverride` 手动覆盖 + `formIndexOf` / `formOverrideOf` |

### 9.3 新外壳（P1 第一刀，已落地）

| 文件 | 内容 |
|---|---|
| `ui/feature/shell/ApShell.kt` | **形态自适应骨架**：手机 → 底部 `NavigationBar`；平板/宽屏 → 左侧 `NavigationRail`；电视（≥1200dp）→ 展开成完整侧栏。断点与展开规则来自 MiuixGuiExample，骨架实现是本仓自己的 |
| `ui/feature/shell/ShellScreens.kt` | 首页 / 状态 / 设置三个**占位页**；设置页里主题模式与界面形态两个开关是**真的能用的** |
| `ui/feature/shell/ApShellActivity.kt` | 宿主（`ComponentActivity` + `setContent`），不挂桌面图标，用 `adb shell am start -n com.allperiph/com.allperiph.ui.feature.shell.ApShellActivity` 起 |
| `res/values/strings.xml` | 新 UI 文案全部走资源（`shell_*` / `theme_mode_*` / `form_*`） |
| `AndroidManifest.xml` | 注册新外壳 Activity（`exported=false`） |
| `ui/miuix/MiuixShowcaseActivity.kt` | 组件总览**按 0.9.4 重写**：`Super*` → `SwitchPreference` / `ArrowPreference` / `WindowDropdownPreference`，`SuperDialog` → `WindowDialog`，`Slider` 参数改名，`Checkbox` 改收 `ToggleableState` |

### 9.4 未验证 / 下一步

- **未经编译**：本环境没有 Android SDK 与 JDK，以上 Kotlin 代码只做了静态核对
  （API 逐个对着 Miuix v0.9.4 的 tag 源码核过）。落地前请在有 SDK 的机器上
  `cd android && ./gradlew assembleDebug`；工具链已是 9.7.1 + AGP 9.4.1，需要本机有对应 SDK。
- **下一步（P1 主体）**：首页 / 状态 / 设置三页的真实内容迁到 Compose，
  迁完一页删一页旧实现（`ui/MainActivity.kt`、`ui/StatusFragment.kt`、
  `ui/SettingsFragment.kt` 与对应布局），不留双轨。
- **风险提醒**：AGP 9 + compileSdk 37 + JDK 21 是本仓**第一次**用这套工具链，
  首次 CI 可能需要修 AGP 9 的破坏性变更（打包、lint、NDK 任务）。

### 9.6 第二批：状态页 Compose 化（2026-10-28）

**基线变了**：本批不是接在上一批的分支上，而是接在**远端 miuix-ui 的新提交**上
（`598c6b4`：AGP 9.4.1 + Gradle 9.7.1 + compileSdk 37 + Miuix 0.9.4 + 组件总览重写）。
那批已经把工具链抬到位、组件总览按 0.9.4 重写（含渐进模糊顶栏 / 悬浮玻璃底栏 / OS4 弹簧），
所以本批**不再重复改工具链**，只补它没做的：形态层 + 外壳骨架 + 状态页。

| 文件 | 内容 |
|---|---|
| `ui/EnvHolder.kt`（新） | 环境探测结果（root / 链路速度 / UDC / 电池白名单 / 通知权限）的**唯一存放点**。原来只活在 `MainActivity.lastEnv` 里，状态页必须靠宿主接口才拿得到；MainActivity 仍负责发起探测，`lastEnv` 改为它的别名 |
| `ui/feature/status/StatusProvider.kt`（新） | 状态页**数据层**：取数全走单例（AgentController / EnvHolder / Uplink / EventBus），秒级轮询 + 四类事件触发重算；传输总开关 / 模块启停 / 双属模块「退出这一类」/ 音频方向子开关的动作与旧 `StatusFragment.onTransportToggle` 同一套生效路径 |
| `ui/feature/status/StatusScreen.kt`（新） | 状态页**界面层**（Miuix 组件）：总状态 + 环境摘要、链路诊断五行（Root / USB 速度 / USB 外设 / 后台运行 / 省电白名单）、三个传输总开关、各类下面的模块行（状态点 + 开关）、音频音箱/麦克风子开关 |
| `ui/miuix/MiuixForm.kt`（新） | 形态层：形态枚举（600 / 840 / 1200dp 断点）+ `LocalMiuixFormOverride` 手动覆盖 + 下拉映射；文案走 strings.xml |
| `ui/feature/shell/ApShell.kt`（新） | 形态自适应骨架：手机底部 `NavigationBar`，平板/宽屏左侧 `NavigationRail`，电视（≥1200dp）展开成完整侧栏 |
| `ui/feature/shell/ShellScreens.kt`（新） | 状态页接**真页面**；设置页放两个真开关：主题（走既有 `ThemePref`，与旧设置页同一份偏好）与界面形态 |
| `ui/feature/shell/ApShellActivity.kt`（新） | 宿主 Activity（不挂桌面图标，`adb shell am start -n com.allperiph/com.allperiph.ui.feature.shell.ApShellActivity`） |

**旧实现为什么还没删**：旧 `StatusFragment`（635 行）+ `StatusRows`（104 行）管的视图
（总状态块、传输开关、模块卡、链路行）**物理上长在旧设置页的布局里**（`page_settings.xml`
62KB），旧 app 还在用它。新状态页与旧状态页暂时并存 —— 等新外壳接管启动入口、
旧 app 整体下线时，同一个提交里删掉 `StatusFragment` / `StatusRows` /
`page_status.xml` 与 `page_settings.xml` 里的对应块。**不留双轨的最终态由这一步保证**，
不是靠现在提前删（提前删会让旧 app 少功能）。

**下一步（P1 剩余）**：设置页五个分类（通用 / 外观 / 遥控 / 关于 / 帮助）迁 Compose →
键盘页（KeyboardPanels）→ 触控板页（手势面，风险最高）→ 新外壳接管启动入口，
同提交删除 `MainActivity` / `StatusFragment` / `SettingsFragment` / `TouchpadFragment`
与全部旧布局。

### 9.5 坐标与版本核验（2026-10-28 实查，不是抄文档）

| 核验项 | 结果 |
|---|---|
| Miuix 版本坐标 | `top.yukonga.miuix.kmp:{miuix-ui,miuix-core,miuix-icons,miuix-preference,miuix-squircle,miuix-nav}-android` 在 Maven Central 上**存在**，0.9.4 是 `<latest>`（0.9.0~0.9.4 全系列在）；`miuix-nav-android` 只有 0.9.4，`miuix-squircle-android` 从 0.9.2 起 |
| Miuix 0.9.4 的编译前提 | 上游 `build-plugins/.../BuildConfig.kt`：COMPILE_SDK 37 / TARGET_SDK 37 / **MIN_SDK 24** / BUILD_TOOLS 37.0.0 / **JDK 21**；`miuix-ui-android-0.9.4.pom` 里 kotlin-stdlib **2.4.20**（故 Kotlin 选 2.4.21） |
| Compose BOM | `androidx.compose:compose-bom:2026.09.00` 在 Google Maven 存在（该系列最新一档） |
| activity-compose | `androidx.activity:activity-compose:1.13.0` 在 Google Maven 存在（1.14.0 仍是 alpha） |
| 组件 API 口径 | 逐个对着 Miuix v0.9.4 tag 源码核过：`Colors.copy`/`lightColorScheme`/`ThemeController(colorSchemeMode, lightColors, darkColors, isDark)`/`ColorSchemeMode(System/Light/Dark/Monet*)`/`NavigationBar(RowScope.NavigationBarItem)`/`NavigationRail(expanded)`/`Slider(value,onValueChange)`/`Checkbox(ToggleableState,onClick)`/`WindowDialog(show:Boolean)`/`SwitchPreference`/`ArrowPreference`/`WindowDropdownPreference` |
| 旧名已不存在 | `SuperSwitch` / `SuperArrow` / `SuperDropdown` / `SuperDialog` 在 v0.9.4 全库搜不到（对应新名见上表） |
