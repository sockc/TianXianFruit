import XCTest
@testable import TianXianFruit

final class BusinessDraftWorkflowTests: XCTestCase {
    private var lines: [BusinessCollectorDraftLine] {
        [BusinessCollectorDraftLine(id: "line-1", partnerSyncID: "partner-a",
                                    wechat: "110", alipay: "210", cash: "0")]
    }

    private var draft: BusinessLocalDraft {
        BusinessLocalDraft(
            id: "0da44ae8-f534-4adf-8aa0-4c3f720572fd",
            date: "2026-11-21", storeID: "store-a", storeName: "宁德",
            collectors: lines, startTime: "16:00", endTime: "00:10",
            note: "测试", expense: "5", openingStock: "100",
            closingStock: "40", updatedAt: Date())
    }

    private func event(_ rowVersion: Int64 = 1) -> SyncEvent {
        SyncEvent(tableName: "store_daily_record", syncId: "stable-record",
                  rowVersion: rowVersion, operation: "UPSERT",
                  payload: ["date": .string("2026-11-21"),
                            "store_sync_id": .string("store-a"),
                            "store_name": .string("宁德")])
    }

    private func attempt(_ phase: BusinessCommitAttempt.Phase) -> BusinessCommitAttempt {
        let p = BusinessCommitProposal(
            protocolVersion: 1,
            bookID: "25266a78-a274-4fe0-9665-b097ee41376f",
            account: "test-operator", operationID: draft.id,
            date: draft.date, storeSyncID: draft.storeID, storeName: draft.storeName,
            collectors: [], actualStartTime: "16:00", actualEndTime: "00:10",
            wechat: "110.00", alipay: "210.00", cash: "0.00",
            totalRevenue: "320.00", expense: "5.00",
            openingStockValue: "100.00", stockLeftValue: "40.00",
            profit: "255.00", payloadHash: "test-only")
        return BusinessCommitAttempt(proposal: p, account: p.account,
                                     bookID: p.bookID, phase: phase,
                                     receipt: nil, updatedAt: Date())
    }

    func testConfirmedDraftNeverAppearsAsUnuploaded() {
        let confirmed = BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [event()],
            attempt: attempt(.confirmed))
        XCTAssertEqual(confirmed, .confirmed)
        XCTAssertFalse(confirmed.needsAttention)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [], attempt: attempt(.unknown)),
                       .waitingForResult)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [event()], attempt: nil),
                       .cloudRecordExists)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [], attempt: nil), .localOnly)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [], attempt: nil, outboxReadable: false),
                       .unsafeToRead)
    }

    func testConfirmedBusinessMissingOrChangedOnCloudNeedsAuditNotRepost() {
        let confirmed = attempt(.confirmed)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [], attempt: confirmed,
            cloudSnapshotReady: false), .confirmed,
            "Offline or no fresh cloud snapshot must not accuse the server of deletion")
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [], attempt: confirmed,
            cloudSnapshotReady: true), .confirmedRemoteMissing)
        XCTAssertTrue(BusinessDraftWorkflowPolicy.CloudState
            .confirmedRemoteMissing.needsAttention)
        // A known receipt with a row that no longer matches the original
        // amount must not be mislabeled as still matching the submission.
        var saved = confirmed
        saved.receipt = BusinessCommitReceipt(
            status: "committed", operationID: confirmed.proposal.operationID,
            bookID: confirmed.proposal.bookID,
            recordSyncID: "stable-record", collectorSyncIDs: [],
            payloadHash: confirmed.proposal.payloadHash, syncCursor: 1)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.status(
            draft: draft, events: [event()], attempt: saved,
            cloudSnapshotReady: true), .confirmedRemoteChanged)
        XCTAssertTrue(BusinessDraftWorkflowPolicy.CloudState
            .confirmedRemoteChanged.needsAttention)
    }

    func testReloadedSavedDraftIsRecognizedButUnsavedEditIsNot() {
        XCTAssertTrue(BusinessDraftWorkflowPolicy.matchesSaved(
            draft: draft, date: draft.date, storeID: draft.storeID,
            lines: lines, startTime: "16:00", endTime: "00:10",
            expense: "5", opening: "100", closing: "40", note: "测试"))
        var edited = lines
        edited[0].alipay = "211"
        XCTAssertFalse(BusinessDraftWorkflowPolicy.matchesSaved(
            draft: draft, date: draft.date, storeID: draft.storeID,
            lines: edited, startTime: "16:00", endTime: "00:10",
            expense: "5", opening: "100", closing: "40", note: "测试"))
        XCTAssertFalse(BusinessDraftWorkflowPolicy.matchesSaved(
            draft: draft, date: draft.date, storeID: draft.storeID,
            lines: lines, startTime: "16:00", endTime: "00:10",
            expense: "6", opening: "100", closing: "40", note: "测试"))
    }

    func testPartialDraftNeverBypassesCloudCompletenessGate() {
        XCTAssertFalse(BusinessDraftWorkflowPolicy.readyForCloud(
            lines: lines, expense: "", opening: "", closing: "",
            startTime: "16:00", endTime: "00:10"))
        XCTAssertFalse(BusinessDraftWorkflowPolicy.readyForCloud(
            lines: lines, expense: "5", opening: "100", closing: "40",
            startTime: "16:00", endTime: ""))
        XCTAssertTrue(BusinessDraftWorkflowPolicy.readyForCloud(
            lines: lines, expense: "5", opening: "100", closing: "40",
            startTime: "16:00", endTime: "00:10"))
    }

    func testMissingFieldsNeverTreatsAbsentActualTimesAsValidCloudEntry() {
        let missing = BusinessDraftWorkflowPolicy.missingCloudFields(
            lines: lines, expense: "5", opening: "100", closing: "40",
            startTime: "", endTime: "")
        XCTAssertEqual(missing, ["实际营业时间"])
        XCTAssertFalse(BusinessDraftWorkflowPolicy.readyForCloud(
            lines: lines, expense: "5", opening: "100", closing: "40",
            startTime: "", endTime: ""))
        XCTAssertTrue(BusinessDraftWorkflowPolicy.missingCloudFields(
            lines: lines, expense: "5", opening: "100", closing: "40",
            startTime: "16:00", endTime: "00:10").isEmpty)
        XCTAssertEqual(BusinessDraftWorkflowPolicy.missingCloudFields(
            lines: lines, expense: "", opening: "", closing: "40",
            startTime: "16:00", endTime: "").sorted(),
            ["实际营业时间", "开摊库存", "支出"].sorted())
    }

    func testPreviewMatchesBusinessAtomicFormulaAndNeverGuessesMissingStock() {
        let p = BusinessDraftWorkflowPolicy.preview(
            lines: lines, expense: "5", opening: "100", closing: "40")
        XCTAssertEqual(p?.revenue, "320.00")
        XCTAssertEqual(p?.profit, "255.00")
        XCTAssertNil(BusinessDraftWorkflowPolicy.preview(
            lines: lines, expense: "", opening: "100", closing: "40"))
        XCTAssertNil(BusinessDraftWorkflowPolicy.preview(
            lines: lines, expense: "5", opening: "", closing: "40"))
    }
}
