#if canImport(AppKit)
import AppKit
import SwiftUI
import XCTest
import RommioStackProbe
@testable import RommioContract
@testable import RommioFoundation
import RommioPlayerKit
@testable import RommioUI

/// How much main-thread stack building, laying out and drawing each page and
/// sheet takes (Debug, in an `NSHostingView`), in both themes, light and dark,
/// at iPhone (402×874, compact) and iPhone Duo open / iPad (1180×820, regular)
/// sizes, with realistic content. An iPhone gives its main thread about 1 MB;
/// each page must stay within `budget`.
@MainActor
final class StackBudgetTests: XCTestCase {
    /// The hard limit per page (a quarter of the device's main thread).
    static let budgetKB = 256
    /// Report only (`ROMMIO_STACK_REPORT_ONLY=1`) while measuring a baseline.
    static var enforce: Bool { ProcessInfo.processInfo.environment["ROMMIO_STACK_REPORT_ONLY"] != "1" }

    struct Variant: CustomStringConvertible {
        let theme: AppearanceTheme
        let dark: Bool
        let regular: Bool
        var size: CGSize { regular ? CGSize(width: 1180, height: 820) : CGSize(width: 402, height: 874) }
        var rommioTheme: RommioTheme { RommioTheme(theme, dark: dark) }
        var description: String { "\(theme.rawValue)|\(dark ? "dark" : "light")|\(regular ? "regular" : "compact")" }
    }

    static let variants: [Variant] = AppearanceTheme.allCases.flatMap { theme in
        [false, true].flatMap { dark in [false, true].map { Variant(theme: theme, dark: dark, regular: $0) } }
    }

    private var harness: StackModelHarness!
    private let pipeline = ArtworkPipeline(directory: nil, loader: { _ in throw URLError(.notConnectedToInternet) })

    override func setUp() async throws {
        _ = RommioFonts.registerIfNeeded()
        harness = try StackModelHarness()
    }

    override func tearDown() async throws {
        harness = nil
    }

    // MARK: Measuring

    private func stackUse<V: View>(_ view: V, _ variant: Variant) -> Int {
        let root = view
            .environment(\.rommioTheme, variant.rommioTheme)
            .environment(\.colorScheme, variant.dark ? .dark : .light)
            .environment(\.horizontalSizeClass, variant.regular ? .regular : .compact)
            .environment(\.artworkPipeline, pipeline)
            .environment(harness.appearance)
            .frame(width: variant.size.width, height: variant.size.height)
        rommio_stack_paint()
        let host = NSHostingView(rootView: root)
        let window = NSWindow(contentRect: CGRect(origin: .zero, size: variant.size), styleMask: .borderless, backing: .buffered, defer: true)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        host.display()
        let used = rommio_stack_high_water()
        window.contentView = nil
        return used
    }

    /// Measures one page in every variant, prints `STACK|page|theme|scheme|width|KB` rows and enforces the budget.
    private func measure<V: View>(_ page: String, _ make: (Variant) -> V) {
        for variant in Self.variants {
            let kb = stackUse(make(variant), variant) / 1024
            print("STACK|\(page)|\(variant)|\(kb)")
            if Self.enforce {
                XCTAssertLessThanOrEqual(kb, Self.budgetKB, "\(page) \(variant) uses \(kb) KB of main-thread stack")
            }
        }
    }

    /// What the host costs before any page: a hosting view, a navigation stack, a scroll view.
    func testFloorStack() {
        measure("floor-text") { _ in Text("Rommio") }
        measure("floor-navigation") { _ in NavigationStack { ScrollView { Text("Rommio") }.navigationTitle("Rommio") } }
    }

    // MARK: Tab pages

    func testPlayStack() {
        let actions = PlayActions(resume: { _ in }, open: { _ in }, openSave: { _ in }, chooseConflict: { _ in }, openCollection: { _ in },
                                  openDownloads: {}, startOptions: { _ in })
        measure("play") { variant in
            NavigationStack {
                if variant.theme == .riso {
                    RisoPlayLayout(content: StackFixtures.playContent, actions: actions)
                } else {
                    AuroraPlayLayout(content: StackFixtures.playContent, actions: actions)
                }
            }
        }
    }

    func testLibraryStack() {
        let actions = LibraryActions(openPlatform: { _ in }, openCollection: { _ in }, openGame: { _ in })
        measure("library") { variant in
            NavigationStack {
                if variant.theme == .riso {
                    RisoLibraryLayout(content: StackFixtures.libraryContent, actions: actions)
                } else {
                    AuroraLibraryLayout(content: StackFixtures.libraryContent, actions: actions)
                }
            }
        }
        measure("library-split") { _ in
            LibraryPage(model: harness.model, feature: harness.model.libraryFeature, collections: harness.model.collectionsFeature)
        }
    }

    func testSearchStack() {
        let actions = SearchActions(open: { _ in }, resume: { _ in })
        for query in ["", "metroid"] {
            measure(query.isEmpty ? "search-suggestions" : "search-results") { variant in
                NavigationStack {
                    if variant.theme == .riso {
                        RisoSearchLayout(content: StackFixtures.searchContent(query: query), actions: actions)
                    } else {
                        AuroraSearchLayout(content: StackFixtures.searchContent(query: query), actions: actions)
                    }
                }
            }
        }
    }

