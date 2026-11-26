#include "apxpc/net/json.hpp"

// v-oss（2026-11-26）：解析与序列化委托给 vendored 的 nlohmann/json（third_party/nlohmann，
// MIT，单头文件 v3.12.0）。对外 Json 门面（json.hpp）保持不变，6 个调用点零改动。
//
// 换库理由（自查实锤的两处手搓缺陷）：
// 1. 手搓解析器不处理 \\uXXXX 代理对——emoji（如设备名）会被逐半编码成非法 UTF-8；
// 2. 手搓序列化数字用 %.6g，超过 6 位有效数字（如大文件字节数）会丢精度变科学计数法。
// nlohmann 两处都是经过海量项目验证的正确实现。正确性由 tests/json_test.cpp 兜底
// （代理对 / 大数往返 / 嵌套结构 / 解析失败语义）。
#include "nlohmann/json.hpp"

#include <string>
#include <string_view>

namespace apxpc::net {
namespace {

// nlohmann DOM → 本项目 Json 门面（递归转换）
Json fromNlohmann(const nlohmann::json& j) {
    switch (j.type()) {
        case nlohmann::json::value_t::null:            return Json(nullptr);
        case nlohmann::json::value_t::boolean:         return Json(j.get<bool>());
        case nlohmann::json::value_t::number_integer:
        case nlohmann::json::value_t::number_unsigned:
        case nlohmann::json::value_t::number_float:    return Json(j.get<double>());
        case nlohmann::json::value_t::string:          return Json(j.get<std::string>());
        case nlohmann::json::value_t::array: {
            Json arr = Json::makeArray();
            for (const auto& e : j) arr.push(fromNlohmann(e));
            return arr;
        }
        case nlohmann::json::value_t::object: {
            Json obj = Json::makeObject();
            for (auto it = j.begin(); it != j.end(); ++it) obj[it.key()] = fromNlohmann(it.value());
            return obj;
        }
        default: return Json();
    }
}

// 本项目 Json 门面 → nlohmann DOM（递归转换；仅 stringify 用）
nlohmann::json toNlohmann(const Json& j) {
    switch (j.type()) {
        case Json::Type::Null:   return nullptr;
        case Json::Type::Bool:   return nlohmann::json(j.asBool());
        case Json::Type::Number: return nlohmann::json(j.asNumber());
        case Json::Type::String: return nlohmann::json(j.asString());
        case Json::Type::Array: {
            nlohmann::json arr = nlohmann::json::array();
            for (const auto& e : j.asArray()) arr.push_back(toNlohmann(e));
            return arr;
        }
        case Json::Type::Object: {
            nlohmann::json obj = nlohmann::json::object();
            for (const auto& [k, v] : j.asObject()) obj[k] = toNlohmann(v);
            return obj;
        }
        default: return nullptr;
    }
}

}  // namespace

std::string Json::stringify(int indent) const {
    // indent 语义与手搓版一致：0 = 紧凑单行；>0 = 每层缩进 indent 个空格
    return toNlohmann(*this).dump(indent);
}

// 解析：失败返回 isNull()==true 且 err 被设置；成功返回根对象（与手搓版语义一致）
Json Json::parse(std::string_view text, std::string* err) {
    // allow_exceptions=false：坏输入返回 discarded 而不抛异常；err 语义由这里补齐
    nlohmann::json root = nlohmann::json::parse(text.begin(), text.end(), nullptr, false);
    if (root.is_discarded()) {
        if (err) *err = "JSON 解析失败";
        return Json();
    }
    if (err) err->clear();
    return fromNlohmann(root);
}

}  // namespace apxpc::net
