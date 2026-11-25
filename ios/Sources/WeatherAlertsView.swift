import SwiftUI

/// A seven-day forecast bound to an already-configured cloud stall location.
/// Forecasts are intentionally not inserted in the ledger's local SQLite.
struct WeatherAlertsView: View {
    @EnvironmentObject private var model: LedgerViewModel
    @AppStorage("tianxian-ios-weather-notifications-enabled") private var noticesEnabled = false
    @State private var notificationStatus: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("未来7天 · 只提醒降雨和降温", systemImage: "cloud.rain")
                    .font(.subheadline.bold())
                HStack {
                    if model.snapshot.weatherLocations.isEmpty {
                        Text("营业位置尚未同步经纬度")
                            .font(.caption).foregroundColor(.orange)
                    } else {
                        Picker("营业位置", selection: Binding(
                            get: { model.selectedWeatherStoreID },
                            set: { model.chooseWeatherLocation($0) }
                        )) {
                            ForEach(model.snapshot.weatherLocations) { store in
                                Text(store.name).tag(store.id)
                            }
                        }
                        .pickerStyle(MenuPickerStyle())
                    }
                    Spacer()
                    Button {
                        Task { await updateForecast() }
                    } label: {
                        if model.weatherBusy {
                            ProgressView()
                        } else {
                            Label("更新预报", systemImage: "arrow.clockwise")
                                .font(.caption)
                        }
                    }
                    .disabled(model.weatherBusy || model.selectedWeatherLocation == nil)
                }
                if let message = model.weatherError {
                    Text(message).font(.caption).foregroundColor(.orange)
                }
                if let headline = model.weatherHeadline {
                    Label(headline, systemImage: "exclamationmark.triangle")
                        .font(.subheadline.bold())
                        .foregroundColor(.orange)
                }
                if model.weatherRisks.isEmpty {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("暂未取得未来7天预报").font(.subheadline.bold())
                        Text("选择已保存经纬度的位置后联网读取天气；不会虚构未来7天的天气。")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    .padding(12)
                } else {
                    LazyVStack(spacing: 8) {
                        ForEach(model.weatherRisks) { risk in
                            HStack(spacing: 10) {
                                Image(systemName: risk.rain ? "cloud.rain.fill" :
                                    (risk.cooling ? "thermometer.snowflake" : "cloud.sun"))
                                    .foregroundColor(risk.isAlert ? .orange : .green)
                                    .frame(width: 26)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(risk.day.date).font(.subheadline.bold())
                                    Text(risk.label)
                                        .font(.caption)
                                        .foregroundColor(risk.isAlert ? .orange : .secondary)
                                }
                                Spacer()
                                VStack(alignment: .trailing, spacing: 4) {
                                    Text(temperature(risk.day.tempMax) + " / " +
                                         temperature(risk.day.tempMin))
                                        .font(.caption)
                                    if let probability = risk.day.precipitationProbability {
                                        Text("降雨概率 " + String(format: "%.0f%%", probability))
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                            }
                            .padding(11)
                            .background(Color(UIColor.secondarySystemGroupedBackground),
                                        in: RoundedRectangle(cornerRadius: 11))
                        }
                    }
                    if model.weatherRisks.count < 7 {
                        Text("天气服务目前只提供了 \(model.weatherRisks.count) 天有效预报，其余日期不填充猜测值。")
                            .font(.caption2).foregroundColor(.orange)
                    }
                }
                Divider()
                Toggle("允许本机经营天气提醒", isOn: Binding(
                    get: { noticesEnabled },
                    set: { enabled in
                        if !enabled {
                            noticesEnabled = false
                            WeatherNotificationScheduler.clear()
                            notificationStatus = "已关闭本机天气通知"
                        } else {
                            Task { await enableNotifications() }
                        }
                    }
                ))
                if let status = notificationStatus {
                    Text(status).font(.caption).foregroundColor(.secondary)
                }
                if let checked = model.weatherCheckedAt {
                    Text("最近获取：" + checked.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption2).foregroundColor(.secondary)
                }
                if let attribution = model.weatherOverview?.attribution, !attribution.isEmpty {
                    Text("天气数据：" + attribution).font(.caption2).foregroundColor(.secondary)
                }
                Text("通知只在手动或打开 App 刷新预报后，按当时结果安排风险当天15:00的提醒；App 关闭期间不会持续后台检查天气变化。降温按相邻两天最高气温下降至少5°C判断，不能代表营业时段的即时温差。")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding(12)
        }
        .navigationBarTitle("经营天气", displayMode: .inline)
        .onAppear { Task { await model.refreshWeather() } }
    }

    private func temperature(_ value: Double?) -> String {
        guard let value else { return "--" }
        return String(format: "%.0f°C", value)
    }

    private func updateForecast() async {
        await model.refreshWeather(force: true)
        if noticesEnabled && model.weatherError == nil {
            await scheduleNotifications()
        }
    }

    private func enableNotifications() async {
        guard !model.weatherRisks.isEmpty else {
            notificationStatus = "请先成功获取至少一天的天气预报"
            return
        }
        do {
            let count = try await WeatherNotificationScheduler.schedule(model.weatherRisks)
            noticesEnabled = true
            notificationStatus = "已根据当前预报安排 \(count) 条风险天气提醒"
        } catch {
            noticesEnabled = false
            notificationStatus = error.localizedDescription
        }
    }

    private func scheduleNotifications() async {
        do {
            let count = try await WeatherNotificationScheduler.schedule(model.weatherRisks)
            notificationStatus = "已按新预报更新 \(count) 条提醒"
        } catch {
            noticesEnabled = false
            notificationStatus = error.localizedDescription
        }
    }
}

struct WeatherHomeSummary: View {
    @EnvironmentObject private var model: LedgerViewModel

    var body: some View {
        NavigationLink(destination: WeatherAlertsView()) {
            HStack(spacing: 10) {
                Image(systemName: model.weatherRisks.contains(where: { $0.rain }) ?
                      "cloud.rain.fill" : "cloud.sun.fill")
                    .foregroundColor(model.weatherHeadline == nil ? .green : .orange)
                VStack(alignment: .leading, spacing: 3) {
                    Text("未来7天经营天气").font(.subheadline.bold())
                    Text(model.weatherHeadline ?? (model.weatherRisks.isEmpty ?
                         "尚未加载预报 · 点击查看" : "当前没有雨天或明显降温提示"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(11)
            .background(Color(UIColor.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 11))
        }
        .buttonStyle(PlainButtonStyle())
        .onAppear { Task { await model.refreshWeather() } }
    }
}
