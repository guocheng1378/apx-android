#include "apxpc/net/json.hpp"
#include <cstdio>
#include <sstream>

namespace apxpc::net {

namespace {

void escapeString(std::ostream& os, const std::string& s) {
    os << '"';
    for (char c : s) {
        switch (c) {
            case '"':  os << "\\\""; break;
            case '\\': os << "\\\\"; break;
            case '\n': os << "\\n"; break;
            case '\r': os << "\\r"; break;
            case '\t': os << "\\t"; break;
            case '\b': os << "\\b"; break;
            case '\f': os << "\\f"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    os << buf;
                } else {
                    os << c;
                }
        }
    }
    os << '"';
}

void stringifyImpl(std::ostream& os, const Json& j, int indent, int depth) {
    const std::string pad(depth * indent, ' ');
    const std::string pad1((depth + 1) * indent, ' ');
    const bool pretty = indent > 0;
    switch (j.type()) {
        case Json::Type::Null:   os << "null"; break;
        case Json::Type::Bool:   os << (j.asBool() ? "true" : "false"); break;
        case Json::Type::Number: {
            double d = j.asNumber();
            char buf[32];
            if (d == static_cast<double>(static_cast<int64_t>(d)))
                std::snprintf(buf, sizeof buf, "%.0f", d);
            else
                std::snprintf(buf, sizeof buf, "%.6g", d);
            os << buf;
            break;
        }
        case Json::Type::String: escapeString(os, j.asString()); break;
        case Json::Type::Array: {
            if (j.size() == 0) { os << "[]"; break; }
            os << '[';
            if (pretty) os << '\n';
            bool first = true;
            for (const auto& e : j.asArray()) {
                if (!first) { os << (pretty ? ',' : ','); }
                if (pretty) os << '\n' << pad1;
                stringifyImpl(os, e, indent, depth + 1);
                first = false;
            }
            if (pretty) os << '\n' << pad;
            os << ']';
            break;
        }
        case Json::Type::Object: {
            if (j.size() == 0) { os << "{}"; break; }
            os << '{';
            if (pretty) os << '\n';
            bool first = true;
            for (const auto& [k, v] : j.asObject()) {
                if (!first) { os << ','; }
                if (pretty) os << '\n' << pad1;
                escapeString(os, k);
                os << (pretty ? ": " : ":");
                stringifyImpl(os, v, indent, depth + 1);
                first = false;
            }
            if (pretty) os << '\n' << pad;
            os << '}';
            break;
        }
    }
}

// ---- 极简解析器 ----
struct Parser {
    std::string_view s;
    size_t i = 0;
    std::string* err;

    [[noreturn]] void fail(const std::string& m) {
        if (err) *err = m;
        throw std::runtime_error(m);
    }
    void skipWs() {
        while (i < s.size()) {
            char c = s[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ++i;
            else break;
        }
    }
    char peek() { skipWs(); return i < s.size() ? s[i] : '\0'; }
    Json parseValue() {
        skipWs();
        if (i >= s.size()) fail("意外结束");
        char c = s[i];
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return Json(parseString());
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') return parseNull();
        return parseNumber();
    }
    Json parseObject() {
        Json o = Json::makeObject();
        ++i; // {
        skipWs();
        if (i < s.size() && s[i] == '}') { ++i; return o; }
        while (true) {
            skipWs();
            if (i >= s.size() || s[i] != '"') fail("对象键应为字符串");
            std::string key = parseString();
            skipWs();
            if (i >= s.size() || s[i] != ':') fail("缺少 ':'");
            ++i;
            Json val = parseValue();
            o[key] = std::move(val);
            skipWs();
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == '}') { ++i; break; }
            fail("对象格式错误");
        }
        return o;
    }
    Json parseArray() {
        Json a = Json::makeArray();
        ++i; // [
        skipWs();
        if (i < s.size() && s[i] == ']') { ++i; return a; }
        while (true) {
            Json val = parseValue();
            a.push(std::move(val));
            skipWs();
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == ']') { ++i; break; }
            fail("数组格式错误");
        }
        return a;
    }
    std::string parseString() {
        ++i; // "
        std::string out;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') return out;
            if (c == '\\') {
                if (i >= s.size()) fail("转义截断");
                char e = s[i++];
                switch (e) {
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'u': {
                        if (i + 4 > s.size()) fail("\\u 截断");
                        unsigned code = 0;
                        for (int k = 0; k < 4; ++k) {
                            char h = s[i++];
                            code <<= 4;
                            if (h >= '0' && h <= '9') code |= h - '0';
                            else if (h >= 'a' && h <= 'f') code |= h - 'a' + 10;
                            else if (h >= 'A' && h <= 'F') code |= h - 'A' + 10;
                            else fail("非法 \\u");
                        }
                        if (code < 0x80) out += static_cast<char>(code);
                        else if (code < 0x800) {
                            out += static_cast<char>(0xC0 | (code >> 6));
                            out += static_cast<char>(0x80 | (code & 0x3F));
                        } else {
                            out += static_cast<char>(0xE0 | (code >> 12));
                            out += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
                            out += static_cast<char>(0x80 | (code & 0x3F));
                        }
                        break;
                    }
                    default: fail("非法转义");
                }
            } else {
                out += c;
            }
        }
        fail("字符串未闭合");
    }
    Json parseBool() {
        if (s.compare(i, 4, "true") == 0) { i += 4; return Json(true); }
        if (s.compare(i, 5, "false") == 0) { i += 5; return Json(false); }
        fail("非法布尔");
    }
    Json parseNull() {
        if (s.compare(i, 4, "null") == 0) { i += 4; return Json(nullptr); }
        fail("非法 null");
    }
    Json parseNumber() {
        size_t start = i;
        bool dot = false;
        while (i < s.size()) {
            char c = s[i];
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == 'e' || c == 'E')
                ++i;
            else if (c == '.') { dot = true; ++i; }
            else break;
        }
        if (i == start) fail("非法数字");
        double d = std::strtod(std::string(s.substr(start, i - start)).c_str(), nullptr);
        return Json(d);
    }
};

}  // namespace

std::string Json::stringify(int indent) const {
    std::ostringstream os;
    stringifyImpl(os, *this, indent, 0);
    return os.str();
}

Json Json::parse(std::string_view text, std::string* err) {
    try {
        Parser p{text, 0, err};
        Json root = p.parseValue();
        p.skipWs();
        if (p.i != text.size()) {
            if (err) *err = "根之后有多余内容";
            return Json(nullptr);
        }
        return root;
    } catch (const std::exception&) {
        return Json(nullptr);
    }
}

}  // namespace apxpc::net
