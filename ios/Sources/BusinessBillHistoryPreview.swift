import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Read-only V40 candidate calculations. Historical rows are never mapped
/// to a new-sale V2 draft, and no V4 network operation is exposed here.
struct TXHistoryBillPlan {
    let oldRevenue: Int64
    let newRevenue: Int64
    let oldProfit: Int64
    let newProfit: Int64
    let cash: Int64
    let oldWechat: Int64
    let newWechat: Int64
    let oldAlipay: Int64
    let newAlipay: Int64
    let rowVersion: Int64
    let naturalRevision: Int64
    let previousRevision: Int64
    let followingRevision: Int64
    let bill: TXBillV3Input
    let originalSale: [String:JSONValue]
    let originalNatural: [String:JSONValue]
    let expectedSale: [String:JSONValue]
    let expectedNatural: [String:JSONValue]
    let expectedSplits: [[String:JSONValue]]
    let recordID: String
    let storeID: String
    let date: String
    let originalMorning: [CustomerChangeEvent]

    private static func numeric(_ value: JSONValue?) throws -> Int64 {
        switch value {
        case .exactInteger(let n)?: return n
        case .number(let n)?:
            if let v = Int64(exactly:n), abs(n) <= 9_007_199_254_740_991 { return v }
            fallthrough
        default: throw TXBillPlanError.conflict
        }
    }
    static func money(_ value: JSONValue?, signed: Bool = false) throws -> Int64 {
        let d: Double
        switch value {
        case .number(let n)?: d = n
        case .exactInteger(let n)?: d = Double(n)
        default: throw TXBillPlanError.conflict
        }
        guard d.isFinite, abs(d) <= 1_000_000, signed || d >= 0,
              abs(d*100 - (d*100).rounded()) < 0.00001 else { throw TXBillPlanError.conflict }
        return Int64((d*100).rounded())
    }
    static func partners(record: SyncEvent, records: [SyncEvent]) throws -> [String] {
        guard record.tableName == "store_daily_record", !record.isDeletion, record.rowVersion > 0,
              let raw = record.payload["receipt_splits_json"]?.text.data(using: .utf8) else { throw TXBillPlanError.scope }
        let splits = try JSONDecoder().decode([[String:JSONValue]].self, from: raw)
        guard !splits.isEmpty else { throw TXBillPlanError.scope }
        let ids = try splits.map { try numeric($0["partner_id"]) }
        guard ids.allSatisfy({ $0 > 0 }), Set(ids).count == ids.count else { throw TXBillPlanError.conflict }
        return try ids.map { id in
            let matches = records.filter { $0.tableName == "partner" && !$0.isDeletion && (try? numeric($0.payload["id"])) == id }
            guard matches.count == 1, UUID(uuidString:matches[0].syncId) != nil else { throw TXBillPlanError.conflict }
            return matches[0].syncId
        }
    }

