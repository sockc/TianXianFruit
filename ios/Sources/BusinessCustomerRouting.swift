import SwiftUI

/// When a business row opens customer analytics, never infer a stall from
/// names, shared collectors, sales amounts or the first available store.
/// Accept only explicit sync ID or exact 64-bit Android foreign-key mapping.
struct TXBusinessCustomerRoute: Equatable {
    let date: String
    let storeSyncID: String
    let notice: String
    var hasVerifiedStore: Bool { !storeSyncID.isEmpty }
}

enum TXBusinessCustomerRouting {
    static func route(recordSyncID: String, ledger: ReadOnlySnapshot,
                      customer: CustomerReadSnapshot) -> TXBusinessCustomerRoute {
        guard let event = ledger.records["store_daily_record|" + recordSyncID],
              !event.isDeletion,
              let date = event.payload["date"]?.text,
              BusinessDates.parse(date) != nil else {
            return TXBusinessCustomerRoute(date: BusinessDates.key(Date()),
                storeSyncID: "", notice: "营业记录无法可靠定位；请在客户分析中选择日期")
        }

        let stores = ledger.records.values.filter {
            $0.tableName == "store" && !$0.isDeletion
        }

        let direct = event.payload["store_sync_id"]?.text ?? ""
        let rawID = AndroidNumericIdentity.exact(event.payload["store_id"])

        // Strongest link: explicit store sync UUID, when the store table either
        // confirms it or an independently synced customer receipt uses it.
        let validDirect: String? = {
            guard !direct.isEmpty, direct != "__unmatched__" else { return nil }
            let known = stores.contains { $0.syncId == direct }
            let independentlyAssigned = customer.assignedStores.contains(direct)
            return known || independentlyAssigned ? direct : nil
        }()

        // Alternate original Android Long ID via exactly one live store entity.
        // Never compare an imprecise Double > 2^53 with a real Android ID.
        let byOriginal: String? = {
            guard let rawID else { return nil }
            let matches = stores.filter {
                AndroidNumericIdentity.exact($0.payload["id"]) == rawID
            }
            return matches.count == 1 ? matches[0].syncId : nil
        }()

        if let direct = validDirect, let mapped = byOriginal, mapped != direct {
            return TXBusinessCustomerRoute(date: date, storeSyncID: "",
                notice: "营业记录的位置ID存在冲突；已仅带入日期，不能自动分配该摊位客流")
        }
        if let stableID = validDirect ?? byOriginal, !stableID.isEmpty {
            return TXBusinessCustomerRoute(date: date, storeSyncID: stableID,
                notice: "已按云端位置ID筛选；多位置共用收款人时，仍以账单中实际记录的归属为准")
        }
        return TXBusinessCustomerRoute(date: date, storeSyncID: "",
            notice: "无法从营业记录确认位置ID，已仅带入日期；多位置账单不可按名称猜测归属")
    }
}


/// A small business-list action; no additional account/write capabilities.
/// It only opens the existing read-only customer analytics with safe scope.
struct TXBusinessCustomerShortcut: View {
    @EnvironmentObject private var model: LedgerViewModel
    let recordSyncID: String
    private var resolved: TXBusinessCustomerRoute {
        TXBusinessCustomerRouting.route(
            recordSyncID: recordSyncID, ledger: model.snapshot,
            customer: model.customerSnapshot)
    }

    var body: some View {
        NavigationLink(destination: CustomerInsightsView(
            initialDate: resolved.date,
            initialStoreID: resolved.storeSyncID,
            contextNotice: resolved.notice)) {
            Text("客流 ›")
                .font(.caption.bold())
                .foregroundColor(TXTheme.primary)
                .padding(.horizontal, 4)
                .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("查看该营业记录的客流")
    }
}
