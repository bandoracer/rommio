import SwiftUI
import RommioContract
import RommioFoundation

/// Aurora Game: the cover fills the top of the page and the page takes its
/// light; the title, where the game stands and the main action follow, then the
/// game's saves as a timeline, its file and its story. On a regular-width
/// display the hero spans the fold, with progress in a glass panel beside the
/// title and the timeline running the full width.
struct AuroraGameLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.displayScale) private var displayScale
    let content: GameContent
    let actions: GameActions
    /// The tone of the art under the status bar, and whether it has scrolled away.
    @State private var artTop: ColorScheme?
    @State private var scrolledPastArt = false
    @State private var heroSize = CGSize(width: 402, height: 400)
    @State private var topInset: CGFloat = 62

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                GameArtHero(url: content.artworkURL, title: content.rom.displayName)
                    .accessibilityIdentifier("gameDetail.hero")
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { heroSize = $0 }
                Group {
                    if horizontalSizeClass == .regular {
                        AuroraGameRegular(content: content, actions: actions)
                    } else {
                        AuroraGameCompact(content: content, actions: actions)
                    }
                }
                .padding(.horizontal, horizontalSizeClass == .regular ? 32 : theme.spacing.gutter)
                .padding(.top, -64)
                .padding(.bottom, 40)
                .frame(maxWidth: 1100)
                .frame(maxWidth: .infinity)
            }
        }
        .ignoresSafeArea(edges: .top)
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y > 120 } action: { _, past in scrolledPastArt = past }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { topInset = $0 }
        // Over the art: no scroll-edge fade, and the status bar in the art's tone.
        .modifier(TopEdgeEffectHidden(hidden: !scrolledPastArt))
        .statusBarText(over: scrolledPastArt ? nil : artTop)
        .task(id: "\(content.artworkURL?.absoluteString ?? "")#\(Int(heroSize.height))") {
            let hero = heroSize, inset = topInset
            artTop = await ArtTone.scheme(of: content.artworkURL, pipeline: pipeline, maxPixelSize: Int(800 * displayScale)) { image in
                GameArtHero.statusBarBand(image: image, hero: hero, topInset: inset)
            }
        }
        .themedPage(.page)
        .litBy(content.artworkURL)
    }
}

private struct AuroraGameCompact: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        VStack(alignment: .leading, spacing: theme.spacing.section) {
            AuroraGameHeading(content: content, large: false)
            GameFilePicker(shows: content.showsFilePicker, options: content.fileOptions, selectedFileID: content.selectedFileID, select: actions.selectFile)
            GameActionDeck(deck: content.deck, fileID: content.fileID, showsStartOptions: content.showsStartOptions, actions: actions)
            AuroraGameTimeline(content: content, actions: actions)
            AuroraGameDetails(items: content.details)
            GameScreenshotsRow(urls: content.screenshots, gutter: theme.spacing.gutter)
                .padding(.horizontal, -theme.spacing.gutter)
            AuroraGameFile(file: content.file, availabilityTone: content.availabilityTone, playabilityTone: content.playabilityTone)
            AuroraGameAbout(hero: content.hero, preserved: content.preserved)
        }
    }
}

private struct AuroraGameRegular: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        VStack(alignment: .leading, spacing: theme.spacing.section) {
            HStack(alignment: .top, spacing: 36) {
                VStack(alignment: .leading, spacing: 22) {
                    AuroraGameHeading(content: content, large: true)
                    GameFilePicker(shows: content.showsFilePicker, options: content.fileOptions, selectedFileID: content.selectedFileID, select: actions.selectFile)
                    GameActionDeck(deck: content.deck, fileID: content.fileID, showsStartOptions: content.showsStartOptions, actions: actions)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 18) {
                    AuroraProgressPanel(content: content)
                    AuroraGameFile(file: content.file, availabilityTone: content.availabilityTone, playabilityTone: content.playabilityTone)
                }
                .frame(width: 340)
            }
            AuroraGameTimeline(content: content, actions: actions)
            HStack(alignment: .top, spacing: 36) {
                AuroraGameDetails(items: content.details).frame(maxWidth: .infinity)
                GameScreenshotsRow(urls: content.screenshots, gutter: 0).frame(maxWidth: .infinity, alignment: .leading)
            }
            AuroraGameAbout(hero: content.hero, preserved: content.preserved)
        }
    }
}

// MARK: Heading

