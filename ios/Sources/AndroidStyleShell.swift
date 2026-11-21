import SwiftUI

enum TXTheme {
    static let primary = Color(red: 0.09, green: 0.49, blue: 0.30)
    static let header = Color(red: 0.91, green: 0.97, blue: 0.93)
}

/// Android-like adjacent-day control shared by business and purchasing.
struct TXDayBar: View {
    @Binding var date: Date
    let label: String
    var allowFuture = true
    private var today: String { BusinessDates.key(Date()) }
    var body: some View {
        HStack(spacing: 7) {
            Button { step(-1) } label: {
                Image(systemName: "chevron.left").frame(width: 36, height: 38)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 10))
            }.accessibilityLabel("上一日")
            Spacer(minLength: 0)
            DatePicker(label, selection: $date, displayedComponents: .date)
                .labelsHidden().datePickerStyle(.compact)
                .frame(minWidth: 130).accessibilityLabel(label)
            Spacer(minLength: 0)
            Button { step(1) } label: {
                Image(systemName: "chevron.right").frame(width: 36, height: 38)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 10))
            }
            .disabled(!allowFuture && BusinessDates.key(date) >= today)
            .accessibilityLabel("下一日")
        }
    }
    private func step(_ days: Int) {
        let next = BusinessDates.calendar.date(byAdding: .day, value: days, to: date) ?? date
        if allowFuture || BusinessDates.key(next) <= today { date = next }
    }
}

enum TXTestModule: String, CaseIterable, Identifiable {
    case businessCreate, purchaseCreate, businessEdit, purchaseRemark
    case inventoryEdit, profitWrite, customerImport, overnightReceipt, bookManagement
    var id: String { rawValue }
    var title: String {
        switch self {
        case .businessCreate: return "营业录入与上传"
        case .purchaseCreate: return "采购录入、计划完成"
        case .businessEdit: return "营业更正"
        case .purchaseRemark: return "采购备注更正"
        case .inventoryEdit: return "库存结转与损耗录入"
        case .profitWrite: return "利润分配与资金结算"
        case .customerImport: return "客户账单导入"
        case .overnightReceipt: return "凌晨收款"
        case .bookManagement: return "账本成员及权限管理"
        }
    }
    var icon: String {
        switch self {
        case .businessCreate: return "chart.bar.doc.horizontal"
        case .purchaseCreate: return "cart"
        case .businessEdit: return "pencil.line"
        case .purchaseRemark: return "note.text"
        case .inventoryEdit: return "shippingbox"
        case .profitWrite: return "arrow.left.arrow.right"
        case .customerImport: return "person.2"
        case .overnightReceipt: return "moon.stars"
        case .bookManagement: return "person.crop.rectangle.stack"
        }
    }
    var isImplemented: Bool {
        switch self {
        case .businessCreate, .purchaseCreate, .businessEdit, .purchaseRemark: return true
        default: return false
        }
    }
}

enum TXTestWriteState: Equatable {
    case productionReadOnly, restricted, live, separateVerification, serverUnavailable, preparationOnly, reserved
    var label: String {
        switch self {
        case .productionReadOnly: return "正式账本只读"
        case .restricted: return "无在线测试权限"
        case .live: return "服务器能力已开放"
        case .separateVerification: return "可进入独立验证"
        case .serverUnavailable: return "服务器未启用"
        case .preparationOnly: return "仅本机核对单 · 禁止提交"
        case .reserved: return "功能预留"
        }
    }
}

