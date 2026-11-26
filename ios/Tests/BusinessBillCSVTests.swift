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

    func testDeflatedZIPAndXLSXLongTextIdentifier() throws {
        let zip = try XCTUnwrap(Data(base64Encoded: "UEsDBBQAAAAIAGlpel2DQqCLeAAAAH8AAAAKAAAAd2VjaGF0LmNzdnu6b92T/QtfrFv0tHfq0/7tOk92LXk2Y/Kz6dteTt8C5TzfuPvpvG6dZ1O26T+bsl7nZfvEl4vmwuS6tj1raOQyMDAw1DEyMDLTNTTUNTJTMDSzMjAAIp3nu/tfTFgK1PpszT6QCU9bl+oYGumZ6jzrmPC0az4XAFBLAQIUAxQAAAAIAGlpel2DQqCLeAAAAH8AAAAKAAAAAAAAAAAAAACAAQAAAAB3ZWNoYXQuY3N2UEsFBgAAAAABAAEAOAAAAKAAAAAAAA=="))
        let parsed = try TXBillCSV.parse(fileName: "账单.zip", data: zip)
        XCTAssertEqual(parsed.rows.first?.reference, "0001")
        XCTAssertEqual(parsed.rows.first?.net, 1250)
        XCTAssertEqual(parsed.fileHash, TXBillCSV.digest(zip))
        var corrupted = zip
        corrupted[50] ^= 0xff
        XCTAssertThrowsError(try TXBillArchive.unzip(corrupted))
        let xlsx = try XCTUnwrap(Data(base64Encoded: "UEsDBBQAAAAIAGlpel3OnpgTDQAAAAsAAAAPAAAAeGwvd29ya2Jvb2sueG1ssynPL8pOys/P1rcDAFBLAwQUAAAACABpaXpdVq23HDIBAADNBAAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbKWU0UrDMBSGX6X0fkuTYS8kC6izPoBPMEbBomzQhe3WXmyOXbjJlPVmKOJuHcLowL7O0q5vYdwEK5xTHYZALpLvHM7/QXi35V+2L1xXCr49anVZF9xvdQVvGH7VPKKmIaum17zymu659E3BvbbgUiT3i3UcqtfZ+v0lCSdqtOJEFyGft6TxRR9j9A5KplE2XQLcSTGnBv30LQa4Gsaph76aBJvFMglvAe4UnzIielIAcTAku7nLnmcAcVY8VDqMkuvgJ0e2InI2GFKCWcymlNkWsCgkpqhQidISsw1qH1qW3pAfDNd5qd4cMoMRaTzajOfpU7BLAZKzdzMHIygrH0Bm0A6DsRo+/uKkop93Cg0wTjr58Cv/Cx/D8fAx4g/h793MwQgsfLQDGj7JfVXk+wf7AFBLAQIUAxQAAAAIAGlpel3OnpgTDQAAAAsAAAAPAAAAAAAAAAAAAACAAQAAAAB4bC93b3JrYm9vay54bWxQSwECFAMUAAAACABpaXpdVq23HDIBAADNBAAAGAAAAAAAAAAAAAAAgAE6AAAAeGwvd29ya3NoZWV0cy9zaGVldDEueG1sUEsFBgAAAAACAAIAgwAAAKIBAAAAAA=="))
        let sheet = try TXBillCSV.parse(fileName: "账单.xlsx", data: xlsx)
        XCTAssertEqual(sheet.rows.count, 1)
        XCTAssertEqual(sheet.rows.first?.reference, "20261126000000000000000000001")
        XCTAssertEqual(sheet.review, 1)
    }

    func testCrossFileDeduplicationAndAttribution() throws {
        let text = """
        微信订单号,交易时间,交易类型,收/支,金额,交易状态
        0001,2026-11-26 16:00:00,经营收款,收入,12.5,成功
        """
        let first = try TXBillCSV.parse(fileName: "微信.csv", data: Data(text.utf8))
        let second = try TXBillCSV.parse(fileName: "微信.csv", data: Data((text + "\n").utf8))
        let batch = try TXBillBatchPreview([first, first, second])
        XCTAssertEqual(batch.files.count, 2)
        XCTAssertEqual(batch.rows.count, 1)
        XCTAssertEqual(batch.duplicateFiles, 1)
        XCTAssertEqual(batch.duplicateRows, 1)
        let row = try XCTUnwrap(batch.rows.first)
        XCTAssertEqual(batch.sourceHashes[row.id], Set([first.fileHash,second.fileHash]))
        let receipt = CustomerChangeEvent(kind: "RECEIPT", key: row.id, revision: 1, payload: [
            "platform": .string("WECHAT"), "dedupe_key": .string(row.dedupeKey),
            "transaction_ref": .string(row.reference), "trade_time": .string(row.tradeTime),
            "amount": .number(12.5), "refund_amount": .number(0), "net_amount": .number(12.5),
            "source_file_hash": .string(first.fileHash)])
        let owner = CustomerChangeEvent(kind: "COLLECTOR", key: "WECHAT|" + first.fileHash, revision: 1, payload: [
            "platform": .string("WECHAT"), "file_hash": .string(first.fileHash), "partner_sync_id": .string("p1")])
        let link = CustomerChangeEvent(kind: "SOURCE_LINK", key: row.id + "|" + second.fileHash, revision: 1, payload: [
            "platform": .string("WECHAT"), "dedupe_key": .string(row.dedupeKey), "file_hash": .string(second.fileHash)])
        let all = Set([first.fileHash, second.fileHash])
        XCTAssertEqual(TXBillCloudAudit(events: []).match(row, selectedPartner: "p1", fileHashes: all), .new)
        XCTAssertEqual(TXBillCloudAudit(events: [receipt,owner]).match(row, selectedPartner: "p1", fileHashes: all), .sameCollector)
        XCTAssertEqual(TXBillCloudAudit(events: [receipt,owner,link]).match(row, selectedPartner: "p1", fileHashes: all), .unknownCollector)
        XCTAssertEqual(TXBillCloudAudit(events: [receipt,owner]).match(row, selectedPartner: "p2", fileHashes: all), .otherCollector)
        var dead = receipt.payload; dead["deleted"] = .bool(true)
        XCTAssertEqual(TXBillCloudAudit(events: [CustomerChangeEvent(kind: "RECEIPT", key: row.id, revision: 2, payload: dead)]).match(row, selectedPartner: "p1", fileHashes: all), .deleted)
        let changed = try TXBillCSV.parse(fileName: "微信.csv", data: Data(text.replacingOccurrences(of: "12.5", with: "13.5").utf8))
        XCTAssertThrowsError(try TXBillBatchPreview([first, changed]))
    }
}
