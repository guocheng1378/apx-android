#include "apxpc/wireless/verify_code.hpp"

#include "apxpc/wireless/ctrl9511.hpp"  // setSystemClipboard：平台实现在 input_injector_*.cpp

#include "apxpc/log.hpp"

#include <cctype>
#include <string>

namespace apxpc::wireless {
namespace {

// 验证码位数口径：业界短信/邮件码普遍 4~8 位。1~3 位太短（误报风险高），
// 9 位以上基本是手机号（9~11 位）或订单流水号，都不是码。
constexpr int kMinCodeLen = 4;
constexpr int kMaxCodeLen = 8;
// 关键词与验证码之间允许隔开的字符总数上限（冒号、空格、书名号等）；
// 连同跳过的数字一起累计，防止「订单号 13800138000 …… 验证码」隔太远仍误配。
constexpr int kMaxGap = 10;

// 关键词按「具体 → 宽泛」排列：先试语义更强的词（验证码/校验码…），最后才落宽泛的
// "code"，避免宽泛词把 unrelated 数字截胡。中文按 UTF-8 字节精确匹配（多字节序列
// 不会误碰 ASCII 文本）；英文大小写不敏感且要求两侧词边界。
const char* const kKeywords[] = {
    "验证码", "校验码", "动态码", "随机码",
    "verification code", "security code", "auth code", "code",
};

bool isDigit(char c) { return c >= '0' && c <= '9'; }

bool isAsciiAlnum(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

/// 大小写不敏感的字节串比较。仅对 ASCII 字节做 tolower（>0x7F 的 UTF-8 字节保持原样，
/// 避免依赖 locale 的宽字符行为）；中文关键词因此天然大小写无关。
bool bytesEqualNoCase(const char* a, const char* b, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        const char ca = (a[i] >= 0x00 && a[i] <= 0x7F) ? (char)std::tolower((unsigned char)a[i]) : a[i];
        const char cb = (b[i] >= 0x00 && b[i] <= 0x7F) ? (char)std::tolower((unsigned char)b[i]) : b[i];
        if (ca != cb) return false;
    }
    return true;
}

/// 在 s 的 [from, ...) 找关键词（ASCII 词要求词边界，避免 "Coder" 里截出 "code"）。
/// 命中返回关键词之后一个字符的下标，找不到返回 npos。
size_t findKeyword(const std::string& s, size_t from, const std::string& kw) {
    const bool asciiKw = (unsigned char)kw[0] < 0x80; // 中文 UTF-8 首字节 >= 0x80
    for (size_t i = from; i + kw.size() <= s.size(); ++i) {
        if (!bytesEqualNoCase(s.data() + i, kw.data(), kw.size())) continue;
        if (asciiKw) {
            if (i > 0 && isAsciiAlnum(s[i - 1])) continue;
            if (i + kw.size() < s.size() && isAsciiAlnum(s[i + kw.size()])) continue;
        }
        return i + kw.size();
    }
    return std::string::npos;
}

/// 从关键词之后扫描验证码：跳过至多 kMaxGap 个字符后，取第一段 kMinCodeLen~kMaxCodeLen
/// 位的连续数字段；过短（1~3 位）或过长（9+ 位，手机号/流水号）的数字段整段跳过继续找，
/// 以覆盖「银行短信同时报尾号和验证码」这类一封多条数字的通知。
bool extractAfterKeyword(const std::string& s, size_t from, std::string* out) {
    size_t i = from;
    int gap = 0; // 自关键词起累计消耗的字符数（含跳过的数字段）
    while (i < s.size() && gap <= kMaxGap) {
        if (!isDigit(s[i])) { ++i; ++gap; continue; }
        size_t j = i;
        while (j < s.size() && isDigit(s[j])) ++j;
        const size_t len = j - i;
        if (len >= (size_t)kMinCodeLen && len <= (size_t)kMaxCodeLen) {
            out->assign(s, i, len);
            return true;
        }
        gap += (int)len; // 非码数字段也占 gap 额度：从关键词到真码不能隔太远
        i = j;
    }
    return false;
}

/// 兜底：正文去掉所有空白后恰好是 4~8 位纯数字。这类通知往往整条只有一个码、
/// 没有任何关键词（如通知正文就是「123456」），关键词扫描会全部落空。
bool tryWholeTextFallback(const std::string& text, std::string* out) {
    std::string stripped;
    stripped.reserve(text.size());
    for (char c : text)
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '\f' && c != '\v')
            stripped.push_back(c);
    if (stripped.size() < (size_t)kMinCodeLen || stripped.size() > (size_t)kMaxCodeLen) return false;
    for (char c : stripped)
        if (!isDigit(c)) return false;
    *out = stripped;
    return true;
}

/// 先纯扫描后落副作用：遍历所有关键词的所有出现位置，命中一个就收工；
/// 关键词全军覆没再走整条正文的兜底。title+text 拼接扫描——部分 App 把码放标题。
bool findCode(const std::string& title, const std::string& text, std::string* out) {
    const std::string combined = title + "\n" + text;
    for (const char* kw : kKeywords) {
        size_t from = 0;
        while (true) {
            const size_t after = findKeyword(combined, from, kw);
            if (after == std::string::npos) break;
            if (extractAfterKeyword(combined, after, out)) return true;
            from = after; // 该处没配到码（全是手机号/流水号）：从关键词尾继续找下一处
        }
    }
    return tryWholeTextFallback(text, out);
}

} // namespace

bool copyIfVerifyCode(const std::string& title, const std::string& text, std::string* codeOut) {
    std::string code;
    if (!findCode(title, text, &code)) return false;

    // 命中：写入本机剪贴板。失败不假装成功——返回 false，调用方不追加「已复制」提示。
    if (!setSystemClipboard(code)) {
        APX_LOGW("验证码命中但写入剪贴板失败：{}", code);
        return false;
    }
    if (codeOut) *codeOut = code;
    return true;
}

} // namespace apxpc::wireless
