import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// A game in a collection, with its cover.
struct ShelfGame: Identifiable, Hashable {
    let rom: RomDTO
    let url: URL?
    var id: Int { rom.id }
}

/// Everything the Collection page draws, built once per change of its games.
struct CollectionContent: Equatable {
    let collection: RommCollectionDTO
    /// A–Z.
    var games: [ShelfGame] = []
    var staleMessage: String?
    var errorMessage: String?
    var isLoading = false

    var count: Int { max(collection.romCount, games.count) }

    @MainActor
    static func build(_ feature: CollectionDetailFeatureModel, model: RommioAppModel) -> CollectionContent {
        var content = CollectionContent(collection: feature.collection)
        content.games = feature.roms
            .sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
            .map { ShelfGame(rom: $0, url: model.artworkURL(path: $0.urlCover)) }
        content.staleMessage = feature.staleMessage
        content.errorMessage = feature.errorMessage
        // Loading only shows in an empty state: a refresh of a full page doesn't republish it.
        content.isLoading = feature.isLoading && content.games.isEmpty
        return content
    }
}

/// A collection's feature model and its page content.
@MainActor
final class CollectionPageModel {
    let feature: CollectionDetailFeatureModel
    let content: DerivedContent<CollectionContent>

    init(model: RommioAppModel, collection: RommCollectionDTO) {
        let feature = model.makeCollectionDetailFeature(collection: collection)
        self.feature = feature
        content = DerivedContent { [unowned model] in CollectionContent.build(feature, model: model) }
    }
}

/// Collection: its description and games, restyled by components: a grid of
/// covers lit by the first game (Aurora) or numbered volumes (Riso).
struct CollectionDetailView: View {
    let collection: RommCollectionDTO
    @State private var page: CollectionPageModel

    init(model: RommioAppModel, collection: RommCollectionDTO) {
        self.collection = collection
        _page = State(initialValue: CollectionPageModel(model: model, collection: collection))
    }

    var body: some View {
        CollectionLayout(content: page.content.value)
            .navigationTitle(collection.name)
            .accessibilityIdentifier("screen.collection")
            .task { await page.feature.refresh(forceRemote: false) }
            .refreshable { await page.feature.refresh(forceRemote: true) }
    }
}

/// The Collection page (both themes; the components carry the theme).
struct CollectionLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: CollectionContent

    var body: some View {
        ThemedScrollPage(style: .page) {
            CollectionHeading(count: content.count, description: content.collection.description)
            if content.games.isEmpty {
                EmptyStateView(title: content.isLoading ? "Loading games" : "Nothing here yet", systemImage: "square.stack",
                               message: content.isLoading ? nil : content.errorMessage ?? "This collection has no games on your server yet.")
            } else {
                GameShelfList(games: content.games, identifierPrefix: "collection.game")
            }
            if let staleMessage = content.staleMessage, !content.games.isEmpty {
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
        }
        .litBy(content.games.first?.url)
    }
}

private struct CollectionHeading: View {
    @Environment(\.rommioTheme) private var theme
    let count: Int
    let description: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(PlayFormat.games(count))
                .themeText(.section)
                .foregroundStyle(theme.inkSecondary)
            if !description.isEmpty {
                Text(description)
                    .themeText(.body, size: theme.isRiso ? 18 : 16)
                    .italic(theme.isRiso)
                    .foregroundStyle(theme.ink)
            }
        }
    }
}

/// Games of a collection: a cover grid (Aurora) or numbered volumes with a
/// printed cover (Riso). Each pushes its Game page; both are built as they
/// scroll into view.
struct GameShelfList: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let games: [ShelfGame]
    let identifierPrefix: String

    var body: some View {
        if theme.isRiso {
            LazyVStack(spacing: 0) {
                PrintRule(weight: .heavy)
                ForEach(Array(games.enumerated()), id: \.element.id) { index, game in
                    PushButton(value: AppDestination.game(game.rom)) {
                        RisoShelfVolume(number: index + 1, game: game)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("\(identifierPrefix).\(game.rom.id)")
                }
            }
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: horizontalSizeClass == .regular ? 132 : 104), spacing: 14, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(games) { game in
                    PushButton(value: AppDestination.game(game.rom)) {
                        AuroraShelfTile(game: game)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("\(identifierPrefix).\(game.rom.id)")
                }
            }
        }
    }
}

private struct RisoShelfVolume: View {
    @Environment(\.rommioTheme) private var theme
    let number: Int
    let game: ShelfGame

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Text("\(number)")
                .themeText(.statValue, size: 34)
                .foregroundStyle(theme.accent2)
                .frame(width: 34, alignment: .leading)
            GameCover(url: game.url, title: game.rom.displayName, maxPointSize: 96)
                .frame(width: 56, height: 75)
            VStack(alignment: .leading, spacing: 4) {
                Text(game.rom.displayName).themeText(.headline, size: 22).foregroundStyle(theme.ink).lineLimit(2)
                Text(game.rom.platformAndYear).themeText(.label, size: 10).foregroundStyle(theme.inkSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
        .contentShape(Rectangle())
    }
}

private struct AuroraShelfTile: View {
    @Environment(\.rommioTheme) private var theme
    let game: ShelfGame

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            GameCover(url: game.url, title: game.rom.displayName, maxPointSize: 180)
                .aspectRatio(3 / 4, contentMode: .fit)
            Text(game.rom.displayName).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(2)
            Text(game.rom.platformAndYear).themeText(.caption, size: 12).foregroundStyle(theme.inkSecondary).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}
