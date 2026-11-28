import XCTest
@testable import TianXianFruit

final class BusinessBillHistoryV4Tests: XCTestCase {
    private func uuid(_ n:Int) -> String { String(format:"00000000-0000-0000-0000-%012d",n) }
    private let day = "2026-11-26"
    private func fixture(amount:String = "13.25") throws -> (TXHistoryBillV4Prepared,[SyncEvent],[CustomerChangeEvent]) {
        let splits = """
        [{"partner_id":42,"amount":14.75,"wechat_income":10,"alipay_income":2.5,"cash_income":2.25},{"partner_id":9007199254740993,"amount":13,"wechat_income":4,"alipay_income":3,"cash_income":6}]
        """
        let sale = SyncEvent(tableName:"store_daily_record",syncId:"sale-fixture",rowVersion:7,operation:"UPSERT",payload:[
            "id":.number(99),"date":.string(day),"store_sync_id":.string(uuid(3)),"store_name":.string("A"),
            "revenue":.number(27.75),"profit":.number(26.75),"wechat_income":.number(14),
            "alipay_income":.number(5.5),"cash_income":.number(8.25),"expense":.number(1),
            "opening_stock_value":.number(0),"stock_left_value":.number(0),
            "expense_payer_id":.exactInteger(9007199254740993),"receipt_splits_json":.string(splits)])
        let records = [sale,SyncEvent(tableName:"partner",syncId:uuid(4),rowVersion:1,operation:"UPSERT",payload:["id":.number(42)]),
            SyncEvent(tableName:"partner",syncId:uuid(5),rowVersion:1,operation:"UPSERT",payload:["id":.exactInteger(9007199254740993)])]
        let natural = CustomerChangeEvent(kind:"NATURAL",key:day+"|"+uuid(4),revision:4,payload:[
            "date":.string(day),"store_sync_id":.string(uuid(3)),"partner_sync_id":.string(uuid(4)),
            "wechat":.number(10),"alipay":.number(2.5),"cash":.number(2.25),
            "saved_wechat":.number(10),"saved_alipay":.number(2.5),"saved_cash":.number(2.25),
            "deduct_wechat":.bool(true),"deduct_alipay":.bool(true),"deduct_cash":.bool(false)])
        let csv = "微信订单号,交易时间,交易类型,收/支,金额,交易状态\n2026112600000000000000001,2026-11-26 17:01:00,经营收款,收入,"+amount+",成功"
        let batch = try TXBillBatchPreview([TXBillCSV.parse(fileName:"微信.csv",data:Data(csv.utf8))])
        let plan = try TXHistoryBillPlan.prepare(record:sale,records:records,events:[natural],partner:uuid(4),
            batch:batch,channels:[.wechat],cursor:9)
        let account = Account(username:"seller",displayName:"seller",systemRole:nil)
        let book = CloudBook(id:uuid(1),name:"iOS_TEST_V40",role:"OWNER",deleted:nil,purged:nil)
        let result = try TXHistoryBillV4Prepared.prepare(plan:plan,account:account,book:book,operationID:try XCTUnwrap(UUID(uuidString:uuid(2))))
        XCTAssertThrowsError(try TXHistoryBillV4Prepared.prepare(plan:plan,account:account,
            book:CloudBook(id:book.id,name:"正式账本",role:"OWNER",deleted:nil,purged:nil)))
        return (result,records,[natural])
    }
    func testPythonCanonicalAndUnrelatedFields() throws {
        let (p,_,_) = try fixture()
        // Actual Python V40 EditRequest/canonical_hash with identical synthetic inputs.
        XCTAssertEqual(p.hash,"820b3fc0dd4dffb8473655075a97ccd71810ff0edf5f1238e772d4f5660c7660")
        XCTAssertEqual(p.expectedSale["revenue"],.number(31))
        XCTAssertEqual(p.expectedSale["profit"],.number(30))
        XCTAssertEqual(p.expectedSale["cash_income"],.number(8.25))
        XCTAssertEqual(p.expectedSale["expense_payer_id"],.exactInteger(9007199254740993))
        XCTAssertEqual(p.expectedSplits[1]["partner_id"],.exactInteger(9007199254740993))
        XCTAssertEqual(p.expectedSplits[1]["amount"],.number(13))
        XCTAssertEqual(p.expectedNatural["deduct_cash"],.bool(false))
        XCTAssertNoThrow(try p.validate())
        XCTAssertEqual(try JSONDecoder().decode(TXHistoryBillV4Prepared.self,from:JSONEncoder().encode(p)),p)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with:p.body(deviceID:"test-device")) as? [String:Any])
        XCTAssertEqual(body["protocol_version"] as? Int,4)
        XCTAssertEqual(body["payload_hash"] as? String,p.hash)
    }
    func testReceiptAndNoopNaturalVersion() throws {
        for value in ["13.25","10"] {
            let (p,records,events) = try fixture(amount:value)
            let r = p.request
            let manifest = r.bill.eventKeys.sorted().map { key -> [String:Any] in
                let parts = key.split(separator:"|",maxSplits:1).map(String.init)
                return ["kind":parts[0],"key":parts[1],"revision":1]
            }
            let revision = value == "10" ? 4 : 5
            var object:[String:Any] = ["status":"committed","operation_id":r.operation_id,"book_id":r.book_id,
                "record_sync_id":r.record_sync_id,"payload_hash":p.hash,"sync_cursor":20,"customer_cursor":15,
                "row_version":8,"natural_revision":revision,"previous_revision":0,"following_revision":0,"bill_events":manifest]
            func decode(_ body:[String:Any]) throws -> TXHistoryBillV4Receipt {
                try JSONDecoder().decode(TXHistoryBillV4Receipt.self,from:JSONSerialization.data(withJSONObject:body))
            }
            let valid = try decode(object)
            XCTAssertTrue(valid.matches(p))
            XCTAssertFalse(valid.verified(p,records:records,events:events,ledgerCursor:20,customerCursor:15))
            object["natural_revision"] = revision+1
            XCTAssertFalse(try decode(object).matches(p))
            object["natural_revision"] = revision;object["bill_events"] = Array(manifest.dropLast())
            XCTAssertFalse(try decode(object).matches(p))
        }
    }

    func testHistoryReservationAndScope() throws {
        let (p,_,_) = try fixture()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let url = folder.appendingPathComponent("v4.sqlite")
        let box = try TXHistoryBillV4Outbox(url:url)
        XCTAssertNil(try box.load(bookID:p.request.book_id,recordID:p.request.record_sync_id))
        try box.reserve(p)
        XCTAssertThrowsError(try box.reserve(p))
        XCTAssertEqual(try TXHistoryBillV4Outbox(url:url).load(bookID:p.request.book_id,recordID:p.request.record_sync_id),p)
        XCTAssertNil(try box.load(bookID:uuid(9),recordID:p.request.record_sync_id))
        let owner = Account(username:"seller",displayName:"seller",systemRole:nil)
        let other = Account(username:"other",displayName:"other",systemRole:nil)
        let book = CloudBook(id:p.request.book_id,name:"iOS_TEST_V40",role:"OWNER",deleted:nil,purged:nil)
        XCTAssertTrue(p.permits(owner,book))
        XCTAssertFalse(p.permits(other,book))
        XCTAssertFalse(p.permits(owner,CloudBook(id:book.id,name:"正式账本",role:"OWNER",deleted:nil,purged:nil)))
    }
}
