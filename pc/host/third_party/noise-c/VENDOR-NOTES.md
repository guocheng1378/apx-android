# noise-c 精简 vendor 说明

来源：https://github.com/rweather/noise-c（MIT 许可，正文见 `COPYING`）。
引入时间：2026-11-17（R2 通道加密，docs/REQ-无线安全配对.md §4.1 方案 C）。

本目录只保留了 `Noise_IKpsk2_25519_ChaChaPoly_SHA256` 组合实际需要的部分：

- `src/protocol/`：Noise 协议状态机（完整保留，上游未改一行）。
- `src/backend/ref/`：自包含参考加密后端（curve25519 / chachapoly / aesgcm /
  blake2b / blake2s / sha256 / sha512 / ed25519）。`dh-curve25519.c` 第 128 行起
  按条件 `#include` 进 donna 实现本体，curve25519 无需单独文件。
- `src/crypto/`：参考后端引用的原始实现（含 ed25519-donna 全套头）。
- `stubs.c`：curve448 / newhope 两个本工程不会用到的 DH 后端工厂打桩（说明见文件头）。
- 未保留：openssl / libsodium 后端、curve448 + goldilocks、newhope、tools / tests /
  examples（不参与本工程构建）。

构建：由 `pc/host/CMakeLists.txt` 以 `apxpc_noise` 静态库接入（C 语言，`-DUSE_OPENSSL=0`
等上游宏不需要 —— 后端选择由源文件清单决定）。协议名与 noise-java（Android 端，
`com.southernstorm.noise.protocol`，同作者）一致，两端按同一 Noise 规范实现。
