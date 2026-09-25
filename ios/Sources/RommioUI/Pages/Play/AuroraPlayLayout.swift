import SwiftUI
import RommioContract
import RommioFoundation

/// Aurora Play v2 (docs/theme-layouts.md, Play): the games in progress as a paged
/// carousel of their own screens — the latest resume point or save state as captured,
/// never art-treated — lit by the game in focus, its caption and actions, the rest of
/// the games in progress, then recent saves as a lit gallery by day. On a regular-width
/// display (iPhone Duo open, iPad) the room is one canvas: the screen beside its
/// caption, the other games in a row beneath.
struct AuroraPlayLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: PlayContent
    let actions: PlayActions
    @State private var focusID: Int?
    @State private var palettes: [URL: CoverPalette] = [:]

    var body: some View {
        let games = content.continuePlaying
        let focused = games.first { $0.id == focusID } ?? games.first
        let regular = horizontalSizeClass == .regular
        let gutter = regular ? 32 : theme.spacing.gutter
        ScrollView {
            VStack(alignment: .leading, spacing: theme.spacing.section) {
                if regular {
                    AuroraPlayCanvas(games: games, focused: focused, statusLine: content.statusLine, offline: content.isOffline,
                                     marks: marks(games), palettes: palettes, focusID: $focusID, actions: actions)
                } else {
                    AuroraPlayRooms(games: games, focused: focused, statusLine: content.statusLine, offline: content.isOffline,
                                    marks: marks(games), palettes: palettes, focusID: $focusID, actions: actions)
                }
                AuroraRecentSaves(days: content.recentSaveDays, count: content.recentSaves.count, palettes: palettes, actions: actions)
                    .padding(.horizontal, gutter)
                AuroraPlayNotices(staleMessage: content.staleMessage, errorMessage: content.errorMessage,
                                  showsEmpty: content.isEmpty && !content.isLoading)
                    .padding(.horizontal, gutter)
            }
            .padding(.bottom, 40)
            .frame(maxWidth: 1180)
            .frame(maxWidth: .infinity)
        }
        .themedPage(.room)
        .litBy(focused?.artworkURL)
        .coverPalettes(content.markURLs, into: $palettes)
    }

    /// The pager's palettes: each game's cover, when known.
    private func marks(_ games: [PlayGameItem]) -> [CoverPalette?] {
        games.map { game in game.artworkURL.flatMap { palettes[$0] } }
    }
}

/// Games in the carousel; any more go in the row under "In progress".
private let carouselLimit = 6

// MARK: Compact: a carousel of screens

private struct AuroraPlayRooms: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    let statusLine: String
    let offline: Bool
    let marks: [CoverPalette?]
    let palettes: [URL: CoverPalette]
    @Binding var focusID: Int?
    let actions: PlayActions
    /// Page width: the visible width less the leading gutter, the spacing and a 16 pt peek.
    @State private var pageWidth: CGFloat = 340

    private static let spacing: CGFloat = 12
    private static let peek: CGFloat = 16

    var body: some View {
        let carousel = Array(games.prefix(carouselLimit))
        let more = Array(games.dropFirst(carouselLimit))
        let gutter = theme.spacing.gutter
        VStack(alignment: .leading, spacing: 18) {
            SyncStatusLine(text: statusLine, offline: offline)
                .padding(.horizontal, gutter)
            if let focused {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: Self.spacing) {
                        ForEach(carousel) { game in
                            PlayScreen(game: game, width: pageWidth, glow: mark(game)) { conflict in actions.chooseConflict(conflict) }
                                .onTapGesture { actions.open(game.rom) }
                                .contextMenu { PlayGameMenu(game: game, actions: actions) }
                                .id(game.id)
                        }
                    }
                    .scrollTargetLayout()
                }
                .contentMargins(.horizontal, gutter, for: .scrollContent)
                .scrollTargetBehavior(.viewAligned)
                .scrollPosition(id: $focusID)
                // The screens' glow spills past the pager.
                .scrollClipDisabled()
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width in
                    pageWidth = max(200, width - gutter - Self.spacing - Self.peek)
                }
                .accessibilityIdentifier("play.continue")
                VStack(alignment: .leading, spacing: 18) {
                    AuroraPlayCaption(game: focused, large: false, actions: actions)
                    AuroraPlayActionRow(game: focused, actions: actions)
                }
                .padding(.top, -4)
                .padding(.horizontal, gutter)
                .id(focused.id)
                .transition(.opacity)
                .animation(.smooth(duration: 0.25), value: focused.id)
                if games.count > 1 {
                    InProgressRow(games: carousel, more: more, marks: marks, focusIndex: carousel.firstIndex { $0.id == focused.id } ?? 0,
                                  focusID: $focusID, actions: actions)
                        .padding(.top, 12)
                }
            }
        }
    }

    private func mark(_ game: PlayGameItem) -> Color {
        light.mark(for: game.artworkURL, in: palettes)
    }
}

