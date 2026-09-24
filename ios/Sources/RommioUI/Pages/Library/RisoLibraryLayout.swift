import SwiftUI
import RommioContract
import RommioFoundation

/// Riso Library: a contents page. Platforms are numbered entries grouped by how
/// they play, each with dot leaders to its game count and how many are on this
/// device; collections follow as ruled entries.
struct RisoLibraryLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    let actions: LibraryActions

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !content.isEmpty {
                    Text(content.summary)
                        .themeText(.body, size: 15)
                        .italic()
                        .foregroundStyle(theme.inkSecondary)
                        .padding(.bottom, 14)
                }
                if content.isEmpty {
                    AnyView(EmptyStateView(title: content.isLoading ? "Loading your library" : "No platforms yet", systemImage: "square.stack.3d.up.slash",
                                           message: content.isLoading ? nil : content.errorMessage ?? "Pull to refresh once your server is reachable."))
                } else {
                    AnyView(key)
                    ForEach(content.sections) { section in
                        AnyView(part(section))
                    }
                    AnyView(collections)
                    if let staleMessage = content.staleMessage {
                        VStack(alignment: .leading, spacing: 4) {
                            Kicker(label: "Showing your saved library", fill: .yellow)
                            Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                        }
                        .padding(.top, 20)
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

    /// The column key above the contents: what the two figures mean.
    private var key: some View {
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

    private func part(_ section: PlatformSupportSection) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .lastTextBaseline) {
                    Text(section.title)
                        .themeText(.headline, size: 28)
                        .foregroundStyle(theme.ink)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    SupportTierBadge(tier: section.supportTier)
                }
                Text(dek(section.supportTier))
                    .themeText(.caption, size: 13.5)
                    .foregroundStyle(theme.inkSecondary)
            }
            .padding(.top, 10)
            .padding(.bottom, 8)
            .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
            .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1) }
            ForEach(section.platforms) { platform in
                Button { actions.openPlatform(platform) } label: {
                    ChapterRow(number: number(of: platform), platform: platform, installed: content.installed(platform),
                               dimmed: section.supportTier == .unsupported)
                        .padding(.vertical, 2)
                        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("library.platform.\(platform.slug)")
            }
        }
        .padding(.bottom, 18)
    }

    private func dek(_ tier: EmbeddedSupportTier) -> String {
        switch tier {
        case .touchSupported: "Touch controls on screen; a controller works too."
        case .controllerSupported: "Playable here once a controller is connected."
        case .unsupported: "On your server, but no player on this device yet."
        }
    }

    private func number(of platform: PlatformDTO) -> Int {
        (content.platforms.firstIndex(of: platform) ?? 0) + 1
    }

    @ViewBuilder
    private var collections: some View {
        let all = content.collections
        if !all.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: "Collections", detail: "\(all.count)")
                    .padding(.top, 10)
                ForEach(all) { collection in
                    Button { actions.openCollection(collection) } label: {
                        HStack(alignment: .center, spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(collection.name).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                                Text(collection.description.isEmpty ? kind(collection) : collection.description)
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
            }
        }
    }

    private func kind(_ collection: RommCollectionDTO) -> String {
        collection.isSmart ? "Smart collection" : collection.isVirtual ? "Made by RomM" : "Made by you"
    }
}