private struct AuroraGameHeading: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let large: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(content.platformLine).themeText(.section).foregroundStyle(theme.ink)
                Text(content.tier.title).themeText(.section).foregroundStyle(theme.inkSecondary)
            }
            DisplayText(text: content.rom.displayName, role: .display, size: large ? 52 : 44, lineLimit: 3)
            FlowRow(spacing: 8) {
                StatusChip(title: content.hero.statusText, tone: content.statusTone)
                if let sync = content.resumeSync {
                    SyncBadge(state: sync, detail: "resume")
                }
                if let count = content.savesCount {
                    StatusChip(title: count, tone: .neutral)
                }
            }
            if let line = content.playLine {
                Text(line).themeText(.secondary).foregroundStyle(theme.inkSecondary)
            }
            if let support = content.hero.supportMessage {
                Text(support).themeText(.secondary).foregroundStyle(theme.inkSecondary)
            }
        }
    }
}

// MARK: Saved progress

private struct AuroraGameTimeline: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        if content.showsSavedProgress {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Saved progress", detail: content.timeline.isEmpty ? nil : "\(content.timeline.count)")
                if content.timeline.isEmpty {
                    AuroraNoSaves(status: content.savedStatus)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 0) {
                            ForEach(Array(content.timeline.enumerated()), id: \.element.id) { index, item in
                                Button(action: actions.browseSaves) {
                                    TimelineStop(item: item, coverURL: content.artworkURL, gameTitle: content.rom.displayName,
                                                 first: index == 0, last: index == content.timeline.count - 1)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.bottom, 12)
                    }
                    .scrollClipDisabled()
                }
                if let conflict = content.conflict {
                    GameConflictProblem(item: conflict, action: actions.chooseConflict)
                } else if !content.usesProgressStore, let problem = content.savedSummary?.problemText {
                    Label(problem, systemImage: "exclamationmark.triangle.fill")
                        .themeText(.secondary)
                        .foregroundStyle(theme.wait)
                }
                Button("Browse saved progress", systemImage: "square.stack.3d.up", action: actions.browseSaves)
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("gameDetail.browseStates")
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("game.savedProgress")
        }
    }
}

private struct AuroraNoSaves: View {
    @Environment(\.rommioTheme) private var theme
    let status: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(status).themeText(.bodyEmphasis).foregroundStyle(theme.ink)
            Text("Play to make a resume point; save states appear here as you make them.")
                .themeText(.secondary).foregroundStyle(theme.inkSecondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .themeSurface()
    }
}

// MARK: Details

private struct AuroraGameDetails: View {
    let items: [GameDetailItem]

    var body: some View {
        if !items.isEmpty {
            ThemedGroup(title: "Details") {
                GameDetailsList(items: items)
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("game.details")
        }
    }
}

/// Duo open: the progress kinds in a glass panel beside the title.
private struct AuroraProgressPanel: View {
    let content: GameContent

    var body: some View {
        if content.showsSavedProgress {
            ThemedGroup(title: "Progress", detail: content.savesCount, inset: 58) {
                ForEach(content.timeline.prefix(3)) { item in
                    ListRow(title: item.kindTitle, subtitle: [item.date.map { PlayFormat.relative($0) }, item.device].compactMap { $0 }.joined(separator: " · "),
                            leading: {
                                if let entry = item.entry {
                                    SavePreviewThumb(entry: entry, coverURL: content.artworkURL, title: content.rom.displayName, width: 40)
                                } else {
                                    IconTile(systemImage: item.symbol, tone: item.kind == .resume ? .wait : .live)
                                }
                            },
                            trailing: { if let sync = item.sync { SyncBadge(state: sync) } })
                }
                if content.timeline.isEmpty {
                    ListRow(title: content.savedStatus)
                }
            }
        }
    }
}

// MARK: File and story

private struct AuroraGameFile: View {
    @Environment(\.rommioTheme) private var theme
    let file: GameDetailSelectedFilePresentation?
    let availabilityTone: StatusTone
    let playabilityTone: StatusTone

    var body: some View {
        if let file {
            ThemedGroup(title: "File", inset: 58) {
                ListRow(title: file.title, subtitle: file.metaLine, leading: { IconTile(systemImage: "doc.fill", tone: .neutral) })
                FlowRow(spacing: 8) {
                    StatusChip(title: file.availabilityLabel, tone: availabilityTone)
                    StatusChip(title: file.playabilityLabel, tone: playabilityTone)
                }
                ForEach([file.downloadStatusText, file.progressDetailText, file.readinessMessage].compactMap { $0 }.filter { !$0.isEmpty }, id: \.self) { line in
                    Text(line).themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
                if let error = file.errorMessage, !error.isEmpty {
                    Text(error).themeText(.caption).foregroundStyle(theme.badText)
                }
            }
        }
    }
}

private struct AuroraGameAbout: View {
    @Environment(\.rommioTheme) private var theme
    let hero: GameDetailHeroPresentation
    let preserved: GameDetailPreservedContentPresentation?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let summary = hero.summaryText {
                SectionHeader(title: "About")
                Text(summary)
                    .themeText(.body)
                    .foregroundStyle(theme.ink)
                    .lineLimit(10)
            }
            Text(hero.fileCountText).themeText(.caption).foregroundStyle(theme.inkTertiary)
            if let preserved {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Showing saved details").themeText(.bodyEmphasis).foregroundStyle(theme.ink)
                        Text(preserved.message).themeText(.caption).foregroundStyle(theme.inkSecondary)
                    }
                } icon: {
                    Image(systemName: "icloud.slash").foregroundStyle(theme.wait)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .themeSurface()
            }
        }
    }
}