/// "In progress": the carousel's pager dots at the header's end, then the games in
/// progress beyond the carousel as small lit tiles (each opens its Game page).
private struct InProgressRow: View {
    @Environment(\.rommioTheme) private var theme
    let games: [PlayGameItem]
    let more: [PlayGameItem]
    let marks: [CoverPalette?]
    let focusIndex: Int
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        let gutter = theme.spacing.gutter
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text("In progress").themeText(.section).foregroundStyle(theme.inkSecondary).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 12)
                LitPagerDots(marks: Array(marks.prefix(games.count)), current: focusIndex) { index in
                    withAnimation(.snappy) { focusID = games[index].id }
                }
                .frame(height: 24)
            }
            .padding(.horizontal, gutter)
            if !more.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(more) { game in
                            Button { actions.open(game.rom) } label: {
                                RoomCard(item: game, width: 152, height: 114, showsPlatform: false)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { PlayGameMenu(game: game, actions: actions) }
                            .accessibilityIdentifier("play.inProgress.\(game.rom.id)")
                        }
                    }
                    .padding(.vertical, 6)
                    .padding(.bottom, 12)
                }
                .contentMargins(.horizontal, gutter, for: .scrollContent)
            }
        }
    }
}

/// Page dots in each game's light.
private struct LitPagerDots: View {
    @Environment(\.pageLight) private var light
    let marks: [CoverPalette?]
    let current: Int
    let onSelect: (Int) -> Void

    var body: some View {
        PagerDots(colours: marks.map { light.mark(for: $0) }, current: current, onSelect: onSelect)
    }
}

/// A game's screen: its latest resume point (or save state) as captured, at its own
/// aspect on a black 4:3 screen with its frame glowing behind it; without one, the
/// cover centred on its blurred backdrop. Never art-treated. The sync state (or the
/// conflict) sits bottom-left in a dark glass chip.
struct PlayScreen: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let width: CGFloat
    let glow: Color
    /// A conflicted game's chip opens the Conflict sheet.
    var onConflict: ((ConflictItem) -> Void)? = nil

    var body: some View {
        let height = width * 3 / 4
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        ZStack {
            // The frame, blurred, glowing in the game's light.
            ScreenPicture(game: game, height: height)
                .frame(width: width * 0.9, height: height * 0.9)
                .blur(radius: 26)
                .opacity(theme.isDark ? 0.7 : 0.45)
                .offset(y: height * 0.08)
                .accessibilityHidden(true)
            ScreenPicture(game: game, height: height)
                .frame(width: width, height: height)
                .background(Color.black)
                .clipShape(shape)
                .overlay { shape.strokeBorder(Color.white.opacity(0.22), lineWidth: 0.5) }
                .overlay(alignment: .bottomLeading) { ScreenChip(game: game, onConflict: onConflict).padding(12) }
                .shadow(color: glow.opacity(theme.isDark ? 0.55 : 0.5), radius: 22, y: 14)
        }
        .frame(width: width, height: height)
        .contentShape(shape)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(game.rom.displayName), \(game.captionLine)")
        .accessibilityIdentifier("play.screen.\(game.rom.id)")
    }
}

private struct ScreenPicture: View {
    let game: PlayGameItem
    let height: CGFloat

    var body: some View {
        if let preview = game.previewURL {
            ZStack {
                Color.black
                ScreenFrame(url: preview)
            }
        } else {
            // No preview: the cover on its blurred backdrop.
            ZStack {
                Color.black
                if let url = game.artworkURL {
                    BlurredCover(url: url, dark: true)
                        .scaledToFill()
                        .opacity(0.9)
                }
                GameCover(url: game.artworkURL, title: game.rom.displayName, cornerRadius: 10, maxPointSize: height, shadow: true)
                    .frame(width: height * 0.72 * 0.75, height: height * 0.72)
            }
            .clipped()
        }
    }
}

/// A preview frame at its own aspect (pixel art stays crisp).
private struct ScreenFrame: View {
    let url: URL
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .aspectRatio(contentMode: .fit)
                    .transition(.opacity)
            }
        }
        .task(id: url) {
            let loaded = await SavePreviewCache.shared.image(url)
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.15)) { image = loaded } }
        }
    }
}

/// The screen's sync state in dark glass (§11 wording), or the conflict in `bad`.
private struct ScreenChip: View {
    let game: PlayGameItem
    let onConflict: ((ConflictItem) -> Void)?

