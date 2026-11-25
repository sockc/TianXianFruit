import Foundation
import SwiftUI

struct TXBasicEdit: Codable, Equatable {
    let table: String
    let sync_id: String
    let expected_row_version: Int64
    let name: String
    var default_unit: String? = nil
    var enabled: Int? = nil
    var address: String? = nil
    var latitude: String? = nil
    var longitude: String? = nil
    var default_start_time: String? = nil
    var default_end_time: String? = nil

    private static func minute(_ text: String?, end: Bool) -> Int? {
        guard let text else { return nil }
        if end && text == "24:00" { return 1440 }
        guard text.range(of: #"^(?:[01][0-9]|2[0-3]):[0-5][0-9]$"#, options: .regularExpression) != nil else { return nil }
        let p = text.split(separator: ":").compactMap { Int($0) }
        return p.count == 2 ? p[0] * 60 + p[1] : nil
    }
    private static func coordinate(_ text: String?, limit: Double) -> Bool {
        guard let text else { return false }
        if text.isEmpty { return true }
        guard text.range(of: #"^-?[0-9]{1,3}(\.[0-9]{1,8})?$"#, options: .regularExpression) != nil,
              let value = Double(text), value.isFinite else { return false }
        return abs(value) <= limit
    }
    var valid: Bool {
        guard expected_row_version > 0, !sync_id.isEmpty, !name.isEmpty, name.count <= 80,
              name == name.trimmingCharacters(in: .whitespacesAndNewlines) else { return false }
        if table == "fruit" {
            guard name != "总价", let unit = default_unit, !unit.isEmpty, unit.count <= 16,
                  unit == unit.trimmingCharacters(in: .whitespacesAndNewlines),
                  enabled == 0 || enabled == 1 else { return false }
            return address == nil && latitude == nil && longitude == nil && default_start_time == nil && default_end_time == nil
        }
        guard table == "store", let address, address.count <= 300,
              default_unit == nil, enabled == nil,
              Self.coordinate(latitude, limit: 90), Self.coordinate(longitude, limit: 180),
              (latitude == "") == (longitude == ""),
              let start = Self.minute(default_start_time, end: false),
              let end = Self.minute(default_end_time, end: true) else { return false }
        return end > start
    }
    func matches(_ row: SyncEvent) -> Bool {
        guard valid, row.tableName == table, row.syncId == sync_id,
              row.payload["name"]?.text == name else { return false }
        if table == "fruit" {
            return row.payload["default_unit"]?.text == default_unit &&
                TXFundDataAdapter.number(row.payload["enabled"]) == enabled.map(Double.init)
        }
        func coordinateMatches(_ key: String, _ wanted: String?) -> Bool {
            guard let wanted else { return false }
            if wanted.isEmpty { return row.payload[key] == .null }
            guard let value = Double(wanted), let actual = TXFundDataAdapter.number(row.payload[key]) else { return false }
            return abs(actual - value) < 0.000000001
        }
        return row.payload["address"]?.text == address?.trimmingCharacters(in: .whitespacesAndNewlines) &&
            row.payload["default_start_time"]?.text == default_start_time &&
            row.payload["default_end_time"]?.text == default_end_time &&
            coordinateMatches("latitude", latitude) && coordinateMatches("longitude", longitude)
    }
}

struct TXBasicEditorSelection: Identifiable {
    let event: SyncEvent
    let scope: String
    var id: String { event.logicalKey + "|" + String(event.rowVersion) }
}

struct TXBasicRecordEditor: View {
    @Environment(\.dismiss) private var dismiss
    let selection: TXBasicEditorSelection
    let onSave: (TXBasicEdit) -> Void
    @State private var name: String
    @State private var unit: String
    @State private var enabled: Bool
    @State private var address: String
    @State private var lat: String
    @State private var lon: String
    @State private var start: String
    @State private var end: String
    @State private var confirm = false

    init(selection: TXBasicEditorSelection, onSave: @escaping (TXBasicEdit) -> Void) {
        self.selection = selection; self.onSave = onSave
        let p = selection.event.payload
        _name = State(initialValue: p["name"]?.text ?? "")
        _unit = State(initialValue: p["default_unit"]?.text ?? "")
        _enabled = State(initialValue: TXFundDataAdapter.number(p["enabled"]) == 1)
        _address = State(initialValue: p["address"]?.text ?? "")
        // Keep the cloud coordinate precision; do not implicitly round on save.
        _lat = State(initialValue: p["latitude"]?.text ?? "")
        _lon = State(initialValue: p["longitude"]?.text ?? "")
        _start = State(initialValue: p["default_start_time"]?.text ?? "16:00")
        _end = State(initialValue: p["default_end_time"]?.text ?? "24:00")
    }
    private var proposal: TXBasicEdit {
        var p = TXBasicEdit(table: selection.event.tableName, sync_id: selection.event.syncId,
            expected_row_version: selection.event.rowVersion, name: name.trimmingCharacters(in: .whitespacesAndNewlines))
        if p.table == "fruit" { p.default_unit = unit.trimmingCharacters(in: .whitespacesAndNewlines); p.enabled = enabled ? 1 : 0 }
        else { p.address = address; p.latitude = lat; p.longitude = lon; p.default_start_time = start; p.default_end_time = end }
        return p
    }
    var body: some View {
        NavigationView {
            Form {
                Section("基础资料") {
                    TextField("名称", text: $name)
                    if selection.event.tableName == "fruit" {
                        TextField("默认单位", text: $unit)
                        Toggle("启用商品", isOn: $enabled)
                        Text("停用不会删除历史采购、库存或经营数据。").font(.caption).foregroundColor(.secondary)
                    } else {
                        TextField("地址", text: $address)
                        TextField("纬度（可空）", text: $lat).keyboardType(.numbersAndPunctuation)
                        TextField("经度（可空）", text: $lon).keyboardType(.numbersAndPunctuation)
                        TextField("开始时间，例如 16:00", text: $start).keyboardType(.numbersAndPunctuation)
                        TextField("结束时间，例如 24:00", text: $end).keyboardType(.numbersAndPunctuation)
                        Text("经纬度必须同时填写或同时清空；默认营业时间不能跨日。").font(.caption).foregroundColor(.secondary)
                    }
                }
                Section {
                    Button("保存并同步") { confirm = true }.disabled(!proposal.valid)
                    if !proposal.valid { Text("请检查名称、单位或位置资料格式。").font(.caption).foregroundColor(.orange) }
                }
            }
            .navigationTitle("编辑资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .confirmationDialog("确认修改云端基础资料？", isPresented: $confirm, titleVisibility: .visible) {
                Button("确认保存") { guard proposal.valid else { return }; onSave(proposal) }
                Button("取消", role: .cancel) {}
            }
        }.navigationViewStyle(.stack)
    }
}
