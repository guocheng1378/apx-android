/* ============================================================================
 * noise-c 精简 vendor 的补桩文件（2026-11-17，R2 通道加密）
 *
 * noise-c（rweather/noise-c，MIT）源文件按需精简进本目录：本工程只用
 * Noise_IKpsk2_25519_ChaChaPoly_SHA256 一个协议组合，因此只编 25519 /
 * SHA256/SHA512/BLAKE2 家族与 ChaChaPoly/AESGCM 参考实现。dhstate.c 的
 * 分发 switch 仍会引用 curve448 / newhope 两个 DH 后端的 _new() 工厂函数
 * （签名在 internal.h，缺符号链接不过）—— 这里打桩返回 NULL：运行时永远
 * 不会请求这两个 id（本工程协议名只含 25519），真被请求时 dhstate.c 会按
 * "分配失败" 报错，属预期防御行为。不为此修改上游任何文件。
 * ============================================================================
 */
#include "internal.h"

NoiseDHState *noise_curve448_new(void)  { return 0; }
NoiseDHState *noise_newhope_new(void)   { return 0; }