    func testStatsStack() {
        let actions = StatsActions(open: { _ in }, resume: { _ in })
        measure("stats") { variant in
            NavigationStack {
                if variant.theme == .riso {
                    RisoStatsLayout(content: StackFixtures.statsContent, actions: actions)
                } else {
                    AuroraStatsLayout(content: StackFixtures.statsContent, actions: actions)
                }
            }
        }
    }

    func testSettingsStack() {
        let model = harness.model
        measure("settings") { _ in SettingsPage(model: model, feature: model.settingsFeature) }
        measure("settings-profiles") { _ in NavigationStack { ProfilesSettingsPage(feature: model.settingsFeature) } }
        measure("settings-player") { _ in NavigationStack { PlayerSettingsPage(feature: model.settingsFeature) } }
        measure("settings-cores") { _ in NavigationStack { CoresSettingsPage(model: model, feature: model.settingsFeature) } }
        measure("settings-backup") { _ in NavigationStack { ProgressRecoveryView(service: model.settingsFeature.progressService) } }
    }

    func testShellStack() {
        measure("shell") { _ in AuthenticatedShell(model: harness.model) }
    }

    // MARK: Pushed pages

    func testPlatformStack() {
        measure("platform") { variant in
            NavigationStack {
                if variant.theme == .riso {
                    RisoPlatformLayout(content: StackFixtures.platformContent, filter: .constant(.all), order: .constant(.alphabetical))
                } else {
                    AuroraPlatformLayout(content: StackFixtures.platformContent, filter: .constant(.all), order: .constant(.alphabetical))
                }
            }
        }
    }

    func testCollectionStack() {
        measure("collection") { _ in
            NavigationStack { CollectionLayout(content: StackFixtures.collectionContent) }
        }
    }

    func testGameStack() {
        let actions = GameActions(perform: { _ in }, selectFile: { _ in }, browseSaves: {}, chooseConflict: {}, startOptions: {})
        measure("game") { variant in
            NavigationStack {
                if variant.theme == .riso {
                    RisoGameLayout(content: StackFixtures.gameContent, actions: actions)
                } else {
                    AuroraGameLayout(content: StackFixtures.gameContent, actions: actions)
                }
            }
        }
    }

    func testDownloadsStack() {
        let model = harness.model
        measure("downloads") { _ in NavigationStack { DownloadsPage(model: model, feature: model.downloadsFeature) } }
    }

    // MARK: Sheets

    func testSheetStacks() {
        let model = harness.model
        measure("sheet-conflict") { _ in ConflictSheet(item: StackFixtures.conflictItem(), keep: { _ in }) }
        measure("sheet-launch") { _ in LaunchChoiceSheet(choice: StackFixtures.launchChoice, start: { _ in }, dismiss: {}, retry: {}) }
        measure("sheet-saved-progress") { _ in
            SavedProgressSheet(gameTitle: "EarthBound 2", saves: StackFixtures.gameSaves(2), coverURL: StackFixtures.artwork(2), canPlay: true,
                               onResume: {}, onRename: { _, _ in }, onClose: {})
        }
        measure("sheet-state-browser") { _ in
            StateBrowserView(browser: StackFixtures.stateBrowser, onUseResume: {}, onLoadState: { _ in }, onDeleteState: { _ in }, onClose: {})
        }
        measure("sheet-access-options") { _ in AccessProfileSheet(model: model) }
        measure("sheet-setup-code") { _ in OneTouchSetupView(appModel: model) }
    }

    // MARK: Player

    /// The player's SwiftUI chrome over the touch surface (title strip, Menu) for
    /// NES, SNES and PlayStation at the variant's size. The surface itself is one UIKit
    /// view (no SwiftUI tree) and is not measured here.
    func testPlayerOverlayStack() {
        for family in ["nes", "snes", "psx"] {
            measure("player-overlay-\(family)") { variant in
                let insets = variant.regular ? PlayerViewportSafeAreaInsets(top: 24, bottom: 20, left: 0, right: 0)
                                             : PlayerViewportSafeAreaInsets(top: 62, bottom: 34, left: 0, right: 0)
                let layout = resolvePlayerStageLayout(containerSize: variant.size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
                                                      topAlignedInPortrait: true, reserveTouchSpace: true,
                                                      controls: PlayerControlPlan(family: family)).controls!
                let theme = resolvePlayerVisualTheme(controls: nil, familyOverride: family, appTheme: variant.rommioTheme)
                return ZStack {
                    theme.canvasColor
                    PlayerControlsChrome(layout: layout, theme: theme, title: "Super Metroid 1", detail: "Super Nintendo · 25 min played", onMenu: {})
                }
            }
        }
        measure("player-pause") { _ in
            PlayerPausePanel(title: "Super Metroid 1", platformSlug: "snes", runtimeName: "Snes9x",
                             actions: PlayerPauseActions(resume: {}, quickSave: {}, savedProgress: {}, controls: {}, sync: {}, reset: {}, leave: {}))
        }
    }

    // MARK: Setup

    func testSetupStacks() {
        let model = harness.model
        measure("welcome") { _ in NavigationStack { WelcomePage(onConnect: {}, onSetupCode: {}) } }
        measure("connect") { _ in NavigationStack { OnboardingFlowView(model: model, route: .serverAccess) } }
        measure("sign-in") { _ in NavigationStack { OnboardingFlowView(model: model, route: .login) } }
        measure("first-sync") { _ in FirstSyncPage(model: model) }
    }
}
#endif
