# noise-c 精简 vendor 说明

来源：https://github.com/rweather/noise-c（MIT 许可，正文见 `COPYING`）。
引入时间：2026-11-17（R2 通道加密，docs/REQ-无线安全配对.md §4.1 方案 C）。

本目录只保留了 `Noise_IKpsk2_25519_ChaChaPoly_SHA256` 组合实际需要的部分：

- `src/protocol/`：Noise 协议状态机（完整保留，上游未改一行）。
- `src/backend/ref/`：自包含参考加密后端（curve25519 / chachapoly / aesgcm /
  blake2b / blake2s / sha256 / sha512 / ed25519）。`dh-curve25519.c` 第 128 行起
  按条件 `#include` 进 donna 实现本体。
- `src/crypto/donna/`：curve25519-donna-c64.c / curve25519-donna.c——上文
  `dh-curve25519.c` 条件 `#include` 的实际本体（初次 vendor 漏拷，CI 抓出补齐）。
- `src/crypto/ed25519/`：ed25519-donna 全套头，及其引用的 curve25519-donna-*.h、
  curve25519-donna-helpers.h、modm-donna-*.h（同上初次漏拷补齐）；
  `src/crypto/blake2/blake2-endian.h` 随目录补齐（本工程未编 blake2，仅保完整）。
- `stubs.c`：curve448 / newhope 两个本工程不会用到的 DH 后端工厂打桩（说明见文件头）。
- 未保留：openssl / libsodium 后端、curve448 + goldilocks、newhope、tools / tests /
  examples（不参与本工程构建）。

构建：由 `pc/host/CMakeLists.txt` 以 `apxpc_noise` 静态库接入（C 语言，先
`enable_language(C)`；并定义 `ED25519_CUSTOMRANDOM` —— ed25519 批验证随机数
不引 OpenSSL，走 `protocol/rand_os.c` 的系统熵实现）。协议名与 noise-java
（Android 端，`com.southernstorm.noise.protocol`，同作者）一致，两端按同一
Noise 规范实现。
