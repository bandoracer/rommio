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

/// One day of Recent saves.
struct RecentSaveDay: Identifiable, Hashable {
    let day: Date
    let items: [RecentSaveItem]
    var id: Date { day }
    /// Games on this day, each once, in order of their newest save.
    var gameCount: Int { Set(items.map(\.rom.id)).count }

    /// Recent saves by local day, newest first (at most `limit` shown).
    static func days(_ items: [RecentSaveItem], limit: Int = 12) -> [RecentSaveDay] {
        let shown = Array(items.prefix(limit))
        let byKey = Dictionary(shown.map { ($0.entry.key, $0) }, uniquingKeysWith: { a, _ in a })
        return SavedProgressRules.groupByDay(shown.map(\.entry)).map { day in
            RecentSaveDay(day: day.day, items: day.entries.compactMap { byKey[$0.key] })
        }
    }
}

// MARK: Aurora: a lit gallery by day

/// Recent saves mosaic v2 (docs/theme-layouts.md): never a hole or a lone half tile.
enum SaveMosaic {
    /// Rows of tile indices for `count` saves. Two columns: one save is one full-width
    /// tile, two are a pair, three or more open with a full-width tile, then pairs, and
    /// an odd last save goes full width. More columns: rows of that many, a short last
    /// row stretching its tiles to fill it.
    static func rows(count: Int, columns: Int) -> [[Int]] {
        guard count > 0 else { return [] }
        if columns <= 2 {
            if count == 2 { return [[0, 1]] }
            var rows: [[Int]] = [[0]]
            var index = 1
            while index < count {
                if index + 1 < count {
                    rows.append([index, index + 1])
                    index += 2
                } else {
                    rows.append([index])
                    index += 1
                }
            }
            return rows
        }
        return stride(from: 0, to: count, by: columns).map { Array($0 ..< min($0 + columns, count)) }
    }

    /// Columns on a regular-width canvas: four while tiles keep 180 pt, else fewer.
    static func columns(width: CGFloat, gap: CGFloat = 10) -> Int {
        for columns in [4, 3] where (width - CGFloat(columns - 1) * gap) / CGFloat(columns) >= 180 { return columns }
        return 2
    }
}

/// Aurora Recent saves (docs/theme-layouts.md, Play rules): each day a header with
/// its light strip, then the mosaic of frames lit by their games. Frames are shown as
/// captured (never art-treated); a save without one shows its cover. Regular width:
/// each day is one row, its header in a 150 pt margin beside the mosaic.
struct AuroraRecentSaves: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let days: [RecentSaveDay]
    let count: Int
    let palettes: [URL: CoverPalette]
    let actions: PlayActions

    var body: some View {
        if !days.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "Recent saves", detail: "\(min(count, 12))")
                VStack(alignment: .leading, spacing: horizontalSizeClass == .regular ? 30 : 24) {
                    ForEach(days) { day in
                        AuroraSaveDay(day: day, palettes: day.items.reduce(into: [URL: CoverPalette]()) { found, item in
                            if let url = item.artworkURL, let palette = palettes[url] { found[url] = palette }
                        }, wide: horizontalSizeClass == .regular, actions: actions)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("play.recentSaves")
        }
    }
}

private struct AuroraSaveDay: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let day: RecentSaveDay
    /// Only this day's covers' palettes: another day's palette arriving leaves this day alone.
    let palettes: [URL: CoverPalette]
    let wide: Bool
    let actions: PlayActions

    var body: some View {
        var seen = Set<Int>()
        let lights = day.items.filter { seen.insert($0.rom.id).inserted }.map { light.mark(for: $0.artworkURL, in: palettes) }
        if wide {
            HStack(alignment: .top, spacing: 22) {
                HStack(alignment: .top, spacing: 16) {
                    Capsule().fill(LinearGradient(colors: lights.count > 1 ? lights : [lights.first ?? .clear, lights.first ?? .clear],
                                                  startPoint: .top, endPoint: .bottom))
                        .frame(width: 4)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(PlayFormat.dayTitle(day.day)).themeText(.headline, size: 26).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                        if let caption = PlayFormat.dayCaption(day.day) {
                            Text(caption).themeText(.caption, size: 13).foregroundStyle(theme.inkSecondary)
                        }
                        Text(PlayFormat.line([PlayFormat.count(day.items.count, "save"), PlayFormat.games(day.gameCount)]))
                            .themeText(.numeral, size: 13).foregroundStyle(theme.inkSecondary)
                    }
                }
                .frame(width: 150, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                SaveMosaicView(items: day.items, palettes: palettes, wide: true, actions: actions)
            }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(PlayFormat.dayTitle(day.day)).themeText(.headline, size: 27).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                    HStack {
                        if let caption = PlayFormat.dayCaption(day.day) {
                            Text(caption).themeText(.caption, size: 13).foregroundStyle(theme.inkSecondary)
                        }
                        Spacer()
                        Text(PlayFormat.count(day.items.count, "save")).themeText(.numeral, size: 12.5).foregroundStyle(theme.inkSecondary)
                    }
                    LightStrip(colours: lights).padding(.top, 4)
                }
                SaveMosaicView(items: day.items, palettes: palettes, wide: false, actions: actions)
            }
        }
    }
}

