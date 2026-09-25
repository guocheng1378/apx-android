#pragma once
// 文件发送（TCP 9512）：与手机端 `TvFileSender`、手机/TV 端 `TvFileReceiver` **逐字节同协议**。
//
//   head = u32 LE 名称长度 + 名称(UTF-8) + u64 LE 文件大小
//   随后是文件原始字节（无握手、无令牌、无回执 —— 发完即关连接）
//
// 接收侧：手机端 / TV 端都落在各自应用私有目录 `<外部文件>/APX/`（无需存储权限）。
#include <cstdint>
#include <functional>
#include <string>

namespace apxpc::wireless {

/// @param host       目标 IP（= 当前受控端；由面板的会话快照给出）
/// @param port       固定 9512
/// @param filePath   本机待发文件（UTF-8 路径）
/// @param onProgress 0..100 进度回调（在调用线程；可传空）
/// @return 是否发送完成（失败原因写入 [lastSendError]）
bool sendFile(const std::string& host, uint16_t port, const std::string& filePath,
              const std::function<void(int)>& onProgress = {});

/// 最近一次发送失败的原因（成功时清空）
std::string lastSendError();

}  // namespace apxpc::wireless
