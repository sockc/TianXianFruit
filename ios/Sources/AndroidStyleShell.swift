import SwiftUI

enum TXTheme {
    static let primary = Color(red: 0.09, green: 0.49, blue: 0.30)
    // Follows the device appearance; the former hard-coded pale header
    // created a bright strip above the otherwise dark dashboard.
    static let header = Color(UIColor.secondarySystemGroupedBackground)
}

/// Shared operating-day presentation. Explicit confirmation preserves guarded
/// bindings used by editors; selecting a calendar day never submits a record.
enum TXWorkDayPolicy {
    static func shifted(_ date: Date, days: Int, allowFuture: Bool, now: Date = Date()) -> Date {
        let next = BusinessDates.calendar.date(byAdding: .day, value: days, to: date) ?? date
        return !allowFuture && BusinessDates.key(next) > BusinessDates.key(now) ? date : next
    }

    static func swipeDays(horizontal: CGFloat, vertical: CGFloat) -> Int {
        guard abs(horizontal) >= 55, abs(horizontal) > abs(vertical) * 1.5 else { return 0 }
        return horizontal < 0 ? 1 : -1
    }

    static func clamped(_ date: Date, allowFuture: Bool, now: Date = Date()) -> Date {
        !allowFuture && BusinessDates.key(date) > BusinessDates.key(now) ? now : date
    }
}

struct TXDayBar: View {
    @Binding var date: Date
    let label: String
    var allowFuture = true
    @State private var showCalendar = false
    @State private var calendarDraft = Date()

    private var displayDate: String {
        let formatter = DateFormatter()
        formatter.calendar = BusinessDates.calendar
        formatter.timeZone = BusinessDates.calendar.timeZone
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy年M月d日 EEE"
        return formatter.string(from: date)
    }

    var body: some View {
        HStack(spacing: 5) {
            Button { step(-1) } label: {
                Image(systemName: "chevron.left").frame(width: 32, height: 40)
            }.accessibilityLabel("上一日")
            Button {
                calendarDraft = TXWorkDayPolicy.clamped(date, allowFuture: allowFuture)
                showCalendar = true
            } label: {
                HStack(spacing: 5) {
                    Spacer(minLength: 0)
                    Text(displayDate).font(.subheadline.bold()).lineLimit(1).minimumScaleFactor(0.8)
                    Spacer(minLength: 0)
                    Image(systemName: "calendar").font(.caption)
                }
                .frame(maxWidth: .infinity, minHeight: 40)
                .padding(.horizontal, 5)
                .background(TXTheme.header, in: RoundedRectangle(cornerRadius: 8))
            }
            .accessibilityLabel(label + " " + displayDate)
            .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { value in
                let days = TXWorkDayPolicy.swipeDays(horizontal: value.translation.width,
                                                     vertical: value.translation.height)
                if days != 0 { step(days) }
            })
            Button { step(1) } label: {
                Image(systemName: "chevron.right").frame(width: 32, height: 40)
            }
            .disabled(!allowFuture && BusinessDates.key(date) >= BusinessDates.key(Date()))
            .accessibilityLabel("下一日")
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $showCalendar) {
            NavigationView {
                VStack(spacing: 10) {
                    Group {
                        if allowFuture {
                            DatePicker(label, selection: $calendarDraft, displayedComponents: .date)
                        } else {
                            DatePicker(label, selection: $calendarDraft, in: ...Date(), displayedComponents: .date)
                        }
                    }
                    .datePickerStyle(.graphical)
                    .environment(\.locale, Locale(identifier: "zh_CN"))
                    .environment(\.calendar, BusinessDates.calendar)
                    .environment(\.timeZone, BusinessDates.calendar.timeZone)
                    Button("今天") { calendarDraft = Date() }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .navigationTitle(label)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showCalendar = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("确定") {
                            let next = TXWorkDayPolicy.clamped(calendarDraft, allowFuture: allowFuture)
                            showCalendar = false
                            date = next
                        }
                    }
                }
            }.navigationViewStyle(StackNavigationViewStyle())
        }
    }

    private func step(_ days: Int) {
        date = TXWorkDayPolicy.shifted(date, days: days, allowFuture: allowFuture)
    }
}