    static func prepare(record: SyncEvent, records: [SyncEvent], events: [CustomerChangeEvent],
                        partner: String, batch: TXBillBatchPreview, channels: Set<TXBillPlatform>,
                        cursor: Int64) throws -> Self {
        let p = record.payload
        guard let day = p["date"]?.text, BusinessDates.parse(day) != nil,
              let store = p["store_sync_id"]?.text, UUID(uuidString:store) != nil,
              !channels.isEmpty, try partners(record:record, records:records).contains(partner),
              !TestBusinessEditPolicy.hasDerivedSettlement(date:day,events:records),
              let rawSplits = p["receipt_splits_json"]?.text.data(using:.utf8),
              let person = records.first(where: { $0.tableName == "partner" && $0.syncId == partner && !$0.isDeletion })
        else { throw TXBillPlanError.scope }
        let numericID = try numeric(person.payload["id"])
        let splits = try JSONDecoder().decode([[String:JSONValue]].self,from:rawSplits)
        guard let split = splits.first(where: { (try? numeric($0["partner_id"])) == numericID }) else { throw TXBillPlanError.scope }
        let names = ["wechat","alipay","cash"]
        var totals = [Int64](repeating:0,count:3)
        for row in splits {
            let values = try names.map { try money(row[$0+"_income"]) }
            guard try money(row["amount"]) == values.reduce(0,+) else { throw TXBillPlanError.conflict }
            for i in 0..<3 { totals[i] += values[i] }
        }
        guard totals == (try names.map { try money(p[$0+"_income"]) }) else { throw TXBillPlanError.conflict }
        let revenue = try money(p["revenue"]), profit = try money(p["profit"],signed:true)
        let opening = try money(p["opening_stock_value"]), closing = try money(p["stock_left_value"]), expense = try money(p["expense"])
        guard revenue == totals.reduce(0,+), profit == revenue+closing-opening-expense,
              try money(p["purchase_cost"] ?? .number(0)) == 0 else { throw TXBillPlanError.conflict }
        let pair = try TXBusinessV2DraftPolicy.sources(events,date:day,partner:partner)
        let candidates = events.filter { $0.kind == "NATURAL" && ($0.key == day+"|"+partner ||
            ($0.payload["date"] == .string(day) && $0.payload["partner_sync_id"] == .string(partner))) }
        guard candidates.count == 1, let natural = candidates.first, !natural.isDeleted, natural.revision > 0,
              natural.key == day+"|"+partner, natural.payload["date"] == .string(day),
              natural.payload["partner_sync_id"] == .string(partner), natural.payload["store_sync_id"] == .string(store) else {
            throw TXBillPlanError.source
        }
        var input = TXBusinessV2CollectorInput();input.partner = partner
        let source = natural.payload
        let raw = try names.map { try money(source[$0]) }
        let saved = try names.map { try money(source["saved_"+$0]) }
        guard case .bool(let wx)? = source["deduct_wechat"], case .bool(let ali)? = source["deduct_alipay"],
              case .bool(let cash)? = source["deduct_cash"] else { throw TXBillPlanError.source }
        input.wechat.natural = BusinessCommitPolicy.money(raw[0]);input.wechat.deduct = wx
        input.alipay.natural = BusinessCommitPolicy.money(raw[1]);input.alipay.deduct = ali
        input.cash.natural = BusinessCommitPolicy.money(raw[2]);input.cash.deduct = cash
        input.acknowledge(previous:pair.0.revision,following:pair.1.revision)
        let previous = try input.request(previousRevision:pair.0.revision,followingRevision:pair.1.revision)
        let actual = try TXBusinessV2Preparation.adjusted(previous,previous:pair.0.morning,following:pair.1.morning)
        guard actual == saved, actual == (try names.map { try money(split[$0+"_income"]) }) else { throw TXBillPlanError.source }
        let selected = channels.sorted { $0.rawValue < $1.rawValue }
        // V4 first candidate: whole single-day file only, exactly one collector.
        let wire = try TXBillV3Input.build(batch:batch,date:day,partner:partner,channels:selected,cursor:cursor)
        let audit = TXBillCloudAudit(events:events)
        let setting = events.first { $0.kind == "SETTINGS" && $0.key == "global" && !$0.isDeleted }
        let threshold = setting?.payload["large_payment_threshold"]?.decimal ?? 2000
        let excludeLarge = setting?.payload["exclude_large_payments"]?.isTrue ?? true
        guard threshold.isFinite, threshold >= 1 else { throw TXBillPlanError.conflict }
        if let setting {
            guard case .bool(_)? = setting.payload["exclude_large_payments"] else { throw TXBillPlanError.conflict }
        }
        let assignment = events.first { $0.kind == "ASSIGNMENT" && $0.key == day && !$0.isDeleted }
        guard assignment == nil || assignment?.payload["store_sync_id"] == .string(store) else { throw TXBillPlanError.conflict }
        let selectedRows = batch.rows.filter { channels.contains($0.platform) && $0.date == day }
        for platform in selected {
            let rows = selectedRows.filter { $0.platform == platform }
            let keys = Set(rows.map(\.id))
            guard !audit.hasOmittedKnownReceipts(date:day,platform:platform,partner:partner,included:keys) else { throw TXBillPlanError.conflict }
            for row in rows {
                let match = audit.match(row,selectedPartner:partner,fileHashes:batch.sourceHashes[row.id] ?? [])
                guard match == .new || match == .sameCollector else { throw TXBillPlanError.conflict }
                let state = events.first { $0.kind == "ANALYSIS" && $0.key == row.id && !$0.isDeleted }?.payload["state"]?.text
                if state == "EXCLUDED" || (state != "INCLUDED" && excludeLarge && Double(row.cents)/100 >= threshold) {
                    throw TXBillPlanError.excluded
                }
                if match == .sameCollector {
                    guard let existing = events.first(where: { $0.kind == "RECEIPT" && $0.key == row.id }),
                          existing.payload["store_sync_id"] == .string(store),
                          existing.payload["business_date"] == .string(day),
                          existing.payload["customer_key"] == .string(row.customerKey),
                          existing.payload["identity_confidence"] == .string(row.reliableIdentity ? "HIGH" : "LOW")
                    else { throw TXBillPlanError.conflict }
                }
            }
            let total = rows.reduce(Int64(0)) { $0 + $1.net }
            guard total >= 0 else { throw TXBillPlanError.incomplete }
            if platform == .wechat { input.wechat.natural = BusinessCommitPolicy.money(total) }
            else { input.alipay.natural = BusinessCommitPolicy.money(total) }
        }
        try wire.validate(date:day,partner:partner,channels:selected,cursor:cursor,
            receiptKeys:selectedRows.map(\.id).sorted(),fileHashes:Array(Set(wire.files.map(\.file_hash))).sorted(),target:input)
        let request = try input.request(previousRevision:pair.0.revision,followingRevision:pair.1.revision)
        let changed = try TXBusinessV2Preparation.adjusted(request,previous:pair.0.morning,following:pair.1.morning)
        guard changed[2] == actual[2], changed.reduce(0,+)>0 else { throw TXBillPlanError.conflict }
        let delta = changed.reduce(0,+)-actual.reduce(0,+)
        var updated = p, updatedNatural = source, updatedSplits = splits
        guard let index = splits.firstIndex(where: { (try? numeric($0["partner_id"])) == numericID }),
              record.rowVersion < Int64.max, natural.revision < Int64.max else { throw TXBillPlanError.conflict }
        func value(_ cents: Int64) -> JSONValue { .number(Double(cents)/100) }
        for i in 0..<2 where selected.contains(i == 0 ? .wechat : .alipay) {
            let key = names[i]
            updatedSplits[index][key+"_income"] = value(changed[i])
            updatedNatural[key] = value(try BusinessCollectorDraftPolicy.cents(i == 0 ? input.wechat.natural : input.alipay.natural,label:"账单"))
            updatedNatural["saved_"+key] = value(changed[i])
            updated[key+"_income"] = value(totals[i]+changed[i]-actual[i])
        }
        updatedSplits[index]["amount"] = value(changed.reduce(0,+))
        updated["revenue"] = value(revenue+delta); updated["profit"] = value(profit+delta)
        // Only compare parsed split arrays; server normalizes JSON key ordering.
        updated.removeValue(forKey:"receipt_splits_json")
        let followingDate = TXBusinessOvernightReferencePolicy.reference(events,businessDate:day,partner:partner)?.followingDate
        let morning = events.filter { $0.kind == "OVERNIGHT" &&
            ($0.key == day+"|"+partner || $0.key == (followingDate ?? "")+"|"+partner) }
        return Self(oldRevenue:revenue,newRevenue:revenue+delta,oldProfit:profit,newProfit:profit+delta,cash:totals[2],
            oldWechat:actual[0],newWechat:changed[0],oldAlipay:actual[1],newAlipay:changed[1],
            rowVersion:record.rowVersion,naturalRevision:natural.revision,previousRevision:pair.0.revision,followingRevision:pair.1.revision,
            bill:wire,originalSale:p,originalNatural:source,expectedSale:updated,expectedNatural:updatedNatural,expectedSplits:updatedSplits,
            recordID:record.syncId,storeID:store,date:day,originalMorning:morning)
    }
}

