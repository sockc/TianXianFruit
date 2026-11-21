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
                    case .inventory: InventoryPage()
                    case .purchase: PurchaseWorkspaceView()
                    case .business: BusinessPage()
                    case .settlement: SettlementTableView()
                    case .more: MorePage()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                TXMainNavigationBar(selected: $selectedTab)
            }
            .navigationTitle("天鲜果业")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(StackNavigationViewStyle())
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
            } else {
                Text(model.selectedBook?.name ?? "无可用账本")
                    .font(.subheadline.bold())
                    .lineLimit(1)
            }
            if model.selectedBook?.name.hasPrefix("iOS_TEST_") == true {
                Text("测试")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.orange)
            }
            Spacer(minLength: 4)
            HStack(spacing: 4) {
                Circle().fill(model.isUsingCachedAuthorization ? Color.orange : TXTheme.primary)
                    .frame(width: 6, height: 6)
                Text(model.isBusy ? "同步中" :
                     model.isUsingCachedAuthorization ? "离线" :
                     model.lastSyncAt == nil ? "待同步" : "已同步")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Button {
                Task { await model.refresh(); await model.refreshCustomer() }
            } label: {
                if model.isBusy { ProgressView().controlSize(.small) }
                else { Image(systemName: "arrow.clockwise") }
            }
            .disabled(model.isBusy || model.selectedBookID == nil)
            .accessibilityLabel("同步账本")
        }
        .padding(.horizontal, 13)
        .frame(height: 43)
        .background(TXTheme.header)
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
            VStack(spacing: 5) {
                Image(systemName: symbol)
                    .font(.system(size: 21, weight: .medium))
                    .foregroundColor(TXTheme.primary)
                Text(name).font(.subheadline.bold()).foregroundColor(.primary)
                Text(subtitle).font(.system(size: 10))
                    .foregroundColor(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 78)
            .background(Color(UIColor.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var coreOperations: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("日常操作").font(.headline)
            HStack(spacing: 8) {
                coreAction("营业", "录入收款", "square.and.pencil", to: .business)
                coreAction("采购", "商品采购", "cart", to: .purchase)
                coreAction("结算", "资金结算", "checkmark.circle", to: .settlement)
            }
        }
    }

    private var today: String { BusinessDates.key(Date()) }
    private var week: [StoreDailyRow] { model.snapshot.sales(since: BusinessDates.daysAgo(6), until: today) }
    private var month: [StoreDailyRow] { model.snapshot.sales(since: BusinessDates.daysAgo(29), until: today) }
    private var daily: [StoreDailyRow] { model.snapshot.sales(since: today, until: today) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("天鲜果业").font(.title2.bold())
                            .foregroundColor(TXTheme.primary)
                        Text("经营概览 · " + BusinessDates.key(Date()))
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Text("今日概览")
                        .font(.caption).foregroundColor(.secondary)
                }
                coreOperations
                WeatherHomeSummary()
                if model.lastSyncAt == nil {
                    EmptyStateView(title: "尚未读取云端账本", icon: "icloud.and.arrow.down",
                                   detail: "点击右上角刷新；首次成功后可离线查看")
                } else {
                    HStack(spacing: 6) {
                        AmountCard(title: "今日营业额", value: sum(daily, \.revenue), color: TXTheme.primary)
                        AmountCard(title: "今日采购额",
                                   value: sum(model.snapshot.purchases.filter { $0.date == today },
                                              \.totalCost), color: .primary)
                        AmountCard(title: "今日已存利润", value: sum(daily, \.profit), color: .primary)
                    }
                    HStack(spacing: 8) {
                        AmountCard(title: "近7天营业", value: sum(week, \.revenue), color: .primary)
                        AmountCard(title: "近30天营业", value: sum(month, \.revenue), color: .primary)
                    }
                    VStack(alignment: .leading, spacing: 7) {
                        Text("最近7天营业表现").font(.headline)
                        ForEach(BusinessDates.recentDays(7), id: \.self) { day in
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
                        Text("今日有 \(daily.count) 条位置营业记录，已分别计入合计。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    // Daily figures come from saved cloud records; no estimated settlement.
                }
                SyncFooter()
            }
            .padding(12)
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
    @State private var selectedDate = Date()
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
                    attempt: attempt)
            } catch {
                state = .unsafeToRead
            }
            return BusinessDraftDisplayRow(draft: draft, status: state)
        }
    }

    private var grouped: [DayRows<StoreDailyRow>] {
        DayRows<StoreDailyRow>.grouping(rows, date: { $0.date })
    }

    private func moveDay(_ offset: Int) {
        selectedDate = BusinessDates.calendar.date(byAdding: .day, value: offset, to: selectedDate)
            ?? selectedDate
        visibleDays = 14
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TXDayBar(date: $selectedDate, label: "营业日期", allowFuture: false)
                    .onChange(of: selectedDate) { _ in visibleDays = 14 }
                HStack(spacing: 8) {
                    Picker("位置", selection: $position) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.menu)
                    Spacer()
                    Button {
                        preferredStoreID = nil
                        showLocalEntry = true
                    } label: {
                        Label("营业录入", systemImage: "plus")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.selectedBookID == nil)
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
                        Text("营业额").foregroundColor(.secondary)
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
    @State private var buyer = ""
    @State private var searchText = ""
    @State private var visibleDays = 14

    private var rows: [PurchaseOrderRow] {
        model.snapshot.purchases.filter { row in
            (period == "全部" || row.date >= BusinessDates.daysAgo(period == "近7天" ? 6 : 29)) &&
            (buyer.isEmpty || row.buyer == buyer) &&
            (searchText.isEmpty || row.buyer.localizedCaseInsensitiveContains(searchText) ||
             row.date.contains(searchText))
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Picker("日期", selection: $period) {
                        Text("近7天").tag("近7天")
                        Text("近30天").tag("近30天")
                        Text("全部").tag("全部")
                    }.pickerStyle(SegmentedPickerStyle())
                    Picker("采购人", selection: $buyer) {
                        Text("全部采购人").tag("")
                        ForEach(model.snapshot.buyers, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(MenuPickerStyle())
                }
                SearchField(placeholder: "搜索日期或采购人", text: $searchText)
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

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 11) {
                Text("经营与分析").font(.headline)
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
                        HStack {
                            Button("立即同步") { Task { await model.refresh() } }
                                .disabled(model.isBusy)
                            Spacer()
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
                Text("iOS V0.8.0 · Build 49")
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

struct SyncFooter: View {
    @EnvironmentObject private var model: LedgerViewModel
    var body: some View {
        Group {
            if let time = model.lastSyncAt {
                HStack(spacing: 5) {
                    Image(systemName: "checkmark.icloud")
                    Text("最近同步 " + time.formatted(date: .abbreviated, time: .shortened))
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
