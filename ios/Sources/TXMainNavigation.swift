import SwiftUI

/// Matches Android's primary six tabs while avoiding SwiftUI TabView's
/// automatic overflow "More" tab on iPhone.
enum TXMainTab: String, CaseIterable, Identifiable {
    case home, inventory, purchase, business, settlement, more

    var id: String { rawValue }
    var title: String {
        switch self {
        case .home: return "首页"
        case .inventory: return "库存"
        case .purchase: return "采购"
        case .business: return "营业"
        case .settlement: return "结算"
        case .more: return "更多"
        }
    }
    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .inventory: return "shippingbox"
        case .purchase: return "cart"
        case .business: return "square.and.pencil"
        case .settlement: return "doc.text"
        case .more: return "line.3.horizontal"
        }
    }
}

struct TXMainNavigationBar: View {
    @Binding var selected: TXMainTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(TXMainTab.allCases) { tab in
                Button { selected = tab } label: {
                    VStack(spacing: 4) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 18, weight: selected == tab ? .semibold : .regular))
                        Text(tab.title)
                            .font(.system(size: 10, weight: selected == tab ? .semibold : .regular))
                    }
                    .foregroundColor(selected == tab ? TXTheme.primary : .secondary)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(selected == tab ? .isSelected : [])
            }
        }
        .padding(.horizontal, 3)
        .padding(.top, 4)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .overlay(alignment: .top) { Divider() }
    }
}