struct TXHistoryBillPreview: View {
    @EnvironmentObject private var model: LedgerViewModel
    let recordSyncID: String
    let bookID: String
    @State private var partner = ""
    @State private var choose = false
    @State private var reading = false
    @State private var batch: TXBillBatchPreview?
    @State private var confirmed: Set<TXBillPlatform> = []
    @State private var error: String?
    @State private var plan: TXHistoryBillPlan?
    @State private var prepared: TXHistoryBillV4Prepared?
    @State private var reserved: TXHistoryBillV4Prepared?
    @State private var allowed = false
    @State private var working = false
    @State private var verified = false
    @State private var storageFailed = false
    @State private var showSend = false
    private var record: SyncEvent? {
        guard model.selectedBookID == bookID else { return nil }
        return model.snapshot.records["store_daily_record|"+recordSyncID]
    }
    private var records: [SyncEvent] { Array(model.snapshot.records.values) }
    private var permitted: Bool {
        BusinessCorrectionEntryPolicy.allowed(book:model.selectedBook,record:record,events:records,cachedAuthorization:model.isUsingCachedAuthorization)
    }
    private var ready: Bool { permitted && !storageFailed && reserved == nil && !working &&
        model.customerOvernightCovered && model.customerAPIAvailable &&
        model.customerError == nil && !model.isCustomerBusy && !model.isBusy &&
        model.customerSyncedAt != nil && model.errorMessage == nil && model.lastSyncAt != nil }
    private func loadReservation() {
        guard model.selectedBookID == bookID else { return }
        do { reserved = try TXHistoryBillV4Outbox().load(bookID:bookID,recordID:recordSyncID) }
        catch { storageFailed = true;self.error = error.localizedDescription }
    }
    private func refreshOnline() async {
        guard !working,model.selectedBookID == bookID else { return }
        allowed = false
        let user = model.account?.username
        await model.refresh()
        guard model.selectedBookID == bookID, model.account?.username == user else { return }
        await model.refreshCustomer(forceFull:true)
        guard model.selectedBookID == bookID, model.account?.username == user else { return }
        allowed = (try? await model.checkHistoryBillV4(bookID:bookID)) == true
    }
    private func send() async {
        guard ready,allowed,let prepared,let batch,prepared.permits(model.account,model.selectedBook) else { return }
        working = true;error = nil
        defer { working = false;loadReservation() }
        do { try await model.submitHistoryBillV4(prepared,batch:batch);verified = true }
        catch { self.error = error.localizedDescription }
    }
    private func recover() async {
        guard !working,let reserved,reserved.permits(model.account,model.selectedBook) else { return }
        working = true;error = nil
        defer { working = false }
        do { try await model.recoverHistoryBillV4(reserved);verified = true }
        catch { self.error = error.localizedDescription }
    }
    private var operationSection: some View {
        Section(header:Text("历史补录联合操作")) {
            if let reserved {
                Text(verified ? "已回读确认" : "原请求已锁定；只能查询核对")
                    .foregroundColor(verified ? .green : .orange)
                if reserved.permits(model.account,model.selectedBook) {
                    Button("查询原联合操作") { Task { await recover() } }.disabled(working)
                } else { Text("需使用原提交账号查询").font(.caption).foregroundColor(.secondary) }
            } else if allowed,prepared != nil {
                Button("联合覆盖历史营业与客户交易") { showSend = true }.disabled(!ready || reading)
            } else {
                Text("V4 未授权或尚未生成补录方案。").font(.caption).foregroundColor(.secondary)
            }
            if working { ProgressView("正在核对…") }
        }
    }
    private var partners: Set<String> {
        guard let record else { return [] }
        return Set((try? TXHistoryBillPlan.partners(record:record,records:records)) ?? [])
    }
    private func money(_ amount: Int64) -> String { "¥"+BusinessCommitPolicy.money(amount) }
    private func selectFiles(_ result: Result<[URL],Error>) {
        guard ready,!partner.isEmpty,!reading else { return }
        plan = nil;prepared = nil;batch = nil;confirmed = [];error = nil
        do {
            let urls = try result.get()
            guard (1...12).contains(urls.count) else { throw TXBillCSVError.tooLarge }
            reading = true
            Task {
                defer { reading = false }
                do {
                    let value = try await Task.detached(priority:.userInitiated) {
                        try TXBillBatchPreview(urls.map { url in
                            let accessed = url.startAccessingSecurityScopedResource()
                            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                            let size = try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
                            guard size <= TXBillCSV.maximumBytes else { throw TXBillCSVError.tooLarge }
                            return try TXBillCSV.parse(fileName:url.lastPathComponent,data:Data(contentsOf:url,options:.mappedIfSafe))
                        })
                    }.value
                    guard ready else { return }
                    batch = value
                } catch { self.error = error.localizedDescription }
            }
        } catch { self.error = error.localizedDescription }
    }
    private func calculate() {
        plan = nil;prepared = nil;error = nil
        do {
            guard ready,let record,let batch else { throw TXBillPlanError.source }
            let result = try TXHistoryBillPlan.prepare(record:record,records:records,events:Array(model.customerSnapshot.events.values),
                partner:partner,batch:batch,channels:confirmed,cursor:model.customerCursor)
            guard let account = model.account, let book = model.selectedBook else { throw TXBillPlanError.scope }
            prepared = try TXHistoryBillV4Prepared.prepare(plan:result,account:account,book:book)
            plan = result
        } catch { self.error = error.localizedDescription }
    }
    private func line(_ title:String,_ before:Int64,_ after:Int64) -> some View {
        HStack { Text(title);Spacer();Text(money(before)+" → "+money(after)).font(.caption.monospacedDigit()) }
    }
    var body: some View {
        Form {
            Section(header:Text("历史营业 · 账单补录")) {
                Text((record?.payload["date"]?.text ?? "")+" · "+(record?.payload["store_name"]?.text ?? ""))
                Picker("账单收款人",selection:$partner) {
                    Text("请选择").tag("")
                    ForEach(model.snapshot.testPartners.filter { partners.contains($0.id) }) { Text($0.name).tag($0.id) }
                }.disabled(reading || batch != nil)
                Button("选择完整单日账单") { choose = true }.disabled(!ready || partner.isEmpty || reading)
                if batch != nil { Button("重新选择收款人或账单") { batch=nil;plan=nil;prepared=nil;confirmed=[];partner="" }.disabled(reading) }
                Button("刷新客户及凌晨来源") { Task { await refreshOnline() } }.disabled(reading || working || model.isBusy || model.isCustomerBusy)
                if !ready { Text("需要在线同步及可靠凌晨来源。").font(.caption).foregroundColor(.orange) }
                if let error { Text(error).font(.caption).foregroundColor(.red) }
            }
            if let batch {
                Section(header:Text("逐渠道核对完整账单")) {
                    ForEach(TXBillPlatform.allCases,id:\.self) { platform in
                        if batch.rows.contains(where:{ $0.platform == platform }) {
                            Toggle("确认"+platform.label+"账单完整",isOn:Binding(get:{confirmed.contains(platform)},set:{ enabled in
                                if enabled { confirmed.insert(platform) } else { confirmed.remove(platform) };plan=nil;prepared=nil
                            }))
                        }
                    }
                    Text("\(batch.files.count) 份文件 · \(batch.rows.count) 笔去重交易").font(.caption)
                    Button("计算历史补录差额",action:calculate).disabled(!ready || confirmed.isEmpty || reading)
                }
            }
            if let plan {
                Section(header:Text("调整后的收款与营业预览")) {
                    line("收款人微信",plan.oldWechat,plan.newWechat)
                    line("收款人支付宝",plan.oldAlipay,plan.newAlipay)
                    line("整摊现金不变",plan.cash,plan.cash)
                    line("营业额",plan.oldRevenue,plan.newRevenue)
                    line("利润",plan.oldProfit,plan.newProfit)
                    Text("原营业版本 \(plan.rowVersion)，自然日来源版本 \(plan.naturalRevision)。仅预览，不提交。").font(.caption).foregroundColor(.orange)
                    if let prepared {
                        DisclosureGroup("V4 请求指纹（未发送）") {
                            Text(prepared.hash).font(.caption2).textSelection(.enabled)
                        }.font(.caption2)
                    }
                }
            }
            operationSection
            Section { Text("仅授权测试账本。原营业身份、库存、费用、现金、其他收款人和结算保留；多日文件暂不支持联合覆盖。原操作不明时禁止重发。").font(.caption).foregroundColor(.secondary) }
        }
        .navigationTitle("历史账单补录")
        .task { loadReservation();await refreshOnline() }
        .alert("确认覆盖测试账本原营业？",isPresented:$showSend) {
            Button("确认联合保存") { Task { await send() } }
            Button("取消",role:.cancel) {}
        } message: { Text("仅更新所选电子渠道、营业额、利润及账单客户交易；原现金与结算保持不变。") }
        .fileImporter(isPresented:$choose,allowedContentTypes:[.commaSeparatedText,.plainText,.zip,UTType(filenameExtension:"xlsx") ?? .data],allowsMultipleSelection:true,onCompletion:selectFiles)
        .onChange(of:model.customerCursor) { _ in plan=nil;prepared=nil }
        .onChange(of:model.currentCursor) { _ in plan=nil;prepared=nil }
        .onChange(of:permitted) { value in if !value { plan=nil;prepared=nil;batch=nil;confirmed=[] } }
        .onChange(of:model.selectedBookID) { _ in allowed=false;plan=nil;prepared=nil;batch=nil;reserved=nil;confirmed=[];verified=false }
        .onChange(of:model.account?.username) { _ in allowed=false;plan=nil;prepared=nil;batch=nil;confirmed=[];verified=false;loadReservation() }
    }
}
