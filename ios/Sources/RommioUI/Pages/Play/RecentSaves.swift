import SwiftUI
import RommioContract
import RommioFoundation

/// A save in Recent saves, with its game.
struct RecentSaveItem: Identifiable, Hashable {
    let entry: SaveEntry
    let rom: RomDTO
    let artworkURL: URL?
    var id: SaveKey { entry.key }

    /// "“Name” · 7:07 PM · This iPhone" (the name only when the player gave one).
    var detail: String {
        PlayFormat.line([entry.label.map { "“\($0)”" }, PlayFormat.clock(entry.capturedAt), entry.deviceLabel])
    }
    /// "Automatic resume", "Save state · Slot 2", "Save state".
    var kindTitle: String { entry.kind == .saveState ? entry.kindTitle : "Automatic resume" }
    var symbol: String { entry.kind == .saveState ? "square.stack.3d.up" : "clock.arrow.circlepath" }
    /// A current resume point of a conflicted reference: tapping it opens the Conflict sheet.
    var inConflict: Bool { entry.sync == .conflict && entry.isCurrentResume }
}

/// A conflict to resolve, with its game.
struct ConflictItem: Identifiable, Hashable {
    let conflict: ProgressConflict
    let rom: RomDTO
    let artworkURL: URL?
    /// The game is on this device (Keep playing needs it).
    var installed = true
    var id: String { conflict.id }
    var prompt: String { conflict.prompt(gameName: rom.displayName) }
    /// "Two resume points" (chip on Play).
    var chipTitle: String {
        let count = conflict.choices.count
        let number = count == 2 ? "Two" : count == 3 ? "Three" : "\(count)"
        switch conflict.kind {
        case .resume: return "\(number) resume points"
        case .gameSave: return "\(number) game saves"
        case .slot: return "\(number) save states"
        }
    }
}

/// Recent saves by local day, newest first (at most 12 shown).
func recentSaveDays(_ items: [RecentSaveItem], limit: Int = 12) -> [(day: Date, items: [RecentSaveItem])] {
    let shown = Array(items.prefix(limit))
    let byKey = Dictionary(shown.map { ($0.entry.key, $0) }, uniquingKeysWith: { a, _ in a })
    return SavedProgressRules.groupByDay(shown.map(\.entry)).map { day in (day.day, day.entries.compactMap { byKey[$0.key] }) }
}

// MARK: Aurora: a lit gallery by day

/// Aurora Recent saves (docs/theme-layouts.md, Play rules): each day a header with
/// its light strip, then a two-column mosaic of frames lit by their games; a day
/// with more than two saves opens with one wide tile. Frames are shown as
/// captured (never art-treated); a save without one shows its cover.
struct AuroraRecentSaves: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let items: [RecentSaveItem]
    let palettes: [URL: CoverPalette]
    let open: (RecentSaveItem) -> Void

    var body: some View {
        let days = recentSaveDays(items)
        if !days.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "Recent saves", detail: "\(min(items.count, 12))")
                LazyVGrid(columns: horizontalSizeClass == .regular ? [GridItem(.flexible(), spacing: 24, alignment: .top), GridItem(.flexible(), spacing: 24, alignment: .top)]
                                                                  : [GridItem(.flexible(), alignment: .top)],
                          alignment: .leading, spacing: 24) {
                    ForEach(days, id: \.day) { day in dayGroup(day.day, day.items) }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("play.recentSaves")
        }
    }

    private func dayGroup(_ day: Date, _ items: [RecentSaveItem]) -> some View {
        var seen = Set<Int>()
        let lights = items.filter { seen.insert($0.rom.id).inserted }.map { theme.mark(for: $0.artworkURL, in: palettes) }
        let gameCount = seen.count
        let wide = items.count > 2 ? items.first : nil
        let rest = wide == nil ? items : Array(items.dropFirst())
        return VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(PlayFormat.dayTitle(day)).themeText(.headline, size: 27).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                HStack {
                    if let caption = PlayFormat.dayCaption(day) {
                        Text(caption).themeText(.caption, size: 13).foregroundStyle(theme.inkSecondary)
                    }
                    Spacer()
                    // Wide canvas: "N saves · N games" (docs/theme-layouts.md, Play rules).
                    Text(horizontalSizeClass == .regular ? PlayFormat.line([PlayFormat.count(items.count, "save"), PlayFormat.games(gameCount)])
                                                         : PlayFormat.count(items.count, "save"))
                        .themeText(.numeral, size: 12.5).foregroundStyle(theme.inkSecondary)
                }
                LightStrip(colours: lights).padding(.top, 4)
            }
            if let wide {
                SaveFrameTile(item: wide, mark: theme.mark(for: wide.artworkURL, in: palettes), large: true) { open(wide) }
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10, alignment: .top), GridItem(.flexible(), spacing: 10, alignment: .top)],
                      alignment: .leading, spacing: 10) {
                ForEach(rest) { item in
                    SaveFrameTile(item: item, mark: theme.mark(for: item.artworkURL, in: palettes), large: false) { open(item) }
                }
            }
        }
    }
}

/// One save as a lit frame: the preview (aspect-fill, nearest-neighbour) on a dark
/// ground under a scrim, its kind top left, the game and when/where bottom left and
/// its sync state. The overlay tones are `DarkFrame`'s (Aurora's dark tokens)
/// because they sit on the frame, not on the page.
private struct SaveFrameTile: View {
    @Environment(\.rommioTheme) private var theme
    let item: RecentSaveItem
    let mark: Color
    let large: Bool
    let action: () -> Void

