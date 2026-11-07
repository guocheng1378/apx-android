# Android 4.3 TV Legacy 构建链

电视端 4.3（API 18）兼容包的独立构建链，**与主仓 `android/`（AGP 9 / minSdk 23+）完全隔离**。

## 结构

| 路径 | 是什么 |
|---|---|
| `settings.gradle.kts` | AGP 7.4.2 + Kotlin 1.9.24（settings 级插件版本锁定） |
| `tv/` | TV 主模块：`minSdk 18`，UI 源码来自迁移前快照 `a769548`（PR #64 的 parent，纯 Views） |
| `shared/` | 构建壳：源码零拷贝引用 `../android/shared/src/main`（协议与 PC 端一致） |
| `materialize.sh` | 本地把 `git archive a769548 android/tv` 物化到 `legacy-snapshot/`；CI 用第二 checkout 等价 |

## 本地构建

```bash
cd android-legacy && bash materialize.sh
gradle :tv:assembleRelease   # 或 ./gradlew（需 Gradle 7.5.x + JDK 11）
```

## 为什么必须独立链

- 主链 AGP 9 + compileSdk 37，`minSdk` 下限被 AGP 与 androidx 生态卡在 21+；Miuix AAR 要 24+。
- 4.3 = Dalvik + API 18：Kotlin 字节码必须 jvmTarget 1.8，依赖树必须零 androidx 高版本——只有 AGP 7.4.x 能同时满足。

## 轮 2 待办（shared 的 API 18 审计）

- [ ] shared 源码里 `minSdk 19~23` 的调用点逐个 SDK_INT 分叉（编译不查，运行期才炸）
- [ ] 无障碍注入通道（`dispatchGesture` 是 API 24）在 4.3 上的降级口径
- [ ] 4.3 上 TLS socket 的显式启用（控制面若走 TLS）
