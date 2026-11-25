import XCTest
@testable import TianXianFruit

final class BusinessBillCSVTests: XCTestCase {
    func testMerchantOnlyAndDuplicateKeys() throws {
        let csv = """
        支付宝交易号,交易时间,交易分类,商品说明,收/支,金额(元),交易状态,备注,对方账号
        202611260001,2026-11-26 16:01:00,收入,经营码交易,收入,12.50,成功,,abc@example.com
        202611260001,2026-11-26 16:01:00,收入,经营码交易,收入,12.50,成功,,abc@example.com
        202611260002,2026-11-26 16:02:00,转账,普通转账,收入,100,成功,二维码收款,foo@example.com
        202611260003,2026-11-26 16:03:00,收入,普通收入,收入,100,成功,二维码收款,foo@example.com
        202611260004,2026-11-26 16:04:00,收入,经营码交易,收入,5,失败,,foo@example.com
        """
        let result = try TXBillCSV.parse(fileName: "微信备注.csv", data: Data(csv.utf8))
        XCTAssertEqual(result.platform, .alipay)
        XCTAssertEqual(result.rows.count, 1)
        XCTAssertEqual(result.rows[0].net, 1250)
        XCTAssertEqual(result.rows[0].dedupeKey, TXBillCSV.digest(Data("ALIPAY|202611260001|2026-11-26 16:01:00|12.5|0.0|成功".utf8)))
        XCTAssertEqual(result.rows[0].customerKey, TXBillCSV.digest(Data("ALIPAY|ID|abc@example.com".utf8)))
        XCTAssertEqual(result.duplicates, 1)
        XCTAssertEqual(result.ignored, 3)
    }

    func testRefundAndReview() throws {
        let csv = """
        微信订单号,交易时间,交易类型,收/支,金额,交易状态,用户标识
        0001,2026-11-26 23:59:00,经营收款,收入,15.00,成功,wx***1
        0002,2026-11-27 00:02:00,经营收款,收入,2,退款,wx***1
        ,2026-11-27 00:03:00,经营收款,收入,10,成功,
        """
        let result = try TXBillCSV.parse(fileName: "微信.csv", data: Data(csv.utf8))
        XCTAssertEqual(result.rows.map(\.reference), ["0001","0002"])
        XCTAssertEqual(result.rows.map(\.net), [1500,-200])
        XCTAssertEqual(result.rows.map(\.hour), [23,0])
        XCTAssertFalse(result.rows[0].reliableIdentity)
        XCTAssertEqual(result.review, 1)
    }

    func testQuotedCSVAndInvalidFile() throws {
        XCTAssertEqual(try TXBillCSV.table("a,b\r\n\"x,y\",\"a\"\"b\"\r\n"), [["a","b"],["x,y","a\"b"]])
        XCTAssertThrowsError(try TXBillCSV.table("a,\"unterminated"))
        XCTAssertThrowsError(try TXBillCSV.decode(Data([0x50,0x4b,0,0])))
    }

    func testConflictingTransactionFailsClosed() throws {
        let csv = """
        微信订单号,交易时间,交易类型,收/支,金额,交易状态
        0001,2026-11-26 16:00:00,经营收款,收入,1,成功
        0001,2026-11-26 16:00:00,经营收款,收入,2,成功
        """
        XCTAssertThrowsError(try TXBillCSV.parse(fileName: "微信.csv", data: Data(csv.utf8)))
    }
}
