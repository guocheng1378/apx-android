# 发版手册（Release Runbook）

> 一条命令发版：`git tag vNN && git push origin vNN`，其余全自动。
> 本文是发版流程的唯一操作指南；三端版本号同源链的原理与排查也在这里。
> 机器实现见 `.github/workflows/build.yml`（`release` job）与 `android/app/build.gradle.kts`。

---

## 一、发版步骤

1. **确认 master 绿**：Actions → Build（push master 的那次）全绿。
2. **更新 `docs/CHANGELOG.md`**：把 `Unreleased` 段收进新版本条目（倒序，新在上）。
   **这条是发版门禁，不是建议**：Release 正文顶部的「改了什么」由 CI 从 CHANGELOG 的
   对应版本段自动提取（`release` job 的 Extract changelog 步骤）——没写那段，
   CI 打 `::error::` 并降级为占位正文，Release 页就看不到改了什么。段内要写清：
   改了什么、为什么、行为有什么变化；只有编译级验证的部分要如实写「待真机回验」。
3. **打 tag 并推送**（tag 必须 `v` + 数字开头，如 `v206`）：
   ```bash
   git tag v206
   git push origin v206
   ```
4. **CI 自动完成其余**（tag 事件触发，`needs: [pc, android]` 全绿后进 `release` job）：
   - Android：`app-release.apk` + `tv-release.apk`
   - PC：`apxhost / apxdesktop / apxsetup + WebView2Loader + web/`
   - 协议校验（`scripts/check_protocol.py`）
   - Inno Setup 打单文件安装包 + portable zip + 四件套重命名
   - **自动创建 GitHub Release**，正文 = CHANGELOG 对应版本段（改了什么）
     + `.github/release-body-assets.md`（各资产怎么用）+ 自动生成的 Full Changelog，
     `make_latest: true`
   - **macOS 资产**由 `macos.yml` 的 tag 构建独立上传到同一 Release（两边对同一 Release
     幂等协作：谁先完成谁建 Release，后到者 `--clobber` 追加）
5. **各端更新检查自动生效**（见第三节）。

> 检查：Actions → Build → run 顶部 `push tag v*` 事件 → 等 `release` job 绿 → Releases 页确认四件套。

---

## 二、产物命名（全部由 tag 派生）

| 产物 | 文件名 | 说明 |
|---|---|---|
| Windows 安装包 | `AllPeriph-<tag>-Setup.exe` | Inno 单文件，免管理员 |
| Windows 便携版 | `AllPeriph-<tag>-portable.zip` | 绿色免安装 |
| 手机 APK | `AllPeriph-<tag>-app.apk` | 触控板/键盘/… |
| 电视 APK | `AllPeriph-<tag>-tv.apk` | 被控端 |
| macOS (arm64) | `AllPeriph-<tag>-macos-arm64.zip` | 测试版（macos.yml 独立上传） |

---

## 三、三端版本号同源链（唯一真源 = git tag）

| 消费方 | 代码位置 | 机制 |
|---|---|---|
| App `versionName` / `RELEASE_TAG` | `android/app/build.gradle.kts` | `GITHUB_REF` 为 `refs/tags/*` → 取 `GITHUB_REF_NAME`；正则 `v[0-9][0-9A-Za-z.\-]*`，不匹配则回退 `1.8` 且 `RELEASE_TAG` 为空 |
| PC 关于页 / `apxhost --version` / 卸载项 `DisplayVersion` | `pc/host/CMakeLists.txt` → `pc/host/include/apxpc/version.hpp` | 同一环境变量注入 `APXPC_VERSION_STRING`（`#ifndef` 兜底本地默认值） |
| 安装向导显示版本 | `scripts/installer/AllPeriph.iss` | CI 传 `/DMyAppVersion=<tag>` |
| Release 标题与资产名 | `build.yml` release job | `github.ref_name` |
| App 检查更新 | `android/.../ui/UpdateChecker.kt` | 读 `releases/latest` 的 `tag_name`，与本机按**数值段**比较 |

**规则与边界**：

- **tag 必须 `v` + 数字开头**（`v206` / `v206.1`）。workflow 触发是 `v*`、gradle 与 CMake 校验是 `v[0-9]` —— 写成 `vnext` 会出包但版本号不注入（App 回退 `1.8`、PC 显示本地默认值），检查更新会失灵。
- **检查更新的比法**（`UpdateChecker.kt`）：去 `v` 前缀 → 按非数字切段 → 逐段比数值、缺段补 0。所以 `v206.1 > v206 > v205` 都成立，**热修号可用带后缀形式**。
- `releases/latest` 只认**已发布**的 Release：draft / prerelease 不参与（HTTP 404 → 检查更新报失败）。
- **versionCode 与更新检测无关**（比的是 tag 数值段），直装分发保持现状即可；若将来上架商店需每次发版 +1。
- 手动检查无频控窗口；静默检查（进「关于」触发）6 小时一次（匿名 API 60 次/小时）。
- 本地模拟发版版本号：`./gradlew :app:assembleRelease -PversionName=v206`。

---

## 四、常见失败排查

| 症状 | 看哪 / 怎么办 |
|---|---|
| 推了 tag 没出 Release | Actions → Build（该 tag 事件）→ `release` job；它 `needs: [pc, android]`，任一红都不发 |
| Release 出了但 App 说检查失败 | App 内手动「检查更新」→ 失败弹窗；多半是 tag 不是 `v+数字`（本机 `RELEASE_TAG` 为空比不出新旧）或 Release 为 draft |
| PC 关于页/卸载项版本号是旧的 | 非 tag 构建显示 `version.hpp` 默认值属**预期**；tag 构建看 configure 日志有无 `发版构建：APXPC_VERSION_STRING=` |
| 安装包没生成 | `release` job → Inno `ISCC` 步骤日志（`scripts/installer/AllPeriph.iss`） |
| 单测挂了但本机跑不了 | 单测以 CI 为准（Windows 中文路径下 Gradle test worker 有已知问题，见 `build.gradle.kts` 注释） |

---

## 五、回滚 / 热修

- **热修**：直接打下一个带后缀号（`v206.1`）→ 正常发版，检查更新按数值段可正确识别。
- **撤下坏版本**：GitHub → Releases → 该版本改 draft 或删 tag（`git push origin :refs/tags/v206`）；
  已升上去的用户不会被降级（比较只报「远端更新」）。
- **永远不要重打同名 tag 覆盖内容**——装了旧内容的用户会永远收不到修复，用新号。

---

*MIT 开源 · 零第三方依赖 · 所有通信仅限局域网，不上传任何数据*
