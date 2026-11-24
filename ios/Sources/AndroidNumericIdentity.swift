import Foundation

/// Android/SQLite row IDs are signed Int64, not IEEE-754 doubles. JSONValue
/// decodes IDs > 2^53-1 as exactInteger; NEVER round an imprecise Double or
/// infer a missing identity. All cross-table purchase links use this helper.
enum AndroidNumericIdentity {
    static func exact(_ raw: JSONValue?) -> Int64? {
        guard let raw else { return nil }
        switch raw {
        case .exactInteger(let id):
            return id > 0 ? id : nil
        case .number(let value):
            guard value.isFinite, value > 0,
                  value <= 9_007_199_254_740_991,
                  value.rounded() == value else { return nil }
            return Int64(value)
        case .string(let text):
            guard !text.isEmpty, text.count <= 24,
                  text.allSatisfy({ $0 >= "0" && $0 <= "9" }),
                  let value = Int64(text), value > 0
            else { return nil }
            return value
        default: return nil
        }
    }

    static func same(_ left: JSONValue?, _ right: JSONValue?) -> Bool {
        guard let a = exact(left), let b = exact(right) else { return false }
        return a == b
    }

    static func unique(_ source: SyncEvent, in records: [SyncEvent]) -> Bool {
        guard let numeric = exact(source.payload["id"]) else { return false }
        let matches = records.filter {
            $0.tableName == source.tableName && !$0.isDeletion &&
            exact($0.payload["id"]) == numeric
        }
        return matches.count == 1 && matches[0].syncId == source.syncId
    }
}
