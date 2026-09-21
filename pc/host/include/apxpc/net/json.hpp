#pragma once
// 极简 JSON：零依赖，仅满足本项目配置读写与状态序列化所需子集
// 支持 object / array / string / number(double) / bool / null。
#include <map>
#include <memory>
#include <string>
#include <variant>
#include <vector>

namespace apxpc::net {

class Json {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };
    using ArrayT  = std::vector<Json>;
    using ObjectT = std::map<std::string, Json, std::less<>>;

    Json() : v_(nullptr) {}
    Json(std::nullptr_t) : v_(nullptr) {}
    Json(bool b) : v_(b) {}
    Json(double d) : v_(d) {}
    Json(int i) : v_(static_cast<double>(i)) {}
    Json(int64_t i) : v_(static_cast<double>(i)) {}
    Json(const char* s) : v_(std::string(s)) {}
    Json(std::string s) : v_(std::move(s)) {}
    Json(ArrayT a) : v_(std::move(a)) {}
    Json(ObjectT o) : v_(std::move(o)) {}

    static Json makeArray() { return Json(ArrayT{}); }
    static Json makeObject() { return Json(ObjectT{}); }

    Type type() const {
        if (std::holds_alternative<std::nullptr_t>(v_)) return Type::Null;
        if (std::holds_alternative<bool>(v_)) return Type::Bool;
        if (std::holds_alternative<double>(v_)) return Type::Number;
        if (std::holds_alternative<std::string>(v_)) return Type::String;
        if (std::holds_alternative<ArrayT>(v_)) return Type::Array;
        return Type::Object;
    }
    bool isNull()  const { return type() == Type::Null; }
    bool isObject() const { return type() == Type::Object; }
    bool isArray()  const { return type() == Type::Array; }
    bool isNumber() const { return type() == Type::Number; }
    bool isString() const { return type() == Type::String; }
    bool isBool()   const { return type() == Type::Bool; }

    bool   asBool(bool def = false) const { return isBool() ? std::get<bool>(v_) : def; }
    double asNumber(double def = 0) const { return isNumber() ? std::get<double>(v_) : def; }
    int    asInt(int def = 0) const { return static_cast<int>(asNumber(def)); }
    std::string asString(const std::string& def = {}) const {
        return isString() ? std::get<std::string>(v_) : def;
    }

    // 对象读写
    Json& operator[](const std::string& key) {
        if (!std::holds_alternative<ObjectT>(v_)) v_ = ObjectT{};
        return std::get<ObjectT>(v_)[key];
    }
    const Json* find(const std::string& key) const {
        if (!std::holds_alternative<ObjectT>(v_)) return nullptr;
        auto& o = std::get<ObjectT>(v_);
        auto it = o.find(key);
        return it == o.end() ? nullptr : &it->second;
    }
    bool has(const std::string& key) const { return find(key) != nullptr; }
    const ObjectT& asObject() const {
        static const ObjectT empty;
        return std::holds_alternative<ObjectT>(v_) ? std::get<ObjectT>(v_) : empty;
    }
    const ArrayT& asArray() const {
        static const ArrayT empty;
        return std::holds_alternative<ArrayT>(v_) ? std::get<ArrayT>(v_) : empty;
    }
    size_t size() const {
        if (isArray()) return asArray().size();
        if (isObject()) return asObject().size();
        return 0;
    }

    // 数组便捷
    Json& push(Json val) {
        if (!std::holds_alternative<ArrayT>(v_)) v_ = ArrayT{};
        auto& a = std::get<ArrayT>(v_);
        a.push_back(std::move(val));
        return a.back();
    }

    std::string stringify(int indent = 0) const;

    // 解析：失败返回 isNull()==true 且 err 被设置；成功返回根对象
    static Json parse(std::string_view text, std::string* err = nullptr);

private:
    std::variant<std::nullptr_t, bool, double, std::string, ArrayT, ObjectT> v_;
};

}  // namespace apxpc::net