struct TXPageDateHeader: View {
    let title: String
    @Binding var date: Date
    var allowFuture = false

    var body: some View {
        HStack(spacing: 4) {
            Text(title).font(.headline.bold()).fixedSize()
            TXDayBar(date: $date, label: title + "日期", allowFuture: allowFuture)
        }
    }
}

/// iOS 16 can measure intrinsic widths; iOS 15 deliberately uses the
/// readable stacked layout. Keep the existing deployment target unchanged.
struct TXAdaptiveHorizontal<Compact: View, Stacked: View>: View {
    private let compact: Compact
    private let stacked: Stacked

    init(@ViewBuilder compact: () -> Compact, @ViewBuilder stacked: () -> Stacked) {
        self.compact = compact()
        self.stacked = stacked()
    }

    var body: some View {
        if #available(iOS 16.0, *) {
            ViewThatFits(in: .horizontal) {
                compact
                stacked
            }
        } else {
            stacked
        }
    }
}

/// Small tinted metric tile, readable in both device appearances.
struct TXCompactMetric: View {
    let title: String
    let value: String
    let tint: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundColor(.secondary).lineLimit(1).minimumScaleFactor(0.8)
            Text(value).font(.headline.bold()).foregroundColor(.primary)
                .monospacedDigit().lineLimit(1).minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity, minHeight: 42, alignment: .leading)
        .padding(7)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
        .accessibilityElement(children: .combine)
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
                      purchaseEnabled: Bool,
                      settlementEnabled: Bool = false) -> TXTestWriteState {
        guard let book, book.name.hasPrefix("iOS_TEST_") else { return .productionReadOnly }
        guard isCandidate(book, account: account, offline: offline) else { return .restricted }
        if feature == .profitWrite { return settlementEnabled ? .live : .preparationOnly }
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
    /// Mirrors Android's short 4-column shortcut grid. No developer modules.
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 7), count: 4)

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("快捷操作").font(.headline)
            LazyVGrid(columns: columns, spacing: 7) {
                NavigationLink(destination: PurchasesHistoryView()) {
                    tile("历史记录", "clock.arrow.circlepath", 0)
                }
                NavigationLink(destination: PurchaseWorkspaceView()) {
                    tile("协作采购", "cart", 1)
                }
                NavigationLink(destination: BusinessStatsView()) {
                    tile("经营统计", "chart.bar.xaxis", 2)
                }
                NavigationLink(destination: CustomerInsightsView()) {
                    tile("客户分析", "person.2", 3)
                }
                NavigationLink(destination: TXFundHistoryView()) {
                    tile("资金余额", "banknote", 1)
                }
                NavigationLink(destination: OperatingReportView()) {
                    tile("生成报表", "doc.text", 2)
                }
                NavigationLink(destination: SettlementTableView()) {
                    tile("结算记录", "arrow.left.arrow.right", 0)
                }
                NavigationLink(destination: WeatherAlertsView()) {
                    tile("天气提醒", "cloud.rain", 3)
                }
            }
        }
    }

    private func tile(_ title: String, _ symbol: String, _ palette: Int) -> some View {
        let shades: [Color] = [
            Color(red: 0.91, green: 0.97, blue: 0.93),
            Color(red: 1.00, green: 0.96, blue: 0.90),
            Color(red: 0.91, green: 0.95, blue: 1.00),
            Color(red: 0.96, green: 0.93, blue: 1.00)
        ]
        return VStack(spacing: 7) {
            Image(systemName: symbol)
                .font(.system(size: 19)).foregroundColor(TXTheme.primary)
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.primary)
                .lineLimit(1).minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, minHeight: 64)
        .background(shades[palette], in: RoundedRectangle(cornerRadius: 12))
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
                               businessEnabled: businessEnabled,
                               purchaseEnabled: purchaseEnabled,
                               settlementEnabled: model.settlementCommitCapabilities?.allowsWrite == true)
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
                Text("营业、采购与已有当日资金转账使用独立原子接口；仅在线可编辑 iOS_TEST_ 账本可提交。生成日结、周期结清和利润分配继续预留。")
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
