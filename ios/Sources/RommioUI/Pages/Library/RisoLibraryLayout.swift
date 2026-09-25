import SwiftUI
import RommioContract
import RommioFoundation

/// Riso Library: a contents page. Platforms are numbered entries grouped by how
/// they play, each with dot leaders to its game count and how many are on this
/// device; collections follow as ruled entries. Parts and entries are built as
/// they scroll into view.
struct RisoLibraryLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    let actions: LibraryActions

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if content.isEmpty {
                    EmptyStateView(title: content.isLoading ? "Loading your library" : "No platforms yet", systemImage: "square.stack.3d.up.slash",
                                   message: content.isLoading ? nil : content.errorMessage ?? "Pull to refresh once your server is reachable.")
                } else {
                    RisoLibrarySummary(text: content.summary)
                    RisoContentsKey()
                    ForEach(content.sections) { section in
                        RisoLibraryPartHeading(section: section)
                        ForEach(section.platforms) { platform in
                            RisoLibraryEntry(platform: platform, number: content.number(of: platform), installed: content.installed(platform),
                                             dimmed: section.supportTier == .unsupported, actions: actions)
                        }
                        Color.clear.frame(height: 18)
                    }
                    if !content.collections.isEmpty {
                        SectionHeader(title: "Collections", detail: "\(content.collections.count)")
                            .padding(.top, 10)
                        ForEach(content.collections) { item in
                            RisoCollectionEntry(collection: item.collection, actions: actions)
                        }
                    }
                    if let staleMessage = content.staleMessage {
                        RisoStaleNote(message: staleMessage).padding(.top, 20)
                    }
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }
}

private struct RisoLibrarySummary: View {
    @Environment(\.rommioTheme) private var theme
    let text: String

    var body: some View {
        Text(text)
            .themeText(.body, size: 15)
            .italic()
            .foregroundStyle(theme.inkSecondary)
            .padding(.bottom, 14)
    }
}

/// The column key above the contents: what the two figures mean.
private struct RisoContentsKey: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        HStack(spacing: 14) {
            Text("Platform")
            Spacer()
            Text("Games")
            Text("On this device").frame(width: 92, alignment: .trailing)
        }
        .themeText(.label, size: 9.5)
        .foregroundStyle(theme.inkSecondary)
        .padding(.bottom, 6)
        .accessibilityHidden(true)
    }
}

private struct RisoLibraryPartHeading: View {
    @Environment(\.rommioTheme) private var theme
    let section: PlatformSupportSection

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .lastTextBaseline) {
                Text(section.title)
                    .themeText(.headline, size: 28)
                    .foregroundStyle(theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                SupportTierBadge(tier: section.supportTier)
            }
            Text(dek)
                .themeText(.caption, size: 13.5)
                .foregroundStyle(theme.inkSecondary)
        }
        .padding(.top, 10)
        .padding(.bottom, 8)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1) }
    }

    private var dek: String {
        switch section.supportTier {
        case .touchSupported: "Touch controls on screen; a controller works too."
        case .controllerSupported: "Playable here once a controller is connected."
        case .unsupported: "On your server, but no player on this device yet."
        }
    }
}

private struct RisoLibraryEntry: View {
    @Environment(\.rommioTheme) private var theme
    let platform: PlatformDTO
    let number: Int
    let installed: Int
    let dimmed: Bool
    let actions: LibraryActions

    var body: some View {
        Button { actions.openPlatform(platform) } label: {
            ChapterRow(number: number, platform: platform, installed: installed, dimmed: dimmed)
                .padding(.vertical, 2)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("library.platform.\(platform.slug)")
    }
}

private struct RisoCollectionEntry: View {
    @Environment(\.rommioTheme) private var theme
    let collection: RommCollectionDTO
    let actions: LibraryActions

    var body: some View {
        Button { actions.openCollection(collection) } label: {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(collection.name).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                    Text(collection.description.isEmpty ? kind : collection.description)
                        .themeText(.caption, size: 12.5)
                        .foregroundStyle(theme.inkSecondary)
                        .lineLimit(1)
                }
                .layoutPriority(1)
                DotLeader()
                Text("\(collection.romCount)")
                    .themeText(.statValue, size: 26)
                    .foregroundStyle(theme.ink)
            }
            .frame(minHeight: 54)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityLabel("\(collection.name), \(PlayFormat.games(collection.romCount))")
        .accessibilityIdentifier("library.collection.\(collection.id)")
    }

    private var kind: String {
        collection.isSmart ? "Smart collection" : collection.isVirtual ? "Made by RomM" : "Made by you"
    }
}

/// "Showing your saved library" with the reason (Riso's yellow kicker).
struct RisoStaleNote: View {
    @Environment(\.rommioTheme) private var theme
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Kicker(label: "Showing your saved library", fill: .yellow)
            Text(message).themeText(.caption).foregroundStyle(theme.inkSecondary)
        }
    }
}
