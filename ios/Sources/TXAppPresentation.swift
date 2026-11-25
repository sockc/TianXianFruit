import Foundation

enum TXAppPresentation {
    static func versionLabel(version: String?, build: String?) -> String {
        let v = version?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let b = build?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !v.isEmpty else { return "iOS · 版本未知" }
        return "iOS V" + v + (b.isEmpty ? "" : " · Build " + b)
    }
}
