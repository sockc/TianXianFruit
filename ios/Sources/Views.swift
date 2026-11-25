import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: LedgerViewModel

    var body: some View {
        Group {
            if model.account == nil {
                LoginView()
            } else {
                DashboardView()
            }
        }
        .tint(TXTheme.primary)
    }
}

private struct LoginView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationView {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("天鲜账本").font(.largeTitle.bold())
                        Text("天鲜果业 · 经营管理").foregroundColor(.secondary)
                    }.padding(.vertical, 10)
                }
                Section("云端账号") {
                    TextField("账号", text: $username)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                        .textContentType(.password)
                    Text("sync.830888.xyz")
                        .font(.caption).foregroundColor(.secondary)
                }
                Section {
                    Button {
                        Task {
                            await model.signIn(username: username, password: password)
                            password = ""
                        }
                    } label: {
                        HStack {
                            Spacer()
                            if model.isBusy { ProgressView().padding(.trailing, 6) }
                            Text("登录并读取账本")
                            Spacer()
                        }
                    }
                    .disabled(model.isBusy || username.isEmpty || password.isEmpty)
                }
                if let error = model.errorMessage {
                    Section {
                        Text(error).font(.footnote).foregroundColor(.red)
                    }
                }
            }
            .navigationTitle("登录")
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

private struct DashboardView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selectedTab: TXMainTab = .home

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                compactBookBar
                Group {
                    switch selectedTab {
                    case .home: HomePage(selectedTab: $selectedTab)
                    case .inventory: TXInventory094Page()
                    case .purchase: PurchaseWorkspaceView()
                    case .business: BusinessPage()
                    case .settlement: SettlementTableView()
                    case .more: MorePage()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                TXMainNavigationBar(selected: $selectedTab)
                    .disabled(model.inlinePurchaseEditing)
            }
            // The dashboard owns its own compact header. Do not reserve
            // a second iOS navigation bar / white safe-area region above it.
            .background(Color(UIColor.systemGroupedBackground).ignoresSafeArea())
            .navigationBarHidden(true)
            .onChange(of: selectedTab) { tab in
                if tab != .purchase && tab != .more {
                    model.workDate = TXWorkDayPolicy.clamped(model.workDate, allowFuture: false)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .background(Color(UIColor.systemGroupedBackground).ignoresSafeArea())
    }

    private var compactBookBar: some View {
        HStack(spacing: 9) {
            if model.books.count > 1 {
                Picker("账本", selection: Binding(
                    get: { model.selectedBookID ?? "" },
                    set: { model.selectBook($0) }
                )) {
                    ForEach(model.books) { Text($0.name).tag($0.id) }
                }
                .pickerStyle(.menu)
                .font(.subheadline.bold())
                .disabled(model.inlinePurchaseEditing)
            } else {
                Text(model.selectedBook?.name ?? "无可用账本")
                    .font(.subheadline.bold())
                    .lineLimit(1)
            }
            if model.selectedBook?.name.hasPrefix("iOS_TEST_") == true {
                Text("测试账本")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.orange)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Color.orange.opacity(0.12),
                                in: Capsule())
            }
            Spacer(minLength: 4)
            HStack(spacing: 4) {
                Circle().fill(model.isUsingCachedAuthorization || model.errorMessage != nil ? Color.orange : TXTheme.primary)
                    .frame(width: 6, height: 6)
                Text(model.isBusy ? "同步中" :
                     model.isUsingCachedAuthorization ? "离线" :
                     model.errorMessage != nil ? "同步异常" :
                     model.lastSyncAt == nil ? "待同步" : "已同步")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Button {
                Task { await model.refresh(); await model.refreshAllTestWriteCapabilities(); await model.refreshCustomer() }
            } label: {
                if model.isBusy { ProgressView().controlSize(.small) }
                else { Image(systemName: "arrow.clockwise") }
            }
            .disabled(model.isBusy || model.selectedBookID == nil)
            .accessibilityLabel("同步账本")
        }
        .padding(.horizontal, 12)
        .frame(height: 36)
        .background(Color(UIColor.systemGroupedBackground))
        .overlay(alignment: .bottom) { Divider() }
    }
}

private struct HomePage: View {
    @EnvironmentObject private var model: LedgerViewModel
    @Binding var selectedTab: TXMainTab