    private static var chipGlass: Color { DarkFrame.glass.opacity(0.5) }

    var body: some View {
        let radius: CGFloat = large ? 22 : 16
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        Button(action: action) {
            Color.clear
                .aspectRatio(large ? 16 / 9.4 : 4 / 3.3, contentMode: .fit)
                .overlay {
                    ZStack {
                        DarkFrame.ground
                        SavePreviewImage(url: item.entry.thumbnailURL, coverURL: item.artworkURL, title: item.rom.displayName, contentMode: .fill)
                        LinearGradient(stops: [
                            .init(color: DarkFrame.scrim.opacity(0.42), location: 0),
                            .init(color: .clear, location: large ? 0.26 : 0.34),
                            .init(color: DarkFrame.scrim.opacity(0.78), location: 0.6),
                            .init(color: DarkFrame.scrim.opacity(0.94), location: 1),
                        ], startPoint: .top, endPoint: .bottom)
                    }
                }
                .overlay(alignment: .topLeading) { kindChip.padding(large ? 12 : 8) }
                .overlay(alignment: .topTrailing) {
                    if large, let status = syncLine { status.padding(.horizontal, 9).frame(minHeight: 22).background(Capsule().fill(Self.chipGlass)).padding(12) }
                }
                .overlay(alignment: .bottomLeading) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.rom.displayName)
                            .themeText(.headline, size: large ? 28 : 18)
                            .foregroundStyle(DarkFrame.ink)
                            .lineLimit(2)
                            .minimumScaleFactor(0.8)
                        Text(item.detail)
                            .font(.system(size: large ? 13 : 11.5, weight: .medium))
                            .foregroundStyle(DarkFrame.ink.opacity(0.76))
                            .lineLimit(2)
                        if !large, let status = syncLine { status }
                    }
                    .padding(.horizontal, large ? 16 : 11)
                    .padding(.bottom, large ? 14 : 10)
                }
                .clipShape(shape)
                .overlay { shape.strokeBorder(DarkFrame.keyline, lineWidth: 0.5) }
                .shadow(color: mark.opacity(0.55), radius: 12, y: 16)
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(item.rom.displayName), \(SaveFormat.recentDetail(item.entry))")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("play.recentSave")
    }

    private var kindChip: some View {
        HStack(spacing: 5) {
            Image(systemName: item.symbol).font(.system(size: 12, weight: .semibold))
            Text(item.kindTitle).font(.system(size: 11, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(DarkFrame.ink.opacity(0.92))
        .padding(.horizontal, 8)
        .frame(height: 22)
        .background(Capsule().fill(Self.chipGlass))
    }

    /// The sync state in the frame's tones: a dot and its label (a warning glyph for a conflict).
    private var syncLine: AnyView? {
        let status = item.entry.sync
        guard status != .unknown else { return nil }
        let tone = DarkFrame.tone(status)
        return AnyView(HStack(spacing: 5) {
            if status == .conflict {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 10, weight: .bold))
            } else {
                Circle().frame(width: 6, height: 6)
            }
            Text(status.label).font(.system(size: 11, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(tone))
    }
}

// MARK: Riso: a list by day

/// Riso Recent saves: a list by day; each save a line of print with its frame in a
/// 1 pt ink keyline (the frame itself is never printed).
struct RisoRecentSaves: View {
    @Environment(\.rommioTheme) private var theme
    let items: [RecentSaveItem]
    var limit = 12
    let open: (RecentSaveItem) -> Void

    var body: some View {
        let days = recentSaveDays(items, limit: limit)
        if !days.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: "Recent saves", detail: "\(min(items.count, limit))")
                ForEach(days, id: \.day) { day in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(PlayFormat.dayTitle(day.day)).themeText(.headline, size: 22).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                        Text(PlayFormat.line([PlayFormat.dayCaption(day.day), PlayFormat.count(day.items.count, "save")]))
                            .themeText(.body, size: 14).italic().foregroundStyle(theme.inkSecondary).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.top, 14)
                    .padding(.bottom, 6)
                    .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1) }
                    ForEach(day.items) { item in row(item) }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("play.recentSaves")
        }
    }

    private func row(_ item: RecentSaveItem) -> some View {
        Button { open(item) } label: {
            HStack(alignment: .center, spacing: 12) {
                SavePreviewImage(url: item.entry.thumbnailURL, coverURL: item.artworkURL, title: item.rom.displayName)
                    .frame(width: 64, height: 48)
                    .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.kindTitle).themeText(.label, size: 10).foregroundStyle(theme.inkSecondary).lineLimit(1)
                    Text(item.rom.displayName).themeText(.headline, size: 18).foregroundStyle(theme.ink).lineLimit(2)
                    Text(item.detail).themeText(.body, size: 14).italic().foregroundStyle(theme.ink).lineLimit(2)
                }
                Spacer(minLength: 8)
                if let badge = item.entry.sync.badge { SyncBadge(state: badge) }
            }
            .padding(.vertical, 11)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.rom.displayName), \(SaveFormat.recentDetail(item.entry))")
        .accessibilityIdentifier("play.recentSave")
    }
}

/// "Two resume points" under a conflicted game (Play): opens the Conflict sheet.
struct ConflictChip: View {
    let item: ConflictItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            StatusChip(title: item.chipTitle, tone: .bad, systemImage: "exclamationmark.triangle.fill")
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.prompt)
        .accessibilityIdentifier("play.conflict.\(item.rom.id)")
    }
}
