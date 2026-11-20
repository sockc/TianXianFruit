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
        .tint(.green)
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
                        Text("iOS V0.2.7 · 草稿预演").foregroundColor(.secondary)
                        Label("无营业、采购或结算写入功能", systemImage: "lock.shield")
                            .font(.footnote).foregroundColor(.green)
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

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                header
                TabView {
                    HomePage()
                        .tabItem { Label("首页", systemImage: "house") }
                    BusinessPage()
                        .tabItem { Label("营业", systemImage: "chart.bar") }
                    PurchasesPage()
                        .tabItem { Label("采购", systemImage: "cart") }
                    InventoryPage()
                        .tabItem { Label("库存", systemImage: "shippingbox") }
                    MorePage()
                        .tabItem { Label("更多", systemImage: "ellipsis.circle") }
                }
            }
            .navigationBarTitle("天鲜账本", displayMode: .inline)
            .navigationBarItems(trailing: Button {
                Task { await model.refresh(); await model.refreshCustomer() }
            } label: {
                if model.isBusy {
                    ProgressView()
                } else {
                    Image(systemName: "arrow.clockwise")
                }
            }
            .disabled(model.isBusy || model.selectedBookID == nil)
            .accessibilityLabel("增量刷新"))
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private var header: some View {
        VStack(spacing: 6) {
            HStack {
                Label("只读", systemImage: "lock.shield.fill")
                    .font(.caption.bold()).foregroundColor(.green)
                Spacer()
                if model.books.count > 1 {
                    Picker("账本", selection: Binding(
                        get: { model.selectedBookID ?? "" },
                        set: { model.selectBook($0) }
                    )) {
                        ForEach(model.books) { Text($0.name).tag($0.id) }
                    }
                    .pickerStyle(MenuPickerStyle())
                } else {
                    Text(model.selectedBook?.name ?? "无可用账本")
                        .font(.caption.bold()).lineLimit(1)
                }
                Spacer(minLength: 4)
                Text(model.isBusy ? "同步中" : (model.isUsingCachedAuthorization ? "离线授权" : "云端账号"))
                    .font(.caption2).foregroundColor(.secondary)
            }
            if let error = model.errorMessage {
                Text(error).font(.caption2).foregroundColor(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .lineLimit(3)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Color(UIColor.secondarySystemBackground))
    }
}

private struct HomePage: View {
    @EnvironmentObject private var model: LedgerViewModel

    private var today: String { BusinessDates.key(Date()) }
    private var week: [StoreDailyRow] { model.snapshot.sales(since: BusinessDates.daysAgo(6), until: today) }
    private var month: [StoreDailyRow] { model.snapshot.sales(since: BusinessDates.daysAgo(29), until: today) }
    private var daily: [StoreDailyRow] { model.snapshot.sales(since: today, until: today) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("经营概览")
                    .font(.title3.bold())
                WeatherHomeSummary()
                if model.lastSyncAt == nil {
                    EmptyStateView(title: "尚未读取云端账本", icon: "icloud.and.arrow.down",
                                   detail: "点击右上角刷新；首次成功后可离线查看")
                } else {
                    HStack(spacing: 10) {
                        AmountCard(title: "今日营业额", value: sum(daily, \.revenue), color: .green)
                        AmountCard(title: "今日已存利润", value: sum(daily, \.profit), color: .primary)
                    }
                    HStack(spacing: 10) {
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
                    Text("营业额、利润直接来自 Android 已保存的正式营业记录；无新增结算或预估计算。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                SyncFooter()
            }
            .padding(12)
        }
    }
}

private struct BusinessPage: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var period = "近30天"
    @State private var position = ""
    @State private var searchText = ""
    @State private var visibleDays = 14

    private var rows: [StoreDailyRow] {
        model.snapshot.business.filter { row in
            (period == "全部" || row.date >= BusinessDates.daysAgo(period == "近7天" ? 6 : 29)) &&
            (position.isEmpty || row.storeName == position) &&
            (searchText.isEmpty || row.storeName.localizedCaseInsensitiveContains(searchText) ||
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
                    Picker("位置", selection: $position) {
                        Text("全部位置").tag("")
                        ForEach(model.snapshot.locations, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(MenuPickerStyle())
                }
                SearchField(placeholder: "搜索日期或位置", text: $searchText)
                HStack {
                    Text("营业记录 · \(rows.count) 条").font(.headline)
                    Spacer()
                    Text(sum(rows, \.revenue).formatted(.currency(code: "CNY")))
                        .font(.subheadline.bold())
                }
                if rows.isEmpty {
                    EmptyStateView(title: "此范围没有营业记录", icon: "chart.bar")
                }
                ForEach(Array(DayRows<StoreDailyRow>.grouping(rows, date: { $0.date }).prefix(visibleDays)), id: \.date) { day in
                    VStack(alignment: .leading, spacing: 9) {
                        HStack {
                            Text(day.date).font(.subheadline.bold())
                            Spacer()
                            Text("合计 \(sum(day.rows, \.revenue).formatted(.currency(code: "CNY")))")
                                .font(.caption.bold())
                        }
                        ForEach(day.rows) { row in
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(row.storeName).font(.subheadline)
                                    Text("已存利润：\(row.profit.formatted(.currency(code: "CNY")))")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                Text(row.revenue.formatted(.currency(code: "CNY")))
                                    .font(.subheadline.bold())
                            }
                            Divider()
                        }
                    }
                    .padding(12)
                    .background(Color(UIColor.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                if DayRows<StoreDailyRow>.grouping(rows, date: { $0.date }).count > visibleDays {
                    Button("加载更早营业记录") { visibleDays += 14 }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                SyncFooter()
            }
            .padding(12)
        }
    }
}

private struct PurchasesPage: View {
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
                    Text("库存盘点 · 只读").font(.headline)
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
                Text("此处仅展示已保存的库存盘点数量和损耗。未同步或未盘点的商品不能推断为零库存；不进行跨日成本反算。")
                    .font(.caption2).foregroundColor(.secondary)
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

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("经营分析 · 只读").font(.headline)
                NavigationLink(destination: CustomerInsightsView()) {
                    Label("客户分析 · 微信/支付宝逐小时客流", systemImage: "person.2")
                }
                NavigationLink(destination: FinanceReadOnlyView()) {
                    Label("资金结算 · 已保存分配与转账", systemImage: "arrow.left.arrow.right")
                }
                NavigationLink(destination: BusinessStatsView()) {
                    Label("经营统计 · 位置与趋势", systemImage: "chart.bar.xaxis")
                }
                NavigationLink(destination: WeatherAlertsView()) {
                    Label("未来7天天气 · 下雨与降温提醒", systemImage: "cloud.rain")
                }
                NavigationLink(destination: OperatingReportView()) {
                    Label("导出经营报表 · PDF / 图片", systemImage: "square.and.arrow.up")
                }
                NavigationLink(destination: TestWriteLabView()) {
                    Label("写入测试实验室 · 仅本机草稿", systemImage: "square.and.pencil")
                }
                NavigationLink(destination: StagingPurchaseUploadView()) {
                    Label("测试账本采购同步", systemImage: "arrow.up.doc")
                }
                Divider()
                Text("账号与同步").font(.headline)
                VStack(alignment: .leading, spacing: 12) {
                    Label(model.account?.displayName ?? "未登录", systemImage: "person.crop.circle")
                    Text("同步游标：\(model.currentCursor)")
                        .font(.caption).foregroundColor(.secondary)
                    Text(model.lastSyncAt.map { "上次成功：\($0.formatted(date: .abbreviated, time: .shortened))" } ?? "尚未成功同步")
                        .font(.caption).foregroundColor(.secondary)
                    Text(model.cacheAvailable ? "本地只读缓存已启用" : "本地缓存不可用")
                        .font(.caption).foregroundColor(model.cacheAvailable ? .green : .red)
                    if model.isUsingCachedAuthorization {
                        Text("离线展示来自上次已验证账号权限，联网后需重新核验。")
                            .font(.caption).foregroundColor(.orange)
                    }
                    Button("增量同步") { Task { await model.refresh() } }
                        .disabled(model.isBusy || model.selectedBookID == nil)
                    Button("重新完整核对云端记录") { Task { await model.refresh(forceFull: true) } }
                        .disabled(model.isBusy || model.selectedBookID == nil)
                    Text("完整核对不会先清空缓存，只有全部下载成功才替换数据。")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 12))
                Text("版本：iOS V0.2.8 · TrollStore 测试采购同步版")
                    .font(.caption).foregroundColor(.secondary)
                Text("正式云端仍只读。仅经隔离证明的测试服务端允许采购上传；营业写入和所有结算仍锁定。")
                    .font(.caption2).foregroundColor(.secondary)
                Button(role: .destructive) {
                    model.signOut()
                } label: {
                    Label("退出登录并清除此账号的本地缓存", systemImage: "rectangle.portrait.and.arrow.right")
                }
                .disabled(model.isBusy)
                SyncFooter()
            }
            .padding(12)
        }
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

private struct SyncFooter: View {
    @EnvironmentObject private var model: LedgerViewModel
    var body: some View {
        HStack {
            Image(systemName: "lock.shield")
            Text(model.lastSyncAt.map {
                "读取于 \($0.formatted(date: .abbreviated, time: .shortened))"
            } ?? "等待首次同步")
            Spacer()
            Text("只读")
        }
        .font(.caption2).foregroundColor(.secondary)
        .padding(.vertical, 8)
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
