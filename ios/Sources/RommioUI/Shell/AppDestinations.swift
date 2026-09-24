import SwiftUI
import RommioContract
import RommioFoundation

/// Pushed pages, reached the same way from every tab and in every theme.
enum AppDestination: Hashable {
    case game(RomDTO)
    case platform(PlatformDTO)
    case collection(RommCollectionDTO)
    case downloads
}

private struct AppDestinationsModifier: ViewModifier {
    let model: RommioAppModel

    func body(content: Content) -> some View {
        content.navigationDestination(for: AppDestination.self) { destination in
            switch destination {
            case let .game(rom):
                GameDetailView(model: model, rom: rom)
            case let .platform(platform):
                PlatformDetailView(model: model, platform: platform)
            case let .collection(collection):
                CollectionDetailView(model: model, collection: collection)
            case .downloads:
                DownloadsPage(model: model, feature: model.downloadsFeature)
            }
        }
    }
}

extension View {
    /// Registers the shared pushed pages on a tab's navigation stack.
    func appDestinations(_ model: RommioAppModel) -> some View {
        modifier(AppDestinationsModifier(model: model))
    }
}