    var body: some View {
        if let conflict = game.conflict {
            // "Two resume points": the way to the Conflict sheet (and Resume asks first).
            Button { onConflict?(conflict) } label: {
                chip(icon: "exclamationmark.triangle.fill", text: conflict.chipTitle, tone: DarkFrame.bad)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(conflict.prompt)
            .accessibilityIdentifier("play.conflict.\(game.rom.id)")
        } else if let latest = game.latest, latest.sync != .unknown {
            chip(icon: nil, text: latest.sync.label, tone: DarkFrame.tone(latest.sync))
        }
    }

    private func chip(icon: String?, text: String, tone: Color) -> some View {
        HStack(spacing: 6) {
            if let icon {
                Image(systemName: icon).font(.system(size: 11, weight: .bold))
            } else {
                Circle().frame(width: 7, height: 7)
            }
            Text(text).font(.system(size: 13, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(tone)
        .padding(.horizontal, 11)
        .frame(height: 28)
        .background(Capsule().fill(DarkFrame.glass.opacity(0.62)))
        .background(Capsule().fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
    }
}

private struct AuroraPlayCaption: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let large: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: large ? 10 : 6) {
            Text(game.rom.platformAndYear).themeText(.section).foregroundStyle(theme.inkSecondary).lineLimit(1)
            DisplayText(text: game.rom.displayName, role: .title, size: large ? 48 : 32, lineLimit: 2)
            Text(game.captionLine)
                .themeText(.secondary)
                .foregroundStyle(theme.inkSecondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }
}

private struct AuroraPlayActionRow: View {
    let game: PlayGameItem
    let actions: PlayActions

    var body: some View {
        HStack(spacing: 12) {
            Button { actions.resume(game.rom) } label: {
                Label("Resume", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityLabel("Resume \(game.rom.displayName)")
            .accessibilityIdentifier("play.resume.\(game.rom.id)")

            Button { actions.open(game.rom) } label: {
                Image(systemName: "info.circle").font(.system(size: 20, weight: .regular))
            }
            .buttonStyle(IconButtonStyle(size: 54))
            .accessibilityLabel("\(game.rom.displayName) details")
            .accessibilityIdentifier("play.details.\(game.rom.id)")
        }
    }
}

// MARK: Regular: one canvas

private struct AuroraPlayCanvas: View {
    @Environment(\.pageLight) private var light
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    let statusLine: String
    let offline: Bool
    let marks: [CoverPalette?]
    let palettes: [URL: CoverPalette]
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack {
                SyncStatusLine(text: statusLine, offline: offline)
                Spacer()
                if games.count > 1 {
                    LitPagerDots(marks: marks, current: games.firstIndex { $0.id == focused?.id } ?? 0) { index in
                        withAnimation(.smooth) { focusID = games[index].id }
                    }
                }
            }
            if let game = focused {
                AuroraPlayFeature(game: game, glow: light.mark(for: game.artworkURL, in: palettes), actions: actions)
            }
            if games.count > 1 {
                AuroraPlayRoomRow(games: games, focusedID: focused?.id, focusID: $focusID, actions: actions)
            }
        }
        .padding(.horizontal, 32)
        .padding(.top, 4)
    }
}

/// The game in focus across the canvas: its screen beside its caption and actions.
private struct AuroraPlayFeature: View {
    let game: PlayGameItem
    let glow: Color
    let actions: PlayActions
    @State private var canvasWidth: CGFloat = 1100

    var body: some View {
        // The screen takes what the caption (up to 380) leaves, 320–600 wide.
        let screenWidth = min(600, max(320, canvasWidth - 44 - 340))
        HStack(alignment: .center, spacing: 44) {
            PlayScreen(game: game, width: screenWidth, glow: glow) { conflict in actions.chooseConflict(conflict) }
                .onTapGesture { actions.open(game.rom) }
                .contextMenu { PlayGameMenu(game: game, actions: actions) }
                .id(game.id)
                .transition(.opacity)
            VStack(alignment: .leading, spacing: 22) {
                AuroraPlayCaption(game: game, large: true, actions: actions)
                AuroraPlayActionRow(game: game, actions: actions)
            }
            .frame(maxWidth: 380, alignment: .leading)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { canvasWidth = $0 }
        .animation(.smooth, value: game.id)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("play.continue")
    }
}

/// The games in progress as small lit cards: tap focuses one, press and hold for its menu.
private struct AuroraPlayRoomRow: View {
    let games: [PlayGameItem]
    let focusedID: Int?
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Continue playing", detail: PlayFormat.games(games.count))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 14) {
                    ForEach(games) { game in
                        Button { withAnimation(.smooth) { focusID = game.id } } label: {
                            RoomCard(item: game, width: 176, selected: game.id == focusedID)
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

// MARK: Notices and empty

private struct AuroraPlayNotices: View {
    @Environment(\.rommioTheme) private var theme
    let staleMessage: String?
    let errorMessage: String?
    let showsEmpty: Bool

    var body: some View {
        Group {
            if let staleMessage {
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
            if let errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.circle")
                    .themeText(.secondary)
                    .foregroundStyle(theme.badText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("play.error")
            } else if showsEmpty {
                EmptyStateView(title: "Nothing to continue yet", systemImage: "play.circle",
                               message: "Games you play show up here. Open the Library to start one.")
            }
        }
    }
}
