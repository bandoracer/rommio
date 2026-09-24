import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Collection: its description and games, restyled by components: a grid of
/// covers lit by the first game (Aurora) or numbered volumes (Riso).
struct CollectionDetailView: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let collection: RommCollectionDTO

    @State private var feature: CollectionDetailFeatureModel

    init(model: RommioAppModel, collection: RommCollectionDTO) {
        self.model = model
        self.collection = collection
        _feature = State(initialValue: model.makeCollectionDetailFeature(collection: collection))
    }

    var body: some View {
        let roms = feature.roms.sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
        ThemedScrollPage(style: .page) {
            VStack(alignment: .leading, spacing: 8) {
                Text(PlayFormat.games(max(collection.romCount, roms.count)))
                    .themeText(.section)
                    .foregroundStyle(theme.inkSecondary)
                if !collection.description.isEmpty {
                    Text(collection.description)
                        .themeText(.body, size: theme.isRiso ? 18 : 16)
                        .italic(theme.isRiso)
                        .foregroundStyle(theme.ink)
                }
            }
            if roms.isEmpty {
                EmptyStateView(title: feature.isLoading ? "Loading games" : "Nothing here yet", systemImage: "square.stack",
                               message: feature.isLoading ? nil : feature.errorMessage ?? "This collection has no games on your server yet.")
            } else {
                GameShelfList(games: roms.map { rom in (rom, model.artworkURL(path: rom.urlCover)) }, identifierPrefix: "collection.game")
            }
            if let staleMessage = feature.staleMessage, !roms.isEmpty {
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
        }
        .litBy(roms.first.flatMap { model.artworkURL(path: $0.urlCover) })
        .navigationTitle(collection.name)
        .accessibilityIdentifier("screen.collection")
        .task { await feature.refresh(forceRemote: false) }
        .refreshable { await feature.refresh(forceRemote: true) }
    }
}

/// Games of a collection: a cover grid (Aurora) or numbered volumes with a
/// printed cover (Riso). Each pushes its Game page.
struct GameShelfList: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let games: [(rom: RomDTO, url: URL?)]
    let identifierPrefix: String

    var body: some View {
        if theme.isRiso {
            VStack(spacing: 0) {
                PrintRule(weight: .heavy)
                ForEach(Array(games.enumerated()), id: \.element.rom.id) { index, game in
                    NavigationLink(value: AppDestination.game(game.rom)) {
                        HStack(alignment: .center, spacing: 14) {
                            Text("\(index + 1)")
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
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("\(identifierPrefix).\(game.rom.id)")
                }
            }
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: horizontalSizeClass == .regular ? 132 : 104), spacing: 14, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(games, id: \.rom.id) { game in
                    NavigationLink(value: AppDestination.game(game.rom)) {
                        VStack(alignment: .leading, spacing: 7) {
                            GameCover(url: game.url, title: game.rom.displayName, maxPointSize: 180)
                                .aspectRatio(3 / 4, contentMode: .fit)
                            Text(game.rom.displayName).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(2)
                            Text(game.rom.platformAndYear).themeText(.caption, size: 12).foregroundStyle(theme.inkSecondary).lineLimit(1)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("\(identifierPrefix).\(game.rom.id)")
                }
            }
        }
    }
}