enum TXTestBookAccess {
    static func isCandidate(_ book: CloudBook?, account: Account?, offline: Bool) -> Bool {
        guard let book, account != nil, !offline, book.isAvailable,
              book.name.hasPrefix("iOS_TEST_") else { return false }
        return ["OWNER", "EDITOR", "SUPERADMIN"].contains(book.role)
    }
    static func state(_ feature: TXTestModule, book: CloudBook?, account: Account?,
                      offline: Bool, businessEnabled: Bool,
                      purchaseEnabled: Bool) -> TXTestWriteState {
        guard let book, book.name.hasPrefix("iOS_TEST_") else { return .productionReadOnly }
        guard isCandidate(book, account: account, offline: offline) else { return .restricted }
        if feature == .profitWrite { return .preparationOnly }
        if !feature.isImplemented { return .reserved }
        switch feature {
        case .businessCreate: return businessEnabled ? .live : .serverUnavailable
        case .purchaseCreate: return purchaseEnabled ? .live : .serverUnavailable
        case .businessEdit, .purchaseRemark: return .separateVerification
        default: return .reserved
        }
    }
}

/// Shared home shortcuts mirror the existing Android business vocabulary.
struct TXQuickOperations: View {
    @EnvironmentObject private var model: LedgerViewModel
    private let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("常用操作").font(.headline)
                Spacer()
                Text("安卓同名业务入口").font(.caption2).foregroundColor(.secondary)
            }
            LazyVGrid(columns: columns, spacing: 9) {
                NavigationLink(destination: BusinessLocalEntryView(
                    date: Date(), preferredStoreID: nil)) {
                    tile("营业录入", "square.and.pencil")
                }
                NavigationLink(destination: PurchaseWorkspaceView()) {
                    tile("采购管理", "cart")
                }
                NavigationLink(destination: TXInventoryShortcutView()) {
                    tile("库存", "shippingbox")
                }
                NavigationLink(destination: SettlementTableView()) {
                    tile("资金结算", "arrow.left.arrow.right")
                }
                NavigationLink(destination: CustomerInsightsView()) {
                    tile("客户分析", "person.2")
                }
                NavigationLink(destination: BusinessStatsView()) {
                    tile("经营统计", "chart.bar.xaxis")
                }
            }
            if TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                            offline: model.isUsingCachedAuthorization) {
                NavigationLink(destination: TXTestWorkbenchView()) {
                    Label("测试账本 · 全部模块与写入状态",
                          systemImage: "checkmark.shield")
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 10))
                }
            }
        }
    }
    private func tile(_ title: String, _ icon: String) -> some View {
        VStack(spacing: 7) {
            Image(systemName: icon).font(.title3).foregroundColor(TXTheme.primary)
            Text(title).font(.caption.bold()).foregroundColor(.primary)
                .lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, minHeight: 66)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

struct TXInventoryShortcutView: View {
    @EnvironmentObject private var model: LedgerViewModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("库存盘点 · 云端已保存", systemImage: "shippingbox").font(.headline)
                ForEach(model.snapshot.latestInventory) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name).font(.subheadline.bold())
                            Text(item.date + " · " + item.unit)
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text(item.remaining.formatted() + " " + item.unit)
                            .font(.subheadline.bold())
                    }
                    .padding(11)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 10))
                }
                if model.snapshot.latestInventory.isEmpty {
                    Text("尚无已同步库存").font(.caption).foregroundColor(.secondary)
                }
                NavigationLink(destination: TXReservedFeatureView(
                    title: "库存结转与损耗录入",
                    detail: "预留安卓库存录入习惯；在服务器提供安全事务前不写入成本和结转。")) {
                    Label("库存录入（预留）", systemImage: "pencil.line")
                }
                SyncFooter()
            }.padding(12)
        }.navigationTitle("库存").navigationBarTitleDisplayMode(.inline)
    }
}

struct TXReservedFeatureView: View {
    let title: String
    let detail: String
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Label("功能预留 · 暂不写入", systemImage: "clock.badge.exclamationmark")
                    .font(.headline).foregroundColor(.orange)
                Text(title).font(.title3.bold())
                Text(detail).font(.subheadline)
                Text("未来开放前须经过服务端权限、原子事务、幂等、并发冲突与安卓跨设备回读验证。本页不发起任何写入请求。")
                    .font(.caption).foregroundColor(.secondary)
            }.padding(18)
        }.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
}

