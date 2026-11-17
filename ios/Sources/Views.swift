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
        .tint(Color.green)
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
                        Text("iOS V0.1.0 · 只读测试版").foregroundStyle(.secondary)
                        Label("仅查看已有账目，不可录入或修改", systemImage: "lock.shield")
                            .font(.footnote)
                    }.padding(.vertical, 12)
                }
                Section("云端账号") {
                    TextField("账号", text: $username)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                        .textContentType(.password)
                    Text("https://sync.830888.xyz")
                        .font(.caption).foregroundStyle(.secondary)
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
                            if model.isBusy { ProgressView().padding(.trailing, 8) }
                            Text("登录并读取账本")
                            Spacer()
                        }
                    }
                    .disabled(model.isBusy || username.isEmpty || password.isEmpty)
                }
                if let message = model.errorMessage {
                    Section { Text(message).foregroundStyle(.red) }
                }
            }
            .navigationTitle("登录")
        }
    }
}

private struct DashboardView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @State private var selection = 0

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Label("只读模式 · 不会修改云端账本", systemImage: "lock.shield.fill")
                        .font(.footnote)
                        .foregroundStyle(.green)
                    if model.books.isEmpty {
                        EmptyStateView(title: "没有可读取的账本", icon: "books.vertical", detail: "请确认此账号已获授权")
                    } else {
                        Picker("当前账本", selection: Binding(
                            get: { model.selectedBookID ?? "" },
                            set: { model.selectBook($0) }
                        )) {
                            ForEach(model.books) { book in
                                Text(book.name).tag(book.id)
                            }
                        }
                        .pickerStyle(.menu)

                        Text("云端已保存的业务数据")
                            .font(.headline)
                        if model.lastSyncAt == nil {
                            Text("尚未读取记录。点击右上角刷新从云端加载。")
                                .font(.subheadline).foregroundStyle(.secondary)
                        } else {
                            metrics
                            Picker("业务视图", selection: $selection) {
                                Text("营业").tag(0)
                                Text("采购").tag(1)
                            }
                            .pickerStyle(.segmented)

                            if selection == 0 { salesList } else { purchaseList }
                        }
                    }
                    if let error = model.errorMessage {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.footnote).foregroundStyle(.red)
                    }
                    if let last = model.lastSyncAt {
                        Text("读取完成：\(last.formatted(date: .abbreviated, time: .shortened)) · 游标 \(model.currentCursor)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Text("仅显示正式云端记录中的已存金额，不重新计算库存利润或资金结算。首次完整读取期间不会展示不完整汇总。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("天鲜账本")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        if let name = model.account?.displayName {
                            Text(name)
                        }
                        Button("退出账号", role: .destructive) { model.signOut() }
                    } label: { Image(systemName: "person.crop.circle") }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isBusy { ProgressView() } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(model.isBusy || model.selectedBookID == nil)
                    .accessibilityLabel("从云端刷新只读账本")
                }
            }
        }
    }

    private var metrics: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                metric("累计营业额", model.snapshot.totalRevenue)
                metric("已存利润", model.snapshot.totalProfit)
            }
            HStack {
                metric("采购单总额", model.snapshot.totalPurchases)
                VStack(alignment: .leading, spacing: 4) {
                    Text("记录数").foregroundStyle(.secondary).font(.caption)
                    Text("\(model.snapshot.business.count) 条营业")
                        .font(.subheadline.bold())
                    Text("\(model.snapshot.purchases.count) 条采购")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func metric(_ title: String, _ amount: Double) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).foregroundStyle(.secondary).font(.caption)
            Text(amount.formatted(.currency(code: "CNY")))
                .font(.headline)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var salesList: some View {
        LazyVStack(spacing: 10) {
            if model.snapshot.business.isEmpty {
                EmptyStateView(title: "暂无营业记录", icon: "chart.bar")
            }
            ForEach(Array(model.snapshot.business.prefix(60))) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(row.storeName).font(.subheadline.bold())
                        Spacer()
                        Text(row.date).foregroundStyle(.secondary).font(.caption)
                    }
                    HStack {
                        Text("营业 \(row.revenue.formatted(.currency(code: "CNY")))")
                        Spacer()
                        Text("利润 \(row.profit.formatted(.currency(code: "CNY")))")
                    }
                    .font(.footnote)
                }
                .padding(12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private var purchaseList: some View {
        LazyVStack(spacing: 10) {
            if model.snapshot.purchases.isEmpty {
                EmptyStateView(title: "暂无采购记录", icon: "basket")
            }
            ForEach(Array(model.snapshot.purchases.prefix(60))) { row in
                HStack {
                    VStack(alignment: .leading) {
                        Text(row.buyer).font(.subheadline.bold())
                        Text(row.date).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(row.totalCost.formatted(.currency(code: "CNY")))
                        .font(.subheadline)
                }
                .padding(12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }
}

private struct EmptyStateView: View {
    let title: String
    let icon: String
    var detail: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(.secondary)
            Text(title).font(.subheadline.bold())
            if let detail = detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }
}
