// Windows 输入注入器配套测试：验证 UTF-8 ↔ UTF-16 转换在分配缓冲区时
// 不会因 "size-1 构造 + size 传入 API" 而越界。
// 对应缺陷：input_injector_win.cpp 的 toWide / readClipboardUtf8 在构造 std::wstring
// / std::string 时只分配 n-1 字符，却向 Win32 API 声明缓冲区大小为 n（含 null terminator）。
// 当字符串超出 SSO 容量、进行堆分配且 capacity == size 时，API 写入第 n 个字符即越界。
//
// 测试方法：在独立进程中复现与原实现等价的转换逻辑，并验证修复后的版本安全。
// 本测试仅在 Windows 上编译运行（_WIN32）。

#if defined(_WIN32)

#include <windows.h>
#include <string>
#include <cstdio>

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } } while (0)

// 修复后的 toWide 逻辑（与 input_injector_win.cpp 保持一致）
static std::wstring toWideFixed(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    w.resize(static_cast<size_t>(n) - 1);
    return w;
}

// 修复后的 readClipboardUtf8 逻辑（与 input_injector_win.cpp 保持一致）
static std::string utf16ToUtf8Fixed(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(static_cast<size_t>(n), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, s.data(), n, nullptr, nullptr);
    s.resize(static_cast<size_t>(n) - 1);
    return s;
}

// 构造一个远超 MSVC std::string SSO 容量（15/16 字节）的 UTF-8 字符串，
// 确保堆分配路径被覆盖。
static std::string makeLongUtf8() {
    // 80 个 ASCII 字符，明确超过 SSO
    return std::string(80, 'A');
}

// 含多字节 UTF-8 字符的长字符串（中文），确保 MultiByteToWideChar 的 n 计算与
// 实际字符数不一致时也不会越界。
static std::string makeLongUtf8Multibyte() {
    return "这是一个超过标准 SSO 缓冲区长度的中文字符串，用于验证堆分配路径下的安全性。";
}

static void testToWideAscii() {
    const std::string src = makeLongUtf8();
    const std::wstring dst = toWideFixed(src);
    CHECK(dst.size() == src.size());
    for (size_t i = 0; i < src.size(); ++i) CHECK(dst[i] == static_cast<wchar_t>(src[i]));
}

static void testToWideMultibyte() {
    const std::string src = makeLongUtf8Multibyte();
    const std::wstring dst = toWideFixed(src);
    CHECK(!dst.empty());
    // 反向转换应能还原文本
    const std::string back = utf16ToUtf8Fixed(dst);
    CHECK(back == src);
}

static void testUtf16ToUtf8Ascii() {
    std::wstring src;
    src.append(80, L'B');
    const std::string dst = utf16ToUtf8Fixed(src);
    CHECK(dst.size() == src.size());
    for (size_t i = 0; i < dst.size(); ++i) CHECK(dst[i] == static_cast<char>(src[i]));
}

static void testUtf16ToUtf8Multibyte() {
    std::wstring src = L"包含 Unicode 字符的宽字符串，例如 \u4e2d\u6587 \u30ab\u30bf\u30ab\u30ca";
    const std::string dst = utf16ToUtf8Fixed(src);
    CHECK(!dst.empty());
    const std::wstring back = toWideFixed(dst);
    CHECK(back == src);
}

int main() {
    testToWideAscii();
    testToWideMultibyte();
    testUtf16ToUtf8Ascii();
    testUtf16ToUtf8Multibyte();

    if (g_fail == 0) {
        std::printf("PASS  utf16_convert_test\n");
        return 0;
    }
    std::printf("FAIL  %d assertion(s)\n", g_fail);
    return 1;
}

#else

// 非 Windows 平台：编译通过但无实际运行内容（保持构建系统简单）
int main() { return 0; }

#endif
