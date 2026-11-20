import Foundation
import SwiftUI

/// Read-only cloud detail for every ledger. Only an isolated test book can
/// enter the existing guarded business-correction workflow.
enum BusinessCorrectionEntryPolicy {
    static func allowed(book: CloudBook?, record: SyncEvent?,
                        events: [SyncEvent], cachedAuthorization: Bool) -> Bool {
        guard let book, let record,
              book.isAvailable,
              book.name.hasPrefix("iOS_TEST_"),
              ["OWNER", "EDITOR"].contains(book.role.uppercased()),
              !cachedAuthorization,
              record.tableName == "store_daily_record", !record.isDeletion,
              record.rowVersion > 0,
              let day = record.payload["date"]?.text,
              let storeID = record.payload["store_sync_id"]?.text,
              !day.isEmpty, !storeID.isEmpty,
              record.syncId == BusinessUploadPolicy.androidSyncID(
                  date: day, storeSyncID: storeID),
              let details = record.payload["receipt_splits_json"]?.text,
              !details.isEmpty,
              !TestBusinessSplitInput.from(record).isEmpty,
              !TestBusinessEditPolicy.hasDerivedSettlement(
                  date: day, events: events)
        else { return false }
        return true
    }
}

struct BusinessCloudDetailView: View {
    @EnvironmentObject private var model: LedgerViewModel

    let recordSyncID: String
    let bookID: String

    private var record: SyncEvent? {
        guard model.selectedBookID == bookID else { return nil }
        return model.snapshot.records["store_daily_record|" + recordSyncID]
    }

    private var canEdit: Bool {
        BusinessCorrectionEntryPolicy.allowed(
            book: model.selectedBook, record: record,
            events: Array(model.snapshot.records.values),
            cachedAuthorization: model.isUsingCachedAuthorization)
    }

    private func amount(_ key: String) -> String {
        let value = record?.payload[key]?.decimal ?? 0
        return value.formatted(.currency(code: "CNY"))
    }

    var body: some View {
        Form {
            if let record {
                Section(header: Text("云端营业记录")) {
                    LabeledContent("营业日期", value: record.payload["date"]?.text ?? "")
                    LabeledContent("营业位置", value: record.payload["store_name"]?.text ?? "未命名")
                    LabeledContent("云端记录版本", value: String(record.rowVersion))
                    LabeledContent("营业额", value: amount("revenue"))
                    LabeledContent("已保存利润", value: amount("profit"))
                }

                Section(header: Text("收款与成本")) {
                    LabeledContent("微信", value: amount("wechat_income"))
                    LabeledContent("支付宝", value: amount("alipay_income"))
                    LabeledContent("现金", value: amount("cash_income"))
                    LabeledContent("支出", value: amount("expense"))
                    LabeledContent("开摊库存估值", value: amount("opening_stock_value"))
                    LabeledContent("收摊库存估值", value: amount("stock_left_value"))
                    LabeledContent("实际开摊", value: record.payload["actual_start_time"]?.text ?? "")
                    LabeledContent("实际收摊", value: record.payload["actual_end_time"]?.text ?? "")
                }

                Section(header: Text("按收款人分配")) {
                    let splits = TestBusinessSplitInput.from(record)
                    if splits.isEmpty {
                        Text("未取得完整收款人明细；金额仍按云端原样显示，禁止在 iOS 猜测归属。")
                            .font(.caption).foregroundColor(.orange)
                    } else {
                        ForEach(splits) { row in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(row.partnerName).font(.subheadline.bold())
                                Text("微信 ¥\(row.wechat) · 支付宝 ¥\(row.alipay) · 现金 ¥\(row.cash)")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }

                Section(header: Text("营业记录更正")) {
                    if canEdit {
                        NavigationLink(destination: TestBusinessEditView(
                            focusRecordSyncID: recordSyncID)) {
                            Label("更正此笔营业", systemImage: "pencil.line")
                        }
                        Text("保持日期、位置、收款人身份及凌晨收款规则不变。提交前复核云端版本；已有利润分配或结算记录时禁止更正。")
                            .font(.caption2).foregroundColor(.secondary)
                    } else {
                        Label("此记录保持只读", systemImage: "lock.shield")
                            .foregroundColor(.secondary)
                        Text(model.selectedBook?.name.hasPrefix("iOS_TEST_") == true
                             ? "无完整收款明细、已有结算记录、离线授权或记录身份不符合要求时，不开放更正。"
                             : "正式账本编辑尚未开放；本页只读，不改变安卓的当日结算。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
            } else {
                Section {
                    Text("云端营业记录已变化、被删除或账本已切换，请刷新营业历史。")
                        .foregroundColor(.orange)
                }
            }
        }
        .navigationTitle("营业明细")
        .navigationBarTitleDisplayMode(.inline)
    }
}
