import SwiftUI
import ImageIO
import RommioContract
import RommioFoundation

// Save previews, save rows and the rename dialog, shared by Play (Recent saves),
// Game (timeline, where you left off, Saved progress), the Conflict sheet and the
// player's Save states sheet (docs/play-data-local.md).

/// A save's preview as captured: the gameplay frame on the player ground,
/// letterboxed, never tinted or printed (both themes). Without one, the game's
/// cover (with the theme's usual cover treatment) stands in.
struct SavePreviewImage: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    var coverURL: URL?
    let title: String
    /// `.fit` letterboxes the frame; `.fill` crops it (only for small glyph-like marks).
    var contentMode: ContentMode = .fit
    @State private var image: CGImage?

    var body: some View {
        // Always exactly the size it is given: the frame letterboxes inside it, a cover fills it.
        Color.clear
            .overlay {
                if let url {
                    ZStack {
                        theme.player.ground.color
                        if let image {
                            Image(decorative: image, scale: 1)
                                .resizable()
                                // Pixel art stays crisp: frames are shown as captured.
                                .interpolation(.none)
                                .aspectRatio(contentMode: contentMode)
                                .transition(.opacity)
                        }
                    }
                } else {
                    TreatedCover(url: coverURL, maxPointSize: 320) { CoverPlaceholder(title: title) }
                        .aspectRatio(contentMode: .fill)
                }
            }
            .clipped()
        .task(id: url) {
            guard let url else { image = nil; return }
            let loaded = await SavePreviewCache.shared.image(url)
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.15)) { image = loaded } }
        }
        .accessibilityHidden(true)
    }
}

/// Decoded previews by file and modification time (previews are small; a few hundred KB in all).
actor SavePreviewCache {
    static let shared = SavePreviewCache()
    private var images: [String: CGImage] = [:]

    func image(_ url: URL) -> CGImage? {
        let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate?.timeIntervalSince1970 ?? 0
        let key = "\(url.path)#\(modified)"
        if let cached = images[key] { return cached }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { return nil }
        if images.count > 200 { images.removeAll() }
        images[key] = image
        return image
    }
}

/// A preview in a small frame for rows: 4:3, the theme's small corner (Aurora) or
/// a 1 pt ink keyline (Riso).
struct SavePreviewThumb: View {
    @Environment(\.rommioTheme) private var theme
    let entry: SaveEntry?
    var coverURL: URL?
    let title: String
    var width: CGFloat = 64

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: theme.isRiso ? 0 : min(theme.radius.cover, 8), style: .continuous)
        SavePreviewImage(url: entry?.thumbnailURL, coverURL: coverURL, title: title)
            .frame(width: width, height: width * 3 / 4)
            .clipShape(shape)
            .overlay { shape.strokeBorder(theme.isRiso ? theme.ink : theme.ink.opacity(theme.isDark ? 0.14 : 0.1), lineWidth: theme.isRiso ? 1 : 0.5) }
    }
}

extension SaveSyncStatus {
    /// The badge for a save's sync state; nil when there is nothing to say.
    var badge: SyncBadgeState? {
        switch self {
        case .synced: .synced
        case .syncing: .syncing
        case .waiting: .waiting
        case .localOnly: .localOnly
        case .signInNeeded: .signInNeeded
        case .conflict: .conflict
        case .unknown: nil
        }
    }
}

/// Plain-language lines for saves (copy shared with Android's `SaveViews`).
enum SaveFormat {
    /// "7:07 PM · This iPhone · Waiting to sync": when, where and whether it synced.
    static func whereWhen(_ entry: SaveEntry) -> String {
        PlayFormat.line([PlayFormat.clock(entry.capturedAt), entry.deviceLabel, entry.sync.label])
    }

    /// Recent saves: `“Name” · Save state · Slot 2 · 7:07 PM · This iPhone · Waiting to sync`.
    static func recentDetail(_ entry: SaveEntry) -> String {
        [entry.label.map { "“\($0)”" }, entry.kindTitle, whereWhen(entry)].compactMap { $0 }.joined(separator: " · ")
    }