/// One day's tiles in `SaveMosaic` rows.
private struct SaveMosaicView: View {
    @Environment(\.pageLight) private var light
    let items: [RecentSaveItem]
    let palettes: [URL: CoverPalette]
    let wide: Bool
    let actions: PlayActions
    @State private var width: CGFloat = 0
    private let gap: CGFloat = 10

    var body: some View {
        let columns = wide ? SaveMosaic.columns(width: width, gap: gap) : 2
        let rows = SaveMosaic.rows(count: items.count, columns: columns)
        let columnWidth = max(1, (width - CGFloat(columns - 1) * gap) / CGFloat(columns))
        VStack(alignment: .leading, spacing: gap) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                // A full-width tile in two columns is the large 16:9 tile.
                let large = columns <= 2 && row.count == 1
                HStack(spacing: gap) {
                    ForEach(row, id: \.self) { index in
                        let item = items[index]
                        SaveFrameTile(item: item, mark: light.mark(for: item.artworkURL, in: palettes), large: large,
                                      height: large ? nil : columnWidth / (4 / 3.3)) { actions.openSave(item) }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}

/// One save as a lit frame: the preview (aspect-fill, nearest-neighbour) on a dark
/// ground under a scrim, its kind top left, the game and when/where bottom left and
/// its sync state. The overlay tones are `DarkFrame`'s (Aurora's dark tokens)
/// because they sit on the frame, not on the page.
private struct SaveFrameTile: View {
    let item: RecentSaveItem
    let mark: Color
    let large: Bool
    /// Tiles in a row share a height; nil keeps the tile's aspect (16:9 large, 4:3.3 small).
    var height: CGFloat? = nil
    let action: () -> Void

    static var chipGlass: Color { DarkFrame.glass.opacity(0.5) }

    var body: some View {
        let radius: CGFloat = large ? 22 : 16
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        Button(action: action) {
            TileFrame(height: height, aspect: large ? 16 / 9 : 4 / 3.3)
                .overlay { SaveFramePicture(item: item, large: large) }
                .overlay(alignment: .topLeading) { SaveKindChip(item: item).padding(large ? 12 : 8) }
                .overlay(alignment: .topTrailing) {
                    if large, item.entry.sync != .unknown {
                        SaveSyncLine(status: item.entry.sync)
                            .padding(.horizontal, 9).frame(minHeight: 22).background(Capsule().fill(Self.chipGlass)).padding(12)
                    }
                }
                .overlay(alignment: .bottomLeading) { SaveFrameCaption(item: item, large: large) }
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
}

private struct SaveFramePicture: View {
    let item: RecentSaveItem
    let large: Bool

    var body: some View {
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
}

private struct SaveFrameCaption: View {
    let item: RecentSaveItem
    let large: Bool

    var body: some View {
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
            if !large, item.entry.sync != .unknown { SaveSyncLine(status: item.entry.sync) }
        }
        .padding(.horizontal, large ? 16 : 11)
        .padding(.bottom, large ? 14 : 10)
    }
}

private struct SaveKindChip: View {
    let item: RecentSaveItem

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: item.symbol).font(.system(size: 12, weight: .semibold))
            Text(item.kindTitle).font(.system(size: 11, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(DarkFrame.ink.opacity(0.92))
        .padding(.horizontal, 8)
        .frame(height: 22)
        .background(Capsule().fill(SaveFrameTile.chipGlass))
    }
}

/// A tile's box: a shared row height, or its own aspect.
private struct TileFrame: View {
    let height: CGFloat?
    let aspect: CGFloat

    var body: some View {
        if let height {
            Color.clear.frame(maxWidth: .infinity).frame(height: height)
        } else {
            Color.clear.aspectRatio(aspect, contentMode: .fit)
        }
    }
}

/// The sync state in the frame's tones: a dot and its label (a warning glyph for a conflict).
private struct SaveSyncLine: View {
    let status: SaveSyncStatus

    var body: some View {
        HStack(spacing: 5) {
            if status == .conflict {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 10, weight: .bold))
            } else {
                Circle().frame(width: 6, height: 6)
            }
            Text(status.label).font(.system(size: 11, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(DarkFrame.tone(status))
    }
}

// MARK: Riso: a list by day

/// Riso Recent saves: a list by day; each save a line of print with its frame in a
/// 1 pt ink keyline (the frame itself is never printed).
struct RisoRecentSaves: View {
    let days: [RecentSaveDay]
    let count: Int
    var limit = 12
    let actions: PlayActions

    var body: some View {
        if !days.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: "Recent saves", detail: "\(min(count, limit))")
                ForEach(days) { day in
                    RisoSaveDayHeader(day: day)
                    ForEach(day.items) { item in RisoSaveRow(item: item, actions: actions) }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("play.recentSaves")
        }
    }
}

private struct RisoSaveDayHeader: View {
    @Environment(\.rommioTheme) private var theme
    let day: RecentSaveDay

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(PlayFormat.dayTitle(day.day)).themeText(.headline, size: 22).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
            Text(PlayFormat.line([PlayFormat.dayCaption(day.day), PlayFormat.count(day.items.count, "save")]))
                .themeText(.body, size: 14).italic().foregroundStyle(theme.inkSecondary).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.top, 14)
        .padding(.bottom, 6)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1) }
    }
}

private struct RisoSaveRow: View {
    @Environment(\.rommioTheme) private var theme
    let item: RecentSaveItem
    let actions: PlayActions

    var body: some View {
        Button { actions.openSave(item) } label: {
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
