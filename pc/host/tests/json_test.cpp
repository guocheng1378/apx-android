// JSON 门面单测（离线可跑）：v-oss（2026-11-26）解析/序列化换 nlohmann 后钉住行为。
//
// 覆盖手搓版实锤踩过的坑 + 门面语义：
//   1) \uXXXX 代理对 —— 手搓版把 emoji 逐半编码成非法 UTF-8，换库后必须正确
//   2) 大数往返 —— 手搓版 %.6g 会把大文件字节数变科学计数法，换库后必须无损
//   3) 嵌套结构、解析失败语义（isNull + err）、stringify 缩进
#include "apxpc/net/json.hpp"

#include <cstdio>
#include <string>

using apxpc::net::Json;

static int g_fail = 0;

#define CHECK(cond) do { \
    if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } \
} while (0)

#define CHECK_EQ(a, b) do { \
    const auto _a = (a); const auto _b = (b); \
    if (!(_a == _b)) { \
        std::printf("FAIL %s:%d  %s == %s\n", __FILE__, __LINE__, #a, #b); \
        std::printf("     实际 [%s]\n     期望 [%s]\n", _a.c_str(), _b.c_str()); \
        ++g_fail; \
    } \
} while (0)

// 1) \uXXXX 代理对：😊 = \uD83D\uDE00（UTF-8: F0 9F 98 80）
static void testSurrogatePairs() {
    std::string err;
    Json j = Json::parse("{\"name\":\"\\ud83d\\ude00 ok\"}", &err);
    CHECK(err.empty());
    CHECK_EQ(j.find("name")->asString(), std::string("\xF0\x9F\x98\x80 ok"));
    // 中文（BMP 内多字节）也要原样还原
    Json cn = Json::parse("\"\\u4e2d\\u6587\"", &err);
    CHECK(err.empty());
    CHECK_EQ(cn.asString(), std::string("\xE4\xB8\xAD\xE6\x96\x87"));
}

// 2) 大数：手搓版 %.6g 会把 12345678901 变 1.23457e+10
static void testBigNumbers() {
    Json j = Json::parse("{\"size\":12345678901,\"small\":42,\"frac\":0.5}");
    // size 是数字字段，asString() 恒为空串——直接取 asNumber 转整型比对
    CHECK_EQ(std::to_string((long long)j.find("size")->asNumber()), std::string("12345678901"));
    CHECK_EQ(std::to_string((long long)j.find("small")->asNumber()), std::string("42"));
    // 序列化往返：大数不得变科学计数法
    Json m = Json::makeObject();
    m["size"] = 12345678901.0;
    std::string out = m.stringify();
    CHECK(out.find("12345678901") != std::string::npos);
    CHECK(out.find("e+") == std::string::npos);
    // 往返再解析，值不变
    std::string err;
    Json back = Json::parse(out, &err);
    CHECK(err.empty());
    CHECK_EQ(std::to_string((long long)back.find("size")->asNumber()), std::string("12345678901"));
}

// 3) 嵌套结构 + stringify 缩进 + 解析失败语义
static void testNestingAndErrors() {
    Json m = Json::makeObject();
    m["ver"] = 1;
    Json files = Json::makeArray();
    Json f = Json::makeObject();
    f["id"] = std::string("a.png");
    f["size"] = 2048.0;
    files.push(f);
    m["files"] = files;

    std::string out = m.stringify(2);
    std::string err;
    Json back = Json::parse(out, &err);
    CHECK(err.empty());
    CHECK_EQ(std::to_string((long long)back.find("ver")->asNumber()), std::string("1"));
    CHECK(back.find("files")->size() == 1);
    CHECK_EQ(back.find("files")->asArray()[0].find("id")->asString(), std::string("a.png"));

    // 解析失败：isNull + err 非空（门面契约，见 json.hpp）
    Json bad = Json::parse("{\"a\":", &err);
    CHECK(bad.isNull());
    CHECK(!err.empty());

    // 紧凑序列化：无换行
    std::string compact = m.stringify();
    CHECK(compact.find('\n') == std::string::npos);
}

int main() {
    testSurrogatePairs();
    testBigNumbers();
    testNestingAndErrors();
    if (g_fail == 0) std::printf("json_test: all passed\n");
    return g_fail == 0 ? 0 : 1;
}
