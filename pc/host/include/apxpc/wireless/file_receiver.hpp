#pragma once
// 文件接收端（TCP 9512）：与手机端 `TvFileSender`、手机/TV 端 `TvFileReceiver` **逐字节同协议**。
//
//   head = u32 LE 名称长度 + 名称(UTF-8) + u64 LE 文件大小
//   随后是 size 字节原始数据（无握手、无令牌、无回执 —— 发送方写完即关连接）
//
// 为什么要有它：手机端的「文件传输」会连**对端 IP 的 9512**，而电脑端此前根本没有这个监听，
// 所以从手机往电脑传文件是「连上就断」；手机 → 手机 / 手机 → TV 才是通的。
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace apxpc::wireless {

class FileReceiver {
public:
    /// 收到**完整**一个文件后回调（在接收线程调用；要上 UI 就自己 post 过去）。
    /// 半截文件不留：中断时会把已写入的部分删掉，避免用户以为收到了完整文件。
    using FileCallback = std::function<void(const std::string& path, uint64_t size)>;

    FileReceiver();
    ~FileReceiver();
    FileReceiver(const FileReceiver&) = delete;
    FileReceiver& operator=(const FileReceiver&) = delete;

    /// 开始监听。dir 为空 = 默认目录（见 [defaultDir]）。端口被占用时返回 false（不抛异常）。
    bool start(uint16_t port, const std::string& dir, FileCallback onFile);
    void stop();
    bool running() const;

    std::string lastError() const;
    /// 最近一次成功接收的文件全路径（供界面显示；没有则空串）
    std::string lastFilePath() const;
    /// 默认落盘目录：用户「下载」目录下的 AllPeriph（不存在会被创建）
    static std::string defaultDir();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace apxpc::wireless
