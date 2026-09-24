import SwiftUI
import RommioContract

/// Aurora Play: the games in progress, one room at a time and lit by the game in
/// focus (swipe between them), then recent saves as a lit gallery by day (Recent
/// sessions live on Stats). On a regular-width
/// display (iPhone Duo open) the room is one canvas: the cover and its caption
/// side by side across the fold, the other games in a row beneath.
struct AuroraPlayLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: PlayContent
    let actions: PlayActions
    @State private var focusID: Int?
    @State private var palettes: [URL: CoverPalette] = [:]

    private var games: [PlayGameItem] { content.continuePlaying }
    private var focused: PlayGameItem? { games.first { $0.id == focusID } ?? games.first }
    private var focusIndex: Int { games.firstIndex { $0.id == focused?.id } ?? 0 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.spacing.section) {
                if horizontalSizeClass == .regular {
                    AnyView(canvas)
                } else {
                    AnyView(compact)
                }
                AnyView(
                    AuroraRecentSaves(items: content.recentSaves, palettes: palettes, open: actions.openSave)
                        .padding(.horizontal, horizontalSizeClass == .regular ? 32 : theme.spacing.gutter)
                )
                AnyView(notices)
            }
            .padding(.bottom, 40)
            .frame(maxWidth: 1180)
            .frame(maxWidth: .infinity)
        }
        .themedPage(.room)
        .litBy(focused?.artworkURL)
        .coverPalettes(games.compactMap(\.artworkURL) + content.recentSaves.compactMap(\.artworkURL), into: $palettes)
    }

    // MARK: Compact: paged rooms

    @ViewBuilder
    private var compact: some View {
        VStack(alignment: .leading, spacing: 18) {
            SyncStatusLine(text: content.statusLine, offline: content.isOffline)
                .padding(.horizontal, theme.spacing.gutter)
            if !games.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 0) {
                        ForEach(games) { game in
                            room(game)
                                .padding(.horizontal, theme.spacing.gutter)
                                .containerRelativeFrame(.horizontal)
                                .id(game.id)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $focusID)
                // Keep the next room out of the safe area (iPhone Duo's vertical bar).
                .clipped()
                .accessibilityIdentifier("play.continue")
                if games.count > 1 {
                    PagerDots(colours: games.map { theme.mark(for: $0.artworkURL, in: palettes) }, current: focusIndex) { index in
                        withAnimation(.snappy) { focusID = games[index].id }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, -30)
                }
            }
        }
    }

    private func room(_ game: PlayGameItem) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            GlowingCover(url: game.artworkURL, title: game.rom.displayName, height: 270)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
                .padding(.bottom, 14)
                .onTapGesture { actions.open(game.rom) }
                .contextMenu { PlayGameMenu(game: game, actions: actions) }
            caption(game)
            actionRow(game)
                // Room for the button's glow inside the clipped pager.
                .padding(.bottom, 30)
        }
    }

    private func caption(_ game: PlayGameItem, large: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Continue playing").themeText(.section).foregroundStyle(theme.ink)
                Text(game.rom.platformName).themeText(.section).foregroundStyle(theme.inkSecondary).lineLimit(1)
            }
            DisplayText(text: game.rom.displayName, role: .title, size: large ? 48 : 36, lineLimit: 2)
            Text(game.playedLine ?? "Resume where you left off")
                .themeText(.secondary)
                .foregroundStyle(theme.inkSecondary)
            if let conflict = game.conflict {
                ConflictChip(item: conflict) { actions.chooseConflict(conflict) }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func actionRow(_ game: PlayGameItem) -> some View {
        HStack(spacing: 12) {
            Button { actions.resume(game.rom) } label: {
                Label("Resume", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityLabel("Resume \(game.rom.displayName)")
            .accessibilityIdentifier("play.resume.\(game.rom.id)")

            Button { actions.open(game.rom) } label: {
                Image(systemName: "info")
            }
            .buttonStyle(IconButtonStyle(size: 54))
            .accessibilityLabel("\(game.rom.displayName) details")
        }
    }

    // MARK: Regular: one canvas

    @ViewBuilder
    private var canvas: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack {
                SyncStatusLine(text: content.statusLine, offline: content.isOffline)
                Spacer()
                if games.count > 1 {
                    PagerDots(colours: games.map { theme.mark(for: $0.artworkURL, in: palettes) }, current: focusIndex) { index in
                        withAnimation(.smooth) { focusID = games[index].id }
                    }
                }
            }
            if let game = focused {
                HStack(alignment: .center, spacing: 44) {
                    GlowingCover(url: game.artworkURL, title: game.rom.displayName, height: 360)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 24)
                        .onTapGesture { actions.open(game.rom) }
                        .contextMenu { PlayGameMenu(game: game, actions: actions) }
                        .id(game.id)
                        .transition(.opacity)
                    VStack(alignment: .leading, spacing: 22) {
                        caption(game, large: true)
                        actionRow(game)
                    }
                    .frame(maxWidth: 380, alignment: .leading)
                }
                .animation(.smooth, value: game.id)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("play.continue")
            }
            if games.count > 1 {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(title: "Continue playing", detail: PlayFormat.games(games.count))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(games) { game in
                                Button { withAnimation(.smooth) { focusID = game.id } } label: {
                                    RoomCard(item: game, width: 176, selected: game.id == focused?.id)
                                }
                                .buttonStyle(.plain)
                                .contextMenu { PlayGameMenu(game: game, actions: actions) }
                                .accessibilityIdentifier("play.quickResume.\(game.rom.id)")
                            }
                        }
                        .padding(.vertical, 6)
                        .padding(.bottom, 12)
                    }
                    .scrollClipDisabled()
                }
            }
        }
        .padding(.horizontal, 32)
        .padding(.top, 4)
    }

    // MARK: Notices and empty

    @ViewBuilder
    private var notices: some View {
        Group {
            if let staleMessage = content.staleMessage {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Showing your saved library").themeText(.bodyEmphasis).foregroundStyle(theme.ink)
                        Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(3)
                    }
                } icon: {
                    Image(systemName: "icloud.slash").foregroundStyle(theme.wait)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .themeSurface()
            }
            if let error = content.errorMessage {
                Label(error, systemImage: "exclamationmark.circle")
                    .themeText(.secondary)
                    .foregroundStyle(theme.badText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("play.error")
            } else if content.isEmpty && !content.isLoading {
                EmptyStateView(title: "Nothing to continue yet", systemImage: "play.circle",
                               message: "Games you play show up here. Open the Library to start one.")
            }
        }
        .padding(.horizontal, horizontalSizeClass == .regular ? 32 : theme.spacing.gutter)
    }
}
