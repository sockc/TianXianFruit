import XCTest
import UIKit
@testable import TianXianFruit

final class WeatherAndReportTests: XCTestCase {
    private func day(_ date: String, maximum: Double? = nil,
                     rainMM: Double? = nil, probability: Double? = nil,
                     text: String = "") -> WeatherForecastDay {
        WeatherForecastDay(date: date, textDay: text, textNight: "",
                           codeDay: "999", codeNight: "999", tempMax: maximum,
                           tempMin: 20, precipitation: rainMM,
                           precipitationProbability: probability)
    }

    func testRainAndCoolingMatchesAndroidSevenDayPolicy() {
        let result = WeatherWeekPolicy.risks([
            day("2026-11-18", maximum: 30, rainMM: 0, probability: 60),
            day("2026-11-19", maximum: 25, rainMM: 0, probability: 0),
            day("2026-11-20", maximum: 25, rainMM: 0, probability: 0),
            day("2026-11-21", maximum: 24, rainMM: 0.06, probability: 0),
            day("2026-11-22", maximum: 22, rainMM: 0, probability: 0),
            day("2026-11-23", maximum: 21, rainMM: 0, probability: 0),
            day("2026-11-24", maximum: 22, rainMM: 0, probability: 0),
            day("2026-11-25", maximum: 18, rainMM: 7, probability: 99)
        ], today: "2026-11-18")
        XCTAssertEqual(result.count, 7)
        XCTAssertTrue(result[0].rain)
        XCTAssertFalse(result[0].cooling)
        XCTAssertTrue(result[1].cooling)
        XCTAssertEqual(result[1].coolingDegrees, 5)
        XCTAssertTrue(result[3].rain)
        XCTAssertFalse(result[2].rain)
        XCTAssertEqual(WeatherWeekPolicy.headline(result), "未来7天：2天可能有雨，1天明显降温")
    }

    func testMissingForecastDaysAreNotImputed() {
        let rows = WeatherWeekPolicy.risks([
            day("2026-11-18", maximum: 26),
            day("2026-11-22", maximum: 20)
        ], today: "2026-11-18")
        XCTAssertEqual(rows.count, 2)
        XCTAssertFalse(rows[1].cooling)
        XCTAssertNil(WeatherWeekPolicy.headline(rows))
    }

    func testDecodeExistingServerWeatherOverviewFields() throws {
        let payload = """
        {"daily":[
          {"date":"2026-11-18","text_day":"多云","text_night":"雨","code_day":"101",
           "code_night":"305","temp_max":29.0,"temp_min":22,
           "precipitation":0.8,"precipitation_probability":70}
         ],"attribution":"服务端天气源","updated_at":"2026-11-18T04:00:00Z"}
        """
        let data = try JSONDecoder().decode(WeatherForecastResponse.self,
                                             from: Data(payload.utf8))
        XCTAssertEqual(data.daily.count, 1)
        XCTAssertEqual(data.daily[0].tempMax, 29)
        XCTAssertTrue(WeatherWeekPolicy.risks(data.daily, today: "2026-11-18")[0].rain)
        XCTAssertEqual(data.attribution, "服务端天气源")
    }

    func testStoreWithoutCoordinatesNeverCreatesWeatherLocation() {
        var state = ReadOnlySnapshot()
        state.apply([
            SyncEvent(tableName: "store", syncId: "no-coords", rowVersion: 1,
                      operation: "UPSERT", payload: ["name": .string("夏茅")]),
            SyncEvent(tableName: "store", syncId: "with-coords", rowVersion: 1,
                      operation: "UPSERT", payload: [
                        "name": .string("龙归"),
                        "latitude": .number(23.29),
                        "longitude": .string("113.27")
                      ]),
            SyncEvent(tableName: "store", syncId: "invalid", rowVersion: 1,
                      operation: "UPSERT", payload: [
                        "name": .string("未知"),
                        "latitude": .number(0),
                        "longitude": .number(0)
                      ])
        ])
        XCTAssertEqual(state.weatherLocations.map(\.id), ["with-coords"])
        XCTAssertTrue(state.weatherLocations[0].coordinateIsValid)
    }

    private func event(_ table: String, _ sync: String,
                       _ fields: [String: JSONValue]) -> SyncEvent {
        SyncEvent(tableName: table, syncId: sync, rowVersion: 1,
                  operation: "UPSERT", payload: fields)
    }

    func testLocationReportDoesNotAllocateAllBookPurchasesToSingleStore() {
        var state = ReadOnlySnapshot()
        state.apply([
            event("store_daily_record", "business-1", [
                "date": .string("2026-11-18"), "store_name": .string("龙归"),
                "revenue": .number(100), "profit": .number(40)
            ]),
            event("store_daily_record", "business-2", [
                "date": .string("2026-11-18"), "store_name": .string("夏茅"),
                "revenue": .number(300), "profit": .number(90)
            ]),
            event("purchase_order", "po", [
                "date": .string("2026-11-18"), "buyer_name": .string("采购人"),
                "total_cost": .number(120)
            ])
        ])
        let all = OperatingReport.lines(snapshot: state, book: "家庭账本",
                                        start: "2026-11-18", end: "2026-11-18")
        XCTAssertTrue(all.contains { $0.title == "当天营业额合计" && $0.value.contains("400") })
        XCTAssertEqual(all.filter { $0.title == "龙归  营业" }.count, 1)
        XCTAssertEqual(all.filter { $0.title == "夏茅  营业" }.count, 1)

        let filtered = OperatingReport.lines(snapshot: state, book: "家庭账本",
                                             start: "2026-11-18", end: "2026-11-18",
                                             storeName: "龙归")
        XCTAssertTrue(filtered.contains { $0.title.contains("采购金额（全账本") })
        XCTAssertTrue(filtered.contains { $0.title == "当天营业额合计" && $0.value.contains("100") })
        XCTAssertFalse(filtered.contains { $0.title == "夏茅  营业" })
    }

    func testPDFAndPNGOutputFilesAreReadable() throws {
        let lines: [OperatingReportLine] = [
            OperatingReportLine("天鲜果业 · 经营汇总", style: .title),
            OperatingReportLine("2026-11-18", style: .section),
            OperatingReportLine("当天营业额", "¥1,800.00", style: .normal)
        ]
        let pdf = try OperatingReport.pdf(lines)
        defer { try? FileManager.default.removeItem(at: pdf) }
        XCTAssertGreaterThan(try Data(contentsOf: pdf).count, 250)
        let document = CGPDFDocument(pdf as CFURL)
        XCTAssertNotNil(document)
        XCTAssertEqual(document?.numberOfPages, 1)

        let png = try OperatingReport.png(lines)
        defer { try? FileManager.default.removeItem(at: png) }
        XCTAssertNotNil(UIImage(contentsOfFile: png.path))
    }

    func testLongImageRejectsAndPDFPaginates() throws {
        let lines = (0..<125).map { i in
            OperatingReportLine("日期序号 " + String(i), "¥200", style: .normal)
        }
        XCTAssertThrowsError(try OperatingReport.png(lines))
        let pdf = try OperatingReport.pdf(lines)
        defer { try? FileManager.default.removeItem(at: pdf) }
        let document = CGPDFDocument(pdf as CFURL)
        XCTAssertNotNil(document)
        XCTAssertGreaterThan(document?.numberOfPages ?? 0, 1)
    }
}