struct TXTestWorkbenchView: View {
    @EnvironmentObject private var model: LedgerViewModel
    private var allowed: Bool {
        TXTestBookAccess.isCandidate(model.selectedBook, account: model.account,
                                     offline: model.isUsingCachedAuthorization)
    }
    private var businessEnabled: Bool {
        model.businessCommitCapabilities?.allowsWrite == true
    }
    private var purchaseEnabled: Bool {
        guard let caps = model.purchaseCommitCapabilities else { return false }
        return caps.version == 1 && caps.enabled && caps.atomic && caps.idempotency &&
               caps.feature == "purchase_atomic_commit_v1"
    }
    private func state(_ module: TXTestModule) -> TXTestWriteState {
        TXTestBookAccess.state(module, book: model.selectedBook,
                               account: model.account,
                               offline: model.isUsingCachedAuthorization,
                               businessEnabled: businessEnabled, purchaseEnabled: purchaseEnabled)
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 13) {
                Label("测试账本工作台", systemImage: "checkmark.shield")
                    .font(.title3.bold()).foregroundColor(TXTheme.primary)
                Text("已接入功能集中开放入口，不再逐个开启客户端开关。写入资格仍由服务端逐笔校验。")
                    .font(.caption).foregroundColor(.secondary)
                if !allowed {
                    Label("请切换在线可编辑的 iOS_TEST_ 测试账本；正式账本不允许写入。",
                          systemImage: "lock.shield.fill")
                        .font(.subheadline).foregroundColor(.orange)
                } else {
                    HStack {
                        Text(model.selectedBook?.name ?? "").font(.subheadline.bold())
                        Spacer()
                        Button("检查写入权限") {
                            Task { await model.refreshAllTestWriteCapabilities() }
                        }.font(.caption)
                    }
                    .padding(12)
                    .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 12))
                }
                ForEach(TXTestModule.allCases) { module in
                    NavigationLink(destination: destination(for: module)) {
                        HStack(spacing: 10) {
                            Image(systemName: module.icon)
                                .frame(width: 26).foregroundColor(TXTheme.primary)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(module.title).font(.subheadline.bold())
                                    .foregroundColor(.primary)
                                Text(state(module).label).font(.caption2)
                                    .foregroundColor(state(module) == .live ? TXTheme.primary : .secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }.disabled(!allowed)
                }
                Text("已实现的营业、采购仍采用专用原子接口；更正操作保留独立测试账号验证。结算操作仅可制作本机核对单，尚不能提交；其他功能保持预留。")
                    .font(.caption2).foregroundColor(.secondary)
            }.padding(12)
        }
        .navigationTitle("测试工作台")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if allowed { await model.refreshAllTestWriteCapabilities() }
        }
    }

    @ViewBuilder
    private func destination(for module: TXTestModule) -> some View {
        switch module {
        case .businessCreate:
            BusinessLocalEntryView(date: Date(), preferredStoreID: nil)
        case .purchaseCreate:
            PurchaseWorkspaceView()
        case .businessEdit:
            TestBusinessEditView()
        case .purchaseRemark:
            TXPurchaseRemarkPickerView()
        case .inventoryEdit:
            TXReservedFeatureView(title: module.title, detail: "库存盘点、损耗及跨日结转服务端接口尚未准备好。")
        case .profitWrite:
            TXSettlementTestWorkbenchView()
        case .customerImport:
            TXReservedFeatureView(title: module.title, detail: "客户账单导入及收款人归属需要事务接口与重复导入保护。")
        case .overnightReceipt:
            TXReservedFeatureView(title: module.title, detail: "凌晨收款写入需自然日、收款人唯一约束及重复扣减保护。")
        case .bookManagement:
            TXReservedFeatureView(title: module.title, detail: "成员和角色管理需要服务器授权及审计接口。")
        }
    }
}
