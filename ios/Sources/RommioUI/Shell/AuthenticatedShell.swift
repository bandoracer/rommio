import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// One navigation structure for every theme: Play · Library · Search · Stats ·
/// Settings. Search uses the system search role, which iOS 26 draws as its own
/// button at the trailing end of the tab bar.
struct AuthenticatedShell: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel

    var body: some View {
        // Sidebar-adaptable tabs become the vertical bar on iPhone Duo's outer
        // display and can expand into a sidebar on the regular-width inner display.
        // Erase each page's large SwiftUI type before TabView combines all tabs;
        // the un-erased tree exhausted the main thread stack on a physical iPhone.
        TabView(selection: bindable.selectedTab) {
            Tab(value: RommioAppModel.ShellTab.play) {
                AnyView(PlayPage(model: model, feature: model.playFeature))
            } label: {
                TabGlyph(title: "Play", systemImage: "play.circle", identifier: "play.circle")
            }

            Tab(value: RommioAppModel.ShellTab.library) {
                AnyView(LibraryPage(model: model, feature: model.libraryFeature, collections: model.collectionsFeature))
            } label: {
                TabGlyph(title: "Library", systemImage: "books.vertical", identifier: "books.vertical")
            }

            Tab(value: RommioAppModel.ShellTab.search, role: .search) {
                AnyView(SearchPage(model: model, feature: model.searchFeature))
            }

            Tab(value: RommioAppModel.ShellTab.stats) {
                AnyView(StatsPage(model: model, feature: model.statsFeature))
            } label: {
                TabGlyph(title: "Stats", systemImage: "chart.line.uptrend.xyaxis", identifier: "chart.bar")
            }

            Tab(value: RommioAppModel.ShellTab.settings) {
                AnyView(SettingsPage(model: model, feature: model.settingsFeature))
            } label: {
                TabGlyph(title: "Settings", systemImage: "gearshape", identifier: "gearshape")
            }

        }
        .tabViewStyle(.sidebarAdaptable)
        // Leading side: the trailing edge holds iPhone Duo's vertical bar.
        .overlay(alignment: .bottomLeading) {
            if model.isRefreshing {
                ProgressView()
                    .padding(12)
                    .themeSurface(radius: 22, strong: true)
                    .padding()
                    .accessibilityLabel("Refreshing library")
            }
        }
    }

    private var bindable: Bindable<RommioAppModel> {
        Bindable(model)
    }
}

/// A tab's title and line glyph. The tab bar would otherwise switch to the filled
/// variant, which reads heavy next to the mockups' line icons.
private struct TabGlyph: View {
    let title: String
    let systemImage: String
    /// The tab's identifier from before the line glyphs (the tab bar used the symbol name).
    let identifier: String

    var body: some View {
        Label(title, systemImage: systemImage)
            .environment(\.symbolVariants, .none)
            .accessibilityIdentifier(identifier)
    }
}
