import XCTest
@testable import TianXianFruit

final class TXProfitRuleDirectoryTests: XCTestCase {
    func testReadoutPreservesRulesAndFlagsDuplicates() {
        var s = ReadOnlySnapshot()
        s.apply([
            .init(tableName: "partner", syncId: "p", rowVersion: 1, operation: "UPSERT", payload: ["id": .number(1), "name": .string("甲"), "enabled": .number(1)]),
            .init(tableName: "profit_rule", syncId: "r", rowVersion: 1, operation: "UPSERT", payload: ["partner_id": .number(1), "percent": .number(60), "is_settlement_center": .number(1)]),
            .init(tableName: "profit_rule", syncId: "r2", rowVersion: 1, operation: "UPSERT", payload: ["partner_id": .number(1), "percent": .number(40)])
        ])
        let result = TXProfitRuleReadout.inspect(s)
        XCTAssertEqual(result.rows.count, 1)
        XCTAssertEqual(result.total, 60)
        XCTAssertTrue(result.rows[0].centerFlag)
        XCTAssertEqual(s.records.count, 3)
        XCTAssertFalse(result.warnings.isEmpty)
    }
    func testPartnerProposalIsRenameOnly() {
        var p = TXBasicEdit(table: "partner", sync_id: "p", expected_row_version: 3, name: "甲新")
        XCTAssertTrue(p.valid)
        p.enabled = 0
        XCTAssertFalse(p.valid)
        XCTAssertFalse(TXBasicEdit(table: "partner", sync_id: UUID().uuidString, expected_row_version: 0, name: "新").valid)
    }
}
