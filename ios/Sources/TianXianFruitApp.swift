import SwiftUI

@main
struct TianXianFruitApp: App {
    @StateObject private var model = LedgerViewModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .task { await model.restoreSession() }
        }
    }
}