/// One stop on the saves timeline: when, a node on the line, and the save's frame
/// (letterboxed on the player ground, never treated; the cover when there is none;
/// a lit panel with the kind's symbol for legacy saves).
private struct TimelineStop: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let item: SaveTimelineItem
    let coverURL: URL?
    let gameTitle: String
    let first: Bool
    let last: Bool

    var body: some View {
        let width: CGFloat = 164
        VStack(alignment: .leading, spacing: 8) {
            Text(item.date.map { PlayFormat.short($0) } ?? "—")
                .themeText(.numeral, size: 12.5)
                .foregroundStyle(theme.inkSecondary)
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(light.mark1.opacity(0.6))
                    .frame(width: last ? 8 : width + 14, height: 1.5)
                    .offset(x: 6)
                Circle()
                    .fill(first ? light.mark1 : light.ground)
                    .overlay { Circle().strokeBorder(light.mark1, lineWidth: 1.5) }
                    .frame(width: 11, height: 11)
                    .shadow(color: first ? light.mark1.opacity(0.7) : .clear, radius: 5)
            }
            .frame(height: 12)
            VStack(alignment: .leading, spacing: 6) {
                TimelineFrame(item: item, coverURL: coverURL, gameTitle: gameTitle)
                    .frame(width: width, height: width * 0.62)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(theme.glassRim.opacity(theme.isDark ? 0.1 : 0.5), lineWidth: 0.5) }
                Text(item.kindTitle).themeText(.section, size: 10.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                Text(item.title).themeText(.bodyEmphasis, size: 15).foregroundStyle(theme.ink).lineLimit(2)
                Text([item.date.map { PlayFormat.relative($0) }, item.device].compactMap { $0 }.joined(separator: " · "))
                    .themeText(.caption, size: 12)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(1)
                if let sync = item.sync { SyncBadge(state: sync) }
            }
            .frame(width: width, alignment: .leading)
        }
        .padding(.trailing, 14)
        .accessibilityElement(children: .combine)
    }
}

private struct TimelineFrame: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let item: SaveTimelineItem
    let coverURL: URL?
    let gameTitle: String

    var body: some View {
        ZStack {
            if let entry = item.entry {
                SavePreviewImage(url: entry.thumbnailURL, coverURL: coverURL, title: gameTitle)
            } else {
                LinearGradient(colors: [light.accent1, light.accent2], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .opacity(theme.isDark ? 0.5 : 0.8)
                Image(systemName: item.symbol)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(theme.ink.opacity(0.7))
            }
        }
    }
}

/// The cover across the top of the Game page, fading into the page's light.
struct GameArtHero: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    let title: String

    /// The rows of the cover under the status bar: the cover fills the hero (cropped about
    /// its centre), and the bar covers the hero's first `topInset` points.
    static func statusBarBand(image: CGImage, hero: CGSize, topInset: CGFloat) -> ClosedRange<CGFloat> {
        let aspect = CGFloat(image.width) / CGFloat(max(1, image.height))
        let drawnHeight = max(hero.height, hero.width / max(aspect, 0.01))
        let cropped = (drawnHeight - hero.height) / 2
        return (cropped / drawnHeight)...((cropped + topInset) / drawnHeight)
    }

    var body: some View {
        TreatedCover(url: url, maxPointSize: 800) { CoverPlaceholder(title: title) }
            .frame(maxWidth: .infinity)
            // Scale with the available height: a fixed hero filled most of
            // iPhone Duo's open landscape display.
            .containerRelativeFrame(.vertical) { height, _ in min(400, max(220, height * (theme.isRiso ? 0.32 : 0.46))) }
            .clipped()
            // Aurora: the art dissolves into the page's light field (a fade to
            // the ground colour would leave a band against the lit field).
            .mask { HeroMask(glass: theme.isGlass) }
            .overlay(alignment: .bottom) {
                if theme.isRiso { Rectangle().fill(theme.ink).frame(height: 1.5) }
            }
            .accessibilityElement()
            .accessibilityLabel("Cover art for \(title)")
    }
}

private struct HeroMask: View {
    let glass: Bool

    var body: some View {
        if glass {
            LinearGradient(stops: [
                .init(color: .black, location: 0),
                .init(color: .black, location: 0.35),
                .init(color: .black.opacity(0.45), location: 0.7),
                .init(color: .clear, location: 1),
            ], startPoint: .top, endPoint: .bottom)
        } else {
            Rectangle()
        }
    }
}
