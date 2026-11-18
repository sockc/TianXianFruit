import Foundation
import UserNotifications

/// Device-local opt-in only. The app does not have a background scheduler
/// that can fetch changed forecasts while suspended; reminders use the LAST
/// explicitly fetched, dated seven-day forecast.
enum WeatherNotificationScheduler {
    static let prefix = "tianxian-ios-weather-"

    private static func currentIDs() async -> [String] {
        await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getPendingNotificationRequests { pending in
                continuation.resume(returning: pending.map(\.identifier)
                    .filter { $0.hasPrefix(prefix) })
            }
        }
    }

    static func clear() {
        UNUserNotificationCenter.current().getPendingNotificationRequests { pending in
            let matches = pending.map(\.identifier).filter { $0.hasPrefix(prefix) }
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: matches)
        }
    }

    /// Only the user can opt in. One advisory notification at 15:00 Shanghai
    /// time on each predicted risk day, before the regular 16:00 shift.
    /// It never claims a certain hour of rainfall.
    static func schedule(_ risks: [WeatherBusinessRisk]) async throws -> Int {
        let center = UNUserNotificationCenter.current()
        let accepted = try await center.requestAuthorization(options: [.alert, .sound])
        guard accepted else {
            throw WeatherNotificationError.denied
        }
        let old = await currentIDs()
        center.removePendingNotificationRequests(withIdentifiers: old)
        let calendar = BusinessDates.calendar
        let now = Date()
        var count = 0
        for risk in risks where risk.isAlert {
            guard let date = BusinessDates.parse(risk.day.date),
                  let fireAt = calendar.date(bySettingHour: 15, minute: 0, second: 0,
                                             of: date), fireAt > now else { continue }
            var matching = calendar.dateComponents([.year,.month,.day,.hour,.minute], from: fireAt)
            matching.timeZone = calendar.timeZone
            let content = UNMutableNotificationContent()
            content.title = "天鲜果业 · 经营天气提醒"
            content.body = "\(risk.day.date)：\(risk.label)。按当天最新天气再决定备货。"
            content.sound = .default
            let notification = UNNotificationRequest(
                identifier: prefix + risk.day.date,
                content: content,
                trigger: UNCalendarNotificationTrigger(dateMatching: matching, repeats: false))
            try await center.add(notification)
            count += 1
        }
        return count
    }
}

enum WeatherNotificationError: LocalizedError {
    case denied
    var errorDescription: String? {
        "未获得通知权限。请到 iPhone 设置中允许天鲜账本通知，或只在 App 内查看天气。"
    }
}