    private func coreAction(_ name: String, _ subtitle: String, _ symbol: String,
                            to tab: TXMainTab) -> some View {
        Button {
            selectedTab = tab
        } label: {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                    .font(.system(size: 21, weight: .medium))
                    .foregroundColor(TXTheme.primary)
                Text(name).font(.subheadline.bold()).foregroundColor(.primary)
                Text(subtitle).font(.system(size: 10))
                    .foregroundColor(.secondary).lineLimit(1).minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity, minHeight: 68)
            .background(Color(UIColor.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var coreOperations: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("日常操作").font(.headline)
            HStack(spacing: 8) {
                coreAction("营业", "录入收款", "square.and.pencil", to: .business)
                coreAction("采购", "商品采购", "cart", to: .purchase)
                coreAction("库存", "库存盘点", "shippingbox", to: .inventory)
                coreAction("结算", "资金结算", "checkmark.circle", to: .settlement)
            }
        }
    }

    @State private var profitReport: TXInventory096Analysis.Report?
    @State private var profitReportKey = ""
    private var today: String { BusinessDates.key(model.workDate) }
    private var week: [StoreDailyRow] { model.snapshot.sales(since: BusinessDates.daysAgo(6, from: model.workDate), until: today) }
    private var reportKey: String { [model.account?.username ?? "", model.selectedBookID ?? "", today, String(model.currentCursor), String(model.lastSyncAt?.timeIntervalSince1970 ?? 0)].joined(separator: "|") }
    private var report: TXInventory096Analysis.Report? { profitReportKey == reportKey ? profitReport : nil }
    private var estimate: String {
        guard let r = report else { return "—" }
        if !r.issues.isEmpty { return "待核对" }
        if !r.hasAvailable { return "—" }
        if r.missingAvailable > 0 { return "缺\(r.missingAvailable)项" }
        return r.knownAvailable.formatted(.currency(code: "CNY").precision(.fractionLength(0)))
    }
    private func money(_ n: Double) -> String { n.formatted(.currency(code: "CNY")) }
    private var daily: [StoreDailyRow] { model.snapshot.sales(since: today, until: today) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("天鲜果业").font(.headline.bold())
                            .foregroundColor(TXTheme.primary)
                        Text("经营概览 · " + today)
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer(minLength: 4)
                    Text(today == BusinessDates.key(Date()) ? "今日概览" : "历史经营")
                        .font(.caption2).foregroundColor(.secondary)
                }
                TXDayBar(date: $model.workDate, label: "经营日期", allowFuture: false)
                if model.lastSyncAt == nil {
                    EmptyStateView(title: "尚未读取云端账本", icon: "icloud.and.arrow.down",
                                   detail: "点击右上角刷新；首次成功后可离线查看")
                } else {
                    Text(today == BusinessDates.key(Date()) ? "今日经营" : "当日经营").font(.headline)
                    Text(daily.isEmpty ? "无已同步营业位置" : Array(Set(daily.map(\.storeName))).sorted().joined(separator: " · "))
                        .font(.caption).foregroundColor(.secondary).lineLimit(2)
                    HStack(spacing: 7) {
                        TXCompactMetric(title: "营业额", value: money(sum(daily, \.revenue)), tint: .orange)
                        TXCompactMetric(title: "采购金额", value: money(sum(model.snapshot.purchases.filter { $0.date == today }, \.totalCost)), tint: .blue)
                    }
                    HStack(spacing: 7) {
                        TXCompactMetric(title: "实际利润", value: report?.recordedProfit.map { money($0) } ?? "—", tint: .green)
                        NavigationLink(destination: TXInventory096Detail(initialDate: model.workDate)) {
                            TXCompactMetric(title: "预估利润", value: estimate, tint: .purple)
                        }.buttonStyle(.plain)
                    }
                    if report?.hasLocal == true {
                        Text("预估含本地草稿 · 未同步").font(.caption2).foregroundColor(.orange)
                    }
                    coreOperations
                    DisclosureGroup("库存与利润明细") {
                        TXInventory096Overview(date: today, compact: true)
                    }.font(.caption)
                    VStack(alignment: .leading, spacing: 7) {
                        Text("最近7天营业表现").font(.headline)
                        ForEach(BusinessDates.recentDays(7, from: model.workDate), id: \.self) { day in
                            let rows = week.filter { $0.date == day }
                            HStack {
                                Text(day.dropFirst(5)).font(.caption).frame(width: 46, alignment: .leading)
                                Text("\(rows.count) 个位置")
                                    .font(.caption2).foregroundColor(.secondary)
                                Spacer()
                                Text(sum(rows, \.revenue).formatted(.currency(code: "CNY")))
                                    .font(.subheadline.bold()).minimumScaleFactor(0.75)
                            }
                            Divider()
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                    if daily.count > 1 {
                        Text("当日有 \(daily.count) 条位置营业记录，已分别计入合计。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    // Daily figures come from saved cloud records; no estimated settlement.
                }
                WeatherHomeSummary()
                HStack(spacing: 8) {
                    NavigationLink(destination: CustomerInsightsView()) {
                        Label("客户分析", systemImage: "person.2").frame(maxWidth: .infinity)
                    }
                    NavigationLink(destination: BusinessStatsView()) {
                        Label("经营统计", systemImage: "chart.bar.xaxis").frame(maxWidth: .infinity)
                    }
                }.font(.subheadline).buttonStyle(.bordered)
                SyncFooter()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .task(id: reportKey) {
            profitReport = nil; profitReportKey = ""
            guard model.lastSyncAt != nil else { return }
            profitReport = TXInventory096Readout.report(model, date: today)
            profitReportKey = reportKey
        }
    }
}

private struct BusinessDraftDisplayRow: Identifiable {
    let draft: BusinessLocalDraft
    let status: BusinessDraftWorkflowPolicy.CloudState
    var id: String { draft.id }
}

private struct BusinessPage: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var period = "单日"
    private var selectedDate: Date { get { model.workDate } nonmutating set { model.workDate = newValue } }
    @State private var position = ""
    @State private var searchText = ""
    @State private var visibleDays = 14
    @State private var showFilters = false
    @State private var showLocalEntry = false
    @State private var preferredStoreID: String?

    private var selectedKey: String { BusinessDates.key(selectedDate) }
    private var startingKey: String {
        switch period {
        case "近7天": return BusinessDates.daysAgo(6, from: selectedDate)
        case "近30天": return BusinessDates.daysAgo(29, from: selectedDate)
        case "全部": return "0000-00-00"
        default: return selectedKey
        }
    }

    private var rows: [StoreDailyRow] {
        model.snapshot.business.filter { row in
            (period == "全部" || (row.date >= startingKey && row.date <= selectedKey)) &&
            (position.isEmpty || row.storeName == position) &&
            (searchText.isEmpty || row.storeName.localizedCaseInsensitiveContains(searchText) ||
             row.date.contains(searchText))
        }
    }

    private var localDrafts: [BusinessLocalDraft] {
        guard let account = model.account?.username, let book = model.selectedBookID else { return [] }
        return BusinessLocalDraftStore.load(account: account, bookID: book)
            .filter { $0.date == selectedKey }
    }

    private var draftStatusRows: [BusinessDraftDisplayRow] {
        let drafts = localDrafts
        guard !drafts.isEmpty else { return [] }
        let outbox: BusinessCommitOutbox
        do { outbox = try BusinessCommitOutbox() }
        catch {
            return drafts.map {
                BusinessDraftDisplayRow(draft: $0, status: .unsafeToRead)
            }
        }
        return drafts.map { draft in
            let state: BusinessDraftWorkflowPolicy.CloudState
            do {
                let attempt = try outbox.load(
                    id: draft.id, account: model.account?.username ?? "",
                    bookID: model.selectedBookID ?? "")
                state = BusinessDraftWorkflowPolicy.status(
                    draft: draft, events: Array(model.snapshot.records.values),
                    attempt: attempt,
                    cloudSnapshotReady: model.lastSyncAt != nil &&
                        !model.isUsingCachedAuthorization)
            } catch {
                state = .unsafeToRead
            }
            return BusinessDraftDisplayRow(draft: draft, status: state)
        }
    }

    private var grouped: [DayRows<StoreDailyRow>] {
        DayRows<StoreDailyRow>.grouping(rows, date: { $0.date })
    }

    // Respect the selected location filter when entering a new business
    // record, but only if the cloud location has an unambiguous identity.
    private var filteredStoreID: String? {
        guard !position.isEmpty else { return nil }
        let found = model.snapshot.testStores.filter { $0.name == position }
        return found.count == 1 ? found[0].id : nil
    }

    private var filteredDayAlreadyRecorded: Bool {
        !position.isEmpty && model.snapshot.business.contains {
            $0.date == selectedKey && $0.storeName == position
        }
    }

    private func moveDay(_ offset: Int) {
        selectedDate = BusinessDates.calendar.date(byAdding: .day, value: offset, to: selectedDate)
            ?? selectedDate
        visibleDays = 14
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 7) {
                TXPageDateHeader(title: "营业", date: $model.workDate)
                    .onChange(of: selectedDate) { _ in visibleDays = 14 }
                if model.isBusy || model.errorMessage != nil || model.isUsingCachedAuthorization || model.lastSyncAt == nil { TXCloudSyncStatusBar() }
                DisclosureGroup("当日库存经营分析（全账本）") {
                    TXInventory096Overview(date: selectedKey)
                }.font(.caption)
                HStack(spacing: 8) {
                    Picker("位置", selection: $position) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.menu)
                    Spacer()
                    if filteredDayAlreadyRecorded {
                        Label("当前日期与位置已有营业", systemImage: "checkmark.shield")
                            .font(.caption2).foregroundColor(.secondary)
                    } else {
                        Button {
                            preferredStoreID = filteredStoreID
                            showLocalEntry = true
                        } label: {
                            Label("新增营业记录", systemImage: "plus")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.selectedBookID == nil)
                    }
                }
                DisclosureGroup(isExpanded: $showFilters) {
                    VStack(spacing: 8) {
                        Picker("日期范围", selection: $period) {
                            Text("单日").tag("单日")
                            Text("近7天").tag("近7天")
                            Text("近30天").tag("近30天")
                            Text("全部").tag("全部")
                        }
                        .pickerStyle(.segmented)
                        .onChange(of: period) { _ in visibleDays = 14 }
                        SearchField(placeholder: "搜索日期或位置", text: $searchText)
                    }
                    .padding(.top, 7)
                } label: {
                    HStack {
                        Label("筛选与历史", systemImage: "line.3.horizontal.decrease")
                        Spacer()
                        Text("\(rows.count) 条记录")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    .font(.caption)
                }
                if draftStatusRows.contains(where: { $0.status.needsAttention }) {
                    VStack(alignment: .leading, spacing: 7) {
                        Label("本机营业事项（仅显示待处理）",
                              systemImage: "square.and.pencil")
                            .font(.subheadline.bold()).foregroundColor(.orange)
                        ForEach(draftStatusRows.filter { $0.status.needsAttention }) { item in
                            Button {
                                preferredStoreID = item.draft.storeID
                                showLocalEntry = true
                            } label: {
                                HStack(spacing: 8) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(item.draft.storeName)
                                            .foregroundColor(.primary)
                                        Text(item.status.label)
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(period == "单日" ? "当天营业额" : "营业额").foregroundColor(.secondary)
                        Spacer()
                        Text(sum(rows, \.revenue).formatted(.currency(code: "CNY")))
                            .font(.headline.bold())
                    }
                    HStack {
                        Text("已存利润").foregroundColor(.secondary)
                        Spacer()
                        Text(sum(rows, \.profit).formatted(.currency(code: "CNY")))
                            .font(.subheadline.bold())
                    }
                    .font(.subheadline)
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))
                if rows.isEmpty {
                    EmptyStateView(title: "此范围没有已同步营业记录", icon: "chart.bar")
                }
                if !rows.isEmpty { Text(period == "单日" ? "当天营业记录" : "营业记录").font(.subheadline.bold()) }
                ForEach(Array(grouped.prefix(visibleDays)), id: \.date) { day in
                    VStack(alignment: .leading, spacing: 9) {
                        HStack {
                            Text(day.date).font(.subheadline.bold())
                            Spacer()
                            Text("合计 \(sum(day.rows, \.revenue).formatted(.currency(code: "CNY")))")
                                .font(.caption.bold())
                        }
                        if day.rows.count > 1 {
                            Text("\(day.rows.count) 个位置 · 已存利润 \(sum(day.rows, \.profit).formatted(.currency(code: "CNY")))")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        ForEach(day.rows) { row in
                            HStack(spacing: 8) {
                                NavigationLink(destination: BusinessCloudDetailView(
                                    recordSyncID: row.id,
                                    bookID: model.selectedBookID ?? "")) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.storeName).font(.subheadline)
                                            .foregroundColor(.primary)
                                        Text("已存利润：" +
                                             row.profit.formatted(.currency(code: "CNY")))
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .buttonStyle(.plain)
                                TXBusinessCustomerShortcut(recordSyncID: row.id)
                                NavigationLink(destination: BusinessCloudDetailView(
                                    recordSyncID: row.id,
                                    bookID: model.selectedBookID ?? "")) {
                                    HStack(spacing: 4) {
                                        Text(row.revenue.formatted(.currency(code: "CNY")))
                                            .font(.subheadline.bold())
                                            .foregroundColor(.primary)
                                        Image(systemName: "chevron.right")
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                            Divider()
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                if grouped.count > visibleDays {
                    Button("加载更早营业记录") { visibleDays += 14 }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                // Android: selected day above, then the previous seven
                // actual BUSINESS days. Other date-range modes already show
                // those days in the main filtered list, so don't duplicate.
                if period == "单日" {
                    Divider()
                    TXRecentBusinessDaysView(selectedDate: selectedKey) {
                        period = "全部"
                        showFilters = true
                        visibleDays = 14
                    }
                }
                // Detail actions remain scoped by account and server permissions.
                SyncFooter()
            }
            .padding(12)
        }
        .sheet(isPresented: $showLocalEntry) {
            BusinessLocalEntryView(date: selectedDate,
                                   preferredStoreID: preferredStoreID)
                .environmentObject(model)
        }
    }
}

struct PurchasesHistoryView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var period = "近30天"
    @State private var selectedDate = Date()
    @State private var buyer = ""
    @State private var searchText = ""
    @State private var visibleDays = 14

    private var rows: [PurchaseOrderRow] {
        TXPurchaseHistoryPolicy.filter(model.snapshot.purchases,
            range: period, selectedDate: selectedDate,
            buyer: buyer, search: searchText)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TXDayBar(date: $selectedDate, label: "采购历史截止日",
                         allowFuture: false)
                    .onChange(of: selectedDate) { _ in visibleDays = 14 }
                HStack(spacing: 8) {
                    Picker("日期范围", selection: $period) {
                        ForEach(TXPurchaseHistoryPolicy.ranges, id: \.self) { value in
                            Text(value).tag(value)
                        }
                    }
                    .pickerStyle(MenuPickerStyle())
                    .onChange(of: period) { _ in visibleDays = 14 }
                    Picker("采购人", selection: $buyer) {
                        Text("全部采购人").tag("")
                        ForEach(model.snapshot.buyers, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(MenuPickerStyle())
                    .onChange(of: buyer) { _ in visibleDays = 14 }
                }
                SearchField(placeholder: "搜索日期或采购人", text: $searchText)
                    .onChange(of: searchText) { _ in visibleDays = 14 }
                HStack {
                    Text("采购订单 · \(rows.count) 条").font(.headline)
                    Spacer()
                    Text(sum(rows, \.totalCost).formatted(.currency(code: "CNY")))
                        .font(.subheadline.bold())
                }
                if rows.isEmpty {
                    EmptyStateView(title: "此范围没有采购记录", icon: "cart")
                }
                ForEach(Array(DayRows<PurchaseOrderRow>.grouping(rows, date: { $0.date }).prefix(visibleDays)), id: \.date) { day in
                    VStack(alignment: .leading, spacing: 9) {
                        HStack {
                            Text(day.date).font(.subheadline.bold())
                            Spacer()
                            Text("合计 \(sum(day.rows, \.totalCost).formatted(.currency(code: "CNY")))")
                                .font(.caption.bold())
                        }
                        ForEach(day.rows) { row in
                            NavigationLink(destination: PurchaseCloudDetailView(
                                order: row, bookID: model.selectedBookID ?? "")) {
                                HStack {
                                    Text(row.buyer).font(.subheadline)
                                    Spacer()
                                    Text(row.totalCost.formatted(.currency(code: "CNY")))
                                        .font(.subheadline.bold())
                                    Image(systemName: "chevron.right")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                            Divider()
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                Text("点击采购单查看云端商品明细与金额核对；只读已保存采购，不把待采购计划计入成本。")
                    .font(.caption2).foregroundColor(.secondary)
                if DayRows<PurchaseOrderRow>.grouping(rows, date: { $0.date }).count > visibleDays {
                    Button("加载更早采购记录") { visibleDays += 14 }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                SyncFooter()
            }
            .padding(12)
        }
    }
}

private struct InventoryPage: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var showHistory = false
    @State private var searchText = ""
    @State private var visibleRows = 80

    private var rows: [InventoryRow] {
        (showHistory ? model.snapshot.inventory : model.snapshot.latestInventory)
            .filter { searchText.isEmpty || $0.name.localizedCaseInsensitiveContains(searchText) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("库存").font(.headline)
                    Spacer()
                    Toggle("历史", isOn: $showHistory).labelsHidden()
                    Text("全部日期").font(.caption)
                }
                if let day = model.snapshot.latestInventoryDate {
                    Text("最近一次云端盘点：\(day)")
                        .font(.caption).foregroundColor(.secondary)
                } else {
                    Text("云端没有同步的库存盘点")
                        .font(.caption).foregroundColor(.secondary)
                }
                SearchField(placeholder: "查找商品", text: $searchText)
                if rows.isEmpty {
                    EmptyStateView(title: "没有可展示的库存快照", icon: "shippingbox")
                }
                LazyVStack(spacing: 9) {
                    ForEach(Array(rows.prefix(visibleRows))) { row in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(row.name).font(.subheadline.bold())
                                Text("\(row.date) · 单位：\(row.unit)")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 4) {
                                Text("剩余 \(row.remaining.formatted()) \(row.unit)")
                                    .font(.subheadline)
                                Text("损耗 \(row.loss.formatted()) \(row.unit)")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                }
                // Only saved stock snapshots are displayed.
                if rows.count > visibleRows {
                    Button("加载更多库存盘点") { visibleRows += 80 }
                        .frame(maxWidth: .infinity)
                }
                SyncFooter()
            }
            .padding(12)
        }
    }
}

private struct MorePage: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var showAccountDetails = false
    @State private var showSyncRecovery = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 11) {
                Text("经营资料").font(.headline)
                NavigationLink(destination: TXBookDirectoryView()) {
                    menuLine("账本管理", "books.vertical", "查看与切换账本")
                }
                NavigationLink(destination: TXBasicDirectoryView(kind: .fruit)) {
                    menuLine("商品管理", "shippingbox", "查看商品资料")
                }
                NavigationLink(destination: TXBasicDirectoryView(kind: .store)) {
                    menuLine("位置管理", "mappin.and.ellipse", "查看营业位置")
                }
                NavigationLink(destination: TXBasicDirectoryView(kind: .partner)) {
                    menuLine("合伙人管理", "person.2", "查看合伙人资料")
                }
                Divider().padding(.vertical, 2)
                Text("经营数据").font(.headline)
                NavigationLink(destination: CustomerInsightsView()) {
                    menuLine("客户分析", "person.2", "微信、支付宝 · 逐小时")
                }
                NavigationLink(destination: BusinessStatsView()) {
                    menuLine("经营统计", "chart.bar.xaxis", "位置 · 趋势")
                }
                NavigationLink(destination: PurchasesHistoryView()) {
                    menuLine("采购历史", "clock.arrow.circlepath", "按日期查看")
                }
                NavigationLink(destination: WeatherAlertsView()) {
                    menuLine("天气提醒", "cloud.rain", "未来7天")
                }
                Divider().padding(.vertical, 2)
                Text("资金与报表").font(.headline)
                NavigationLink(destination: TXFundHistoryView()) {
                    menuLine("资金余额与结算记录", "banknote", "每日明细 · 历史结清")
                }
                NavigationLink(destination: TXProfitRuleDirectoryView()) {
                    menuLine("利润分配规则", "chart.pie", "当前比例 · 只读")
                }
                NavigationLink(destination: OperatingReportView()) {
                    menuLine("生成报表", "doc.text", "经营汇总 · PDF / 图片")
                }
                Divider().padding(.vertical, 2)
                DisclosureGroup(isExpanded: $showAccountDetails) {
                    VStack(alignment: .leading, spacing: 12) {
                        Label(model.account?.displayName ?? "未登录",
                              systemImage: "person.crop.circle")
                        Text("账本：" + (model.selectedBook?.name ?? "未选择"))
                        Text(model.lastSyncAt.map {
                            "最近同步：\($0.formatted(date: .abbreviated, time: .shortened))"
                        } ?? "尚未成功同步")
                        if model.isUsingCachedAuthorization {
                            Text("当前为离线缓存，数据可能不是最新")
                                .foregroundColor(.orange)
                        }
                        if let error = model.errorMessage {
                            Text(error).foregroundColor(.orange)
                        }
                        Button("立即同步") { Task { await model.refresh(); await model.refreshAllTestWriteCapabilities() } }
                            .disabled(model.isBusy)
                        DisclosureGroup("同步修复", isExpanded: $showSyncRecovery) {
                            Button("完整同步") { Task { await model.refresh(forceFull: true) } }
                                .disabled(model.isBusy)
                        }
                        Button(role: .destructive) { model.signOut() } label: {
                            Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                        .disabled(model.isBusy)
                    }
                    .font(.caption)
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 11))
                    .padding(.top, 8)
                } label: {
                    Label("账号与同步", systemImage: "person.crop.circle")
                        .font(.subheadline.bold())
                }
                Text(TXAppPresentation.versionLabel(version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                    build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String))
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
    }

    private func menuLine(_ title: String, _ symbol: String,
                          _ detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundColor(TXTheme.primary)
                .frame(width: 28)
            Text(title).font(.subheadline.bold()).foregroundColor(.primary)
            Spacer(minLength: 4)
            Text(detail).font(.caption2).foregroundColor(.secondary)
                .lineLimit(1)
            Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 51)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 11))
    }
}

private struct AmountCard: View {
    let title: String
    let value: Double
    let color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value.formatted(.currency(code: "CNY")))
                .font(.headline.bold()).foregroundColor(color)
                .minimumScaleFactor(0.65).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SearchField: View {
    let placeholder: String
    @Binding var text: String
    var body: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundColor(.secondary)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .padding(9)
        .background(Color(UIColor.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 9))
    }
}

/// Online sync is not the same as a successful cloud write. The badge
/// describes only last completed pull; it never grants write permission.
struct TXCloudSyncStatusBar: View {
    @EnvironmentObject private var model: LedgerViewModel

    var body: some View {
        HStack(spacing: 8) {
            if model.isBusy {
                ProgressView()
                Text("同步中").font(.caption)
            } else if model.isUsingCachedAuthorization {
                Label("离线缓存 · 禁止云端提交", systemImage: "wifi.slash")
                    .foregroundColor(.orange).font(.caption2)
            } else if let error = model.errorMessage {
                Label("同步未完成：" + error, systemImage: "exclamationmark.icloud")
                    .foregroundColor(.orange).font(.caption2)
                    .lineLimit(2)
            } else if let date = model.lastSyncAt {
                Label("最近拉取 " + date.formatted(date: .omitted, time: .shortened),
                      systemImage: "checkmark.icloud")
                    .foregroundColor(.secondary).font(.caption2)
            } else {
                Label("尚未同步", systemImage: "icloud.slash")
                    .foregroundColor(.secondary).font(.caption2)
            }
            Spacer(minLength: 0)
            Button {
                Task { await model.refresh() }
            } label: {
                Label("刷新云端", systemImage: "arrow.clockwise")
                    .font(.caption)
            }
            .disabled(model.isBusy || model.account == nil ||
                      model.selectedBookID == nil)
        }
    }
}

struct SyncFooter: View {
    @EnvironmentObject private var model: LedgerViewModel
    var body: some View {
        Group {
            if let time = model.lastSyncAt {
                HStack(spacing: 5) {
                    Image(systemName: model.errorMessage == nil && !model.isUsingCachedAuthorization ? "checkmark.icloud" : "exclamationmark.icloud")
                    Text((model.errorMessage == nil && !model.isUsingCachedAuthorization ? "最近同步 " : "缓存时间 ") + time.formatted(date: .abbreviated, time: .shortened))
                }
                .font(.caption2).foregroundColor(.secondary)
                .padding(.vertical, 4)
            }
        }
    }
}

private struct DayRows<Row> {
    let date: String
    let rows: [Row]
    static func grouping(_ rows: [Row], date: (Row) -> String) -> [DayRows<Row>] {
        let grouped = Dictionary(grouping: rows, by: date)
        return grouped.keys.sorted(by: >).map { key in
            DayRows(date: key, rows: grouped[key] ?? [])
        }
    }
}

private func sum<T>(_ rows: [T], _ keyPath: KeyPath<T, Double>) -> Double {
    rows.reduce(0) { $0 + $1[keyPath: keyPath] }
}

private struct EmptyStateView: View {
    let title: String
    let icon: String
    var detail: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.title2).foregroundColor(.secondary)
            Text(title).font(.subheadline.bold())
            if let detail = detail {
                Text(detail).font(.caption).foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }
}