    /// One game's rows: why it was captured (not for game-save data), day, time, device, sync.
    static func gameDetail(_ entry: SaveEntry) -> String {
        [entry.kind == .gameSave ? nil : entry.reasonTitle, day(entry.capturedAt), whereWhen(entry),
         !entry.loadable && entry.kind != .gameSave ? "Not on this device" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    /// "Today", "Yesterday", the weekday within six days, then the month-day (docs/theme-layouts.md §4).
    static func day(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        PlayFormat.dayTitle(date, now: now, calendar: calendar, locale: locale)
    }

    /// "1 resume point · 2 save states".
    static func count(_ saves: GameSaves) -> String {
        [saves.resume == nil ? nil : "1 resume point",
         saves.saveStates.isEmpty ? nil : PlayFormat.count(saves.saveStates.count, "save state")]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// A save in a list: preview, title, detail, and a rename button for save states.
/// `action` (load, open) covers the preview and text; the rename button sits beside it.
struct SaveRow: View {
    @Environment(\.rommioTheme) private var theme
    let entry: SaveEntry
    var coverURL: URL?
    let gameTitle: String
    var subtitle: String?
    var renameIdentifier: String?
    var action: (() -> Void)?
    var onRename: ((SaveEntry) -> Void)?

    var body: some View {
        HStack(spacing: 4) {
            if let action {
                Button(action: action) { content }.buttonStyle(.plain)
            } else {
                content
            }
            if entry.kind == .saveState, let onRename {
                RenameSaveButton(label: entry.label) { onRename(entry) }
                    .accessibilityIdentifier(renameIdentifier ?? "")
            }
        }
        .frame(minHeight: 56)
        .accessibilityElement(children: .contain)
    }

    private var content: some View {
        HStack(spacing: 12) {
            SavePreviewThumb(entry: entry, coverURL: coverURL, title: gameTitle, width: 64)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title)
                    .themeText(.bodyEmphasis, size: 16)
                    .foregroundStyle(theme.ink)
                    .lineLimit(2)
                Text(subtitle ?? SaveFormat.gameDetail(entry))
                    .themeText(.secondary, size: 13)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(3)
            }
            Spacer(minLength: 8)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Pencil button that names (or renames) a save state.
struct RenameSaveButton: View {
    @Environment(\.rommioTheme) private var theme
    let label: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "pencil")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(theme.isRiso ? theme.ink : theme.inkSecondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label == nil ? "Name this save state" : "Rename \(label!)")
    }
}

/// "Name this save state" / "Rename save state": a local name for one checkpoint.
/// Blank removes the name. `tagPrefix` keeps each surface's identifiers apart
/// (`player.states` in the player, `game.saves` on the Game page).
private struct RenameSaveAlert: ViewModifier {
    @Binding var entry: SaveEntry?
    let tagPrefix: String
    let onSave: (SaveEntry, String) -> Void
    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert(entry?.label == nil ? "Name this save state" : "Rename save state", isPresented: Binding(
                get: { entry != nil },
                set: { if !$0 { entry = nil } }
            )) {
                TextField("Name", text: $text, prompt: Text("Before the boss"))
                    .sentenceCapitalization()
                    .accessibilityIdentifier("\(tagPrefix).renameField")
                Button("Save") {
                    if let entry { onSave(entry, text) }
                    entry = nil
                }
                .accessibilityIdentifier("\(tagPrefix).renameSave")
                Button("Cancel", role: .cancel) { entry = nil }
            } message: {
                Text("Only on this device. Leave empty to remove the name.")
            }
            .onChange(of: entry?.key) { _, _ in
                text = String((entry?.label ?? "").prefix(SavedProgressRules.maxLabelLength))
            }
    }
}

extension View {
    func renameSaveAlert(_ entry: Binding<SaveEntry?>, tagPrefix: String, onSave: @escaping (SaveEntry, String) -> Void) -> some View {
        modifier(RenameSaveAlert(entry: entry, tagPrefix: tagPrefix, onSave: onSave))
    }
}

private extension View {
    @ViewBuilder
    func sentenceCapitalization() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.sentences)
        #else
        self
        #endif
    }
}
