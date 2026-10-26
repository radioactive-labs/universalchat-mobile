import Foundation

/// A JSON value that keeps an object's keys in the order the server wrote
/// them, which is the order a flow's choices are meant to be shown in.
/// Foundation's parser does not promise that.
enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([(String, JSON)])

    static func == (lhs: JSON, rhs: JSON) -> Bool {
        switch (lhs, rhs) {
        case (.null, .null): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.number(a), .number(b)): return a == b
        case let (.string(a), .string(b)): return a == b
        case let (.array(a), .array(b)): return a == b
        case let (.object(a), .object(b)): return a.count == b.count && zip(a, b).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
        default: return false
        }
    }

    subscript(key: String) -> JSON {
        if case .object(let entries) = self { return entries.first { $0.0 == key }?.1 ?? .null }
        return .null
    }

    var orderedEntries: [(String, JSON)] {
        if case .object(let entries) = self { return entries }
        return []
    }

    var array: [JSON] {
        if case .array(let items) = self { return items }
        return []
    }

    /// A non-blank string, or a number written as one.
    var string: String? {
        switch self {
        case .string(let s): return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : s
        case .number(let n): return n.rounded() == n ? String(Int(n)) : String(n)
        default: return nil
        }
    }

    var int: Int? {
        if case .number(let n) = self { return Int(n) }
        return nil
    }

    var bool: Bool {
        if case .bool(let b) = self { return b }
        return false
    }

    var stringMap: [String: String] {
        Dictionary(orderedEntries.compactMap { key, value in value.string.map { (key, $0) } }, uniquingKeysWith: { a, _ in a })
    }

    var csv: [String] {
        (string ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// The JSON text for a value built in code.
    var serialized: String {
        switch self {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .number(let n): return n.rounded() == n && abs(n) < 1e15 ? String(Int(n)) : String(n)
        case .string(let s): return JSON.quote(s)
        case .array(let items): return "[" + items.map(\.serialized).joined(separator: ",") + "]"
        case .object(let entries): return "{" + entries.map { JSON.quote($0.0) + ":" + $0.1.serialized }.joined(separator: ",") + "}"
        }
    }

    private static func quote(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where scalar.value < 0x20: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }

    /// Parses JSON text. Nil when it is not JSON.
    static func parse(_ data: Data) -> JSON? {
        var parser = Parser(bytes: Array(data))
        guard let value = parser.value() else { return nil }
        parser.skipWhitespace()
        return parser.atEnd ? value : nil
    }

    private struct Parser {
        let bytes: [UInt8]
        var at = 0

        var atEnd: Bool { at >= bytes.count }

        mutating func skipWhitespace() {
            while at < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[at]) { at += 1 }
        }

        mutating func value() -> JSON? {
            skipWhitespace()
            guard at < bytes.count else { return nil }
            switch bytes[at] {
            case UInt8(ascii: "{"): return object()
            case UInt8(ascii: "["): return list()
            case UInt8(ascii: "\""): return string().map(JSON.string)
            case UInt8(ascii: "t"): return literal("true", .bool(true))
            case UInt8(ascii: "f"): return literal("false", .bool(false))
            case UInt8(ascii: "n"): return literal("null", .null)
            default: return number()
            }
        }

        mutating func literal(_ word: String, _ result: JSON) -> JSON? {
            let w = Array(word.utf8)
            guard at + w.count <= bytes.count, Array(bytes[at..<at + w.count]) == w else { return nil }
            at += w.count
            return result
        }

        mutating func number() -> JSON? {
            let start = at
            while at < bytes.count, "+-0123456789.eE".utf8.contains(bytes[at]) { at += 1 }
            guard let text = String(bytes: bytes[start..<at], encoding: .utf8), let n = Double(text) else { return nil }
            return .number(n)
        }

        mutating func string() -> String? {
            at += 1
            var out = [UInt8]()
            while at < bytes.count {
                let byte = bytes[at]
                at += 1
                switch byte {
                case UInt8(ascii: "\""):
                    return String(decoding: out, as: UTF8.self)
                case UInt8(ascii: "\\"):
                    guard at < bytes.count else { return nil }
                    let escape = bytes[at]
                    at += 1
                    switch escape {
                    case UInt8(ascii: "n"): out.append(0x0A)
                    case UInt8(ascii: "t"): out.append(0x09)
                    case UInt8(ascii: "r"): out.append(0x0D)
                    case UInt8(ascii: "b"): out.append(0x08)
                    case UInt8(ascii: "f"): out.append(0x0C)
                    case UInt8(ascii: "u"):
                        guard var code = hex4() else { return nil }
                        // A surrogate pair spells one character in two escapes.
                        if (0xD800..<0xDC00).contains(code), at + 1 < bytes.count, bytes[at] == UInt8(ascii: "\\"), bytes[at + 1] == UInt8(ascii: "u") {
                            at += 2
                            guard let low = hex4() else { return nil }
                            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                        }
                        out.append(contentsOf: Array(String(UnicodeScalar(code).map(Character.init) ?? "\u{FFFD}").utf8))
                    default: out.append(escape)
                    }
                default:
                    out.append(byte)
                }
            }
            return nil
        }

        mutating func hex4() -> UInt32? {
            guard at + 4 <= bytes.count, let text = String(bytes: bytes[at..<at + 4], encoding: .ascii), let code = UInt32(text, radix: 16) else { return nil }
            at += 4
            return code
        }

        mutating func list() -> JSON? {
            at += 1
            var items = [JSON]()
            skipWhitespace()
            if at < bytes.count, bytes[at] == UInt8(ascii: "]") { at += 1; return .array(items) }
            while true {
                guard let item = value() else { return nil }
                items.append(item)
                skipWhitespace()
                guard at < bytes.count else { return nil }
                if bytes[at] == UInt8(ascii: ",") { at += 1; continue }
                if bytes[at] == UInt8(ascii: "]") { at += 1; return .array(items) }
                return nil
            }
        }

        mutating func object() -> JSON? {
            at += 1
            var entries = [(String, JSON)]()
            skipWhitespace()
            if at < bytes.count, bytes[at] == UInt8(ascii: "}") { at += 1; return .object(entries) }
            while true {
                skipWhitespace()
                guard at < bytes.count, bytes[at] == UInt8(ascii: "\""), let key = string() else { return nil }
                skipWhitespace()
                guard at < bytes.count, bytes[at] == UInt8(ascii: ":") else { return nil }
                at += 1
                guard let item = value() else { return nil }
                entries.append((key, item))
                skipWhitespace()
                guard at < bytes.count else { return nil }
                if bytes[at] == UInt8(ascii: ",") { at += 1; continue }
                if bytes[at] == UInt8(ascii: "}") { at += 1; return .object(entries) }
                return nil
            }
        }
    }
}
