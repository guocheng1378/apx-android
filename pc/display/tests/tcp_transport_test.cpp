// TCP 传输离线自测：验证「单链路 + 帧解复用」——
// 服务端与客户端两条 ITransport 建立连接后，对端按 streamId 把字节流拆入
// control / touch 两条逻辑通道，互不串扰。
#ifdef _WIN32

#include "transport/frame_writer.hpp"
#include "transport/i_transport.hpp"

#include "apx/frame.h"
#include "common/crc32.hpp"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

using namespace apxdisp;

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } } while (0)

static std::vector<uint8_t> makeFrame(uint8_t streamId, const std::vector<uint8_t>& payload, uint32_t seq) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = streamId;
    h.flags = 0;
    h.headerExtWords = 0;
    h.payloadLen = static_cast<uint32_t>(payload.size()) + 4;
    h.seq = seq;
    std::vector<uint8_t> f(sizeof(h) + payload.size() + 4);
    std::memcpy(f.data(), &h, sizeof h);
    std::memcpy(f.data() + sizeof h, payload.data(), payload.size());
    // 与 FrameWriter 同口径（帧头之后），保证造出来的帧与真实发送的一致
    const uint32_t crc = crc32Of(f.data() + sizeof h, f.size() - sizeof h - 4);
    std::memcpy(f.data() + f.size() - 4, &crc, 4);
    return f;
}

// 轮询读取直到攒够 expect 字节或超时
static std::vector<uint8_t> readN(IChannel* ch, size_t expect, uint32_t perTryMs = 200, int tries = 30) {
    std::vector<uint8_t> out;
    uint8_t buf[1024];
    for (int i = 0; i < tries && out.size() < expect; ++i) {
        const size_t n = ch->read(buf, sizeof buf, perTryMs);
        if (n > 0) out.insert(out.end(), buf, buf + n);
    }
    return out;
}

int main() {
    const uint16_t port = 9531;

    auto server = createTcpTransport();
    auto client = createTcpTransport();
    CHECK(server && client);

    bool serverOk = false;
    std::thread srv([&] { serverOk = server->open(TransportSpec::tcpServer(port)); });
    std::this_thread::sleep_for(std::chrono::milliseconds(300));

    const bool clientOk = client->open(TransportSpec::tcpClient("127.0.0.1", port));
    CHECK(clientOk);
    srv.join();
    CHECK(serverOk);
    CHECK(server->isOpen());
    CHECK(client->isOpen());

    // 服务端发出两帧：一帧控制（streamId=3），一帧触控（streamId=2）
    const auto ctrl = FrameWriter::buildControlFrame(reinterpret_cast<const uint8_t*>("hello"), 5, 7);
    const std::vector<uint8_t> touch = makeFrame(apx::kStreamTouch, {1, 2, 3, 4, 5, 6}, 9);

    CHECK(server->controlChannel()->write(ctrl.data(), ctrl.size(), 1000) == ctrl.size());
    CHECK(server->controlChannel()->write(touch.data(), touch.size(), 1000) == touch.size());

    // 客户端应按 streamId 分别落进 control / touch 通道
    const auto gotCtrl  = readN(client->controlChannel(), ctrl.size());
    const auto gotTouch = readN(client->touchChannel(), touch.size());

    CHECK(gotCtrl.size() == ctrl.size());
    if (gotCtrl.size() == ctrl.size()) CHECK(std::memcmp(gotCtrl.data(), ctrl.data(), ctrl.size()) == 0);
    CHECK(gotTouch.size() == touch.size());
    if (gotTouch.size() == touch.size()) CHECK(std::memcmp(gotTouch.data(), touch.data(), touch.size()) == 0);

    client->close();
    server->close();

    if (g_fail == 0) { std::printf("tcp_transport_test: ALL PASS\n"); return 0; }
    std::printf("tcp_transport_test: %d FAILED\n", g_fail);
    return 1;
}

#else
int main() { return 0; }
#endif
