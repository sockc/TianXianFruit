import Foundation

struct WeatherLocation: Identifiable, Hashable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double

    var coordinateIsValid: Bool {
        latitude.isFinite && longitude.isFinite &&
        (-90...90).contains(latitude) && (-180...180).contains(longitude) &&
        latitude != 0 && longitude != 0
    }
}

struct WeatherForecastDay: Decodable, Identifiable {
    let date: String
    let textDay: String?
    let textNight: String?
    let codeDay: String?
    let codeNight: String?
    let tempMax: Double?
    let tempMin: Double?
    let precipitation: Double?
    let precipitationProbability: Double?

    var id: String { date }

    enum CodingKeys: String, CodingKey {
        case date
        case textDay = "text_day"
        case textNight = "text_night"
        case codeDay = "code_day"
        case codeNight = "code_night"
        case tempMax = "temp_max"
        case tempMin = "temp_min"
        case precipitation
        case precipitationProbability = "precipitation_probability"
    }
}

struct WeatherForecastResponse: Decodable {
    let daily: [WeatherForecastDay]
    let attribution: String?
    let updatedAt: String?
    enum CodingKeys: String, CodingKey {
        case daily, attribution
        case updatedAt = "updated_at"
    }
}

struct WeatherBusinessRisk: Identifiable {
    let day: WeatherForecastDay
    let rain: Bool
    let cooling: Bool
    let coolingDegrees: Double?

    var id: String { day.date }
    var isAlert: Bool { rain || cooling }
    var label: String {
        let parts = [
            rain ? "可能有雨" : nil,
            cooling ? "明显降温" : nil
        ].compactMap { $0 }
        return parts.isEmpty ? "暂无雨天或降温提醒" : parts.joined(separator: " · ")
    }
}

/// Uses the existing Android/server thresholds: >0.05 mm, >=60% rain
/// probability, rain label or a 300-399 weather code, and >=5 C day-over-day
/// MAXIMUM-temperature fall. Not a claim about the 16:00-24:00 shift.
enum WeatherWeekPolicy {
    static func risks(_ daily: [WeatherForecastDay], today: String,
                      probabilityThreshold: Double = 60,
                      coolingThreshold: Double = 5) -> [WeatherBusinessRisk] {
        let byDate = Dictionary(daily.map { ($0.date, $0) },
                                uniquingKeysWith: { _, incoming in incoming })
        guard let firstDay = BusinessDates.parse(today) else { return [] }
        let calendar = BusinessDates.calendar
        return (0..<7).compactMap { offset -> WeatherBusinessRisk? in
            guard let date = calendar.date(byAdding: .day, value: offset, to: firstDay) else { return nil }
            let key = BusinessDates.key(date)
            guard let day = byDate[key] else { return nil }
            let yesterday = calendar.date(byAdding: .day, value: -1, to: date)
                .map(BusinessDates.key)
            let previous = yesterday.flatMap { byDate[$0] }
            let rainText = (day.textDay ?? "") + " " + (day.textNight ?? "")
            let rainCode = [day.codeDay, day.codeNight].compactMap { Int($0 ?? "") }
                .contains(where: { (300...399).contains($0) })
            let hasRain = (day.precipitation ?? 0) > 0.05 ||
                (day.precipitationProbability ?? 0) >= probabilityThreshold ||
                rainText.contains("雨") || rainText.contains("雷暴") || rainCode
            let drop = previous?.tempMax.flatMap { before in
                day.tempMax.map { before - $0 }
            }
            let cooling = (drop ?? 0) >= coolingThreshold
            return WeatherBusinessRisk(day: day, rain: hasRain,
                                       cooling: cooling, coolingDegrees: drop)
        }
    }

    static func headline(_ risks: [WeatherBusinessRisk]) -> String? {
        let rainDays = risks.filter(\.rain).count
        let coolingDays = risks.filter(\.cooling).count
        if rainDays == 0 && coolingDays == 0 { return nil }
        var parts: [String] = []
        if rainDays > 0 { parts.append("\(rainDays)天可能有雨") }
        if coolingDays > 0 { parts.append("\(coolingDays)天明显降温") }
        return "未来7天：" + parts.joined(separator: "，")
    }
}

extension ReadOnlySnapshot {
    var weatherLocations: [WeatherLocation] {
        records.values.compactMap { event -> WeatherLocation? in
            guard event.tableName == "store", !event.isDeletion,
                  let lat = event.payload["latitude"]?.numberValue,
                  let lon = event.payload["longitude"]?.numberValue else { return nil }
            let row = WeatherLocation(id: event.syncId,
                                      name: event.payload["name"]?.text ?? "未命名位置",
                                      latitude: lat, longitude: lon)
            return row.coordinateIsValid ? row : nil
        }
        .sorted { $0.name < $1.name }
    }
}

extension JSONValue {
    var numberValue: Double? {
        switch self {
        case .number(let value): return value.isFinite ? value : nil
        case .string(let value): return Double(value).flatMap { $0.isFinite ? $0 : nil }
        default: return nil
        }
    }
}
