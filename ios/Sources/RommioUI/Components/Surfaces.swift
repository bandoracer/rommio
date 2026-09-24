import SwiftUI

// MARK: Card surfaces

/// A card surface: glass lit by the page (Aurora; Liquid Glass on iOS 26, a
/// material before it) or a paper card with an ink keyline (Riso).
private struct ThemeSurfaceModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme
    let radius: CGFloat?
    let strong: Bool
    let keyline: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius ?? theme.radius.card, style: .continuous)
        if theme.isGlass {
            content
                .background { GlassFill(shape: shape, strong: strong) }
                .overlay { GlassEdge(shape: shape) }
                .shadow(color: .black.opacity(theme.isDark ? 0.34 : 0.07), radius: theme.isDark ? 20 : 18, y: theme.isDark ? 12 : 9)
        } else {
            content
                .background(shape.fill(strong ? theme.surfaceStrong : theme.surface))
                .overlay { shape.strokeBorder(keyline ? theme.keyline : theme.hairline, lineWidth: keyline ? theme.material.strokeWidth : 1) }
        }
    }
}

/// Aurora glass: frosted over the page light, milky by day and smoked by night.
struct GlassFill<S: Shape>: View {
    @Environment(\.rommioTheme) private var theme
    let shape: S
    var strong = false

    var body: some View {
        let fill = strong ? theme.surfaceStrong : theme.surface
        if #available(iOS 26, macOS 26, *) {
            shape.fill(fill.opacity(theme.isDark ? 0.9 : 0.72))
                .glassEffect(.regular.tint(fill.opacity(theme.isDark ? 0.18 : 0.35)), in: shape)
        } else {
            ZStack {
                shape.fill(.ultraThinMaterial)
                shape.fill(fill)
            }
        }
    }
}

/// Glass edges: a bright top rim and a faint keyline (studio `--glass-shadow`).
struct GlassEdge<S: InsettableShape>: View {
    @Environment(\.rommioTheme) private var theme
    let shape: S

    var body: some View {
        ZStack {
            shape.strokeBorder(theme.glassRim.opacity(theme.isDark ? 0.08 : 0.7), lineWidth: 0.5)
            shape.strokeBorder(
                LinearGradient(colors: [theme.glassRim.opacity(theme.isDark ? 0.16 : 0.95), .clear],
                               startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.12)),
                lineWidth: 1)
        }
        .allowsHitTesting(false)
    }
}

extension View {
    /// Draws a themed card surface behind the view. `keyline` gives Riso cards
    /// the strong ink outline (boxes of consequence); otherwise a hairline.
    func themeSurface(radius: CGFloat? = nil, strong: Bool = false, keyline: Bool = true) -> some View {
        modifier(ThemeSurfaceModifier(radius: radius, strong: strong, keyline: keyline))
    }

    /// Page background for scroll views, lists and forms. `artUnderBar`: the page
    /// draws art under the bars at rest (Game's hero), so Riso's hard paper edge
    /// waits until content scrolls under them.
    func themedPage(_ style: PageLightStyle = .quiet, artUnderBar: Bool = false) -> some View {
        modifier(ThemedPageModifier(style: style, artUnderBar: artUnderBar))
    }

    /// A row of a system list on a themed surface (lists that stay `List`).
    func themedRow() -> some View {
        modifier(ThemedRowModifier())
    }

    /// A system `List` in the theme: inset glass groups (Aurora) or plain paper
    /// rows between rules (Riso).
    func themedList(_ style: PageLightStyle = .quiet) -> some View {
        modifier(ThemedListModifier(style: style))
    }
}

private struct ThemedPageModifier: ViewModifier {
    let style: PageLightStyle
    var artUnderBar = false

    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background { PageBackground(style: style) }
            .themedTopEdge(artUnderBar: artUnderBar)
    }
}

/// The page's top edge under the status and navigation bars, so content never
/// shows through the status bar and the bar's buttons never sit over the page.
/// Riso prints on paper: a paper bar with a hard edge. Aurora: once content
/// scrolls under the bars they frost to the page's (lit) ground, over the
/// system's soft edge; at rest the page's light shows through.
private struct ThemedTopEdgeModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme
    var artUnderBar = false

    func body(content: Content) -> some View {
        if theme.isRiso && !artUnderBar {
            content
                .hardTopScrollEdge()
                #if os(iOS)
                .toolbarBackground(theme.ground, for: .navigationBar)
                #endif
        } else if theme.isRiso {
            // Art at rest under the bars: paper only once content scrolls under them.
            content
                #if os(iOS)
                .toolbarBackground(theme.ground, for: .navigationBar)
                #endif
        } else {
            content
                #if os(iOS)
                .toolbarBackground(theme.ground.opacity(0.94), for: .navigationBar)
                #endif
        }
    }
}

private extension View {
    @ViewBuilder
    func hardTopScrollEdge() -> some View {
        if #available(iOS 26, macOS 26, *) {
            scrollEdgeEffectStyle(.hard, for: .top)
        } else {
            self
        }
    }
}

extension View {
    /// The theme's treatment of the top scroll edge (see `ThemedTopEdgeModifier`).
    func themedTopEdge(artUnderBar: Bool = false) -> some View {
        modifier(ThemedTopEdgeModifier(artUnderBar: artUnderBar))
    }
}

private struct ThemedListModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme
    let style: PageLightStyle

    func body(content: Content) -> some View {
        Group {
            if theme.isRiso {
                content.listStyle(.plain)
            } else {
                #if os(iOS)
                content.listStyle(.insetGrouped)
                #else
                content
                #endif
            }
        }
        .scrollContentBackground(.hidden)
        .background { PageBackground(style: style) }
        .themedTopEdge()
        .environment(\.defaultMinListRowHeight, theme.spacing.rowHeight - 4)
    }
}

private struct ThemedRowModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme

    func body(content: Content) -> some View {
        if theme.isGlass {
            content
                .listRowBackground(Rectangle().fill(.ultraThinMaterial).overlay(theme.surface))
                .listRowSeparatorTint(theme.hairline)
        } else {
            content
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(theme.hairline)
                .listSectionSeparator(.hidden)
                .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
        }
    }
}

// MARK: Scroll pages and groups

/// A scrolling page in the theme's column: gutters, section spacing, a readable
/// maximum width and the page background.
struct ThemedScrollPage<Content: View>: View {
    @Environment(\.rommioTheme) private var theme
    var style: PageLightStyle = .quiet
    var maxWidth: CGFloat = 980
    var topPadding: CGFloat = 4
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.spacing.section) {
                content()
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.top, topPadding)
            .padding(.bottom, 40)
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
        }
        .themedPage(style)
    }
}

/// A group of rows under a section header: a glass card with hairlines between
/// rows (Aurora) or paper rows between rules (Riso). Rows are separated
/// automatically; `inset` aligns Aurora's hairlines past a leading icon.
struct ThemedGroup<Content: View>: View {
    @Environment(\.rommioTheme) private var theme
    var title: String?
    var detail: String?
    var footer: String?
    var inset: CGFloat = 16
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: theme.isGlass ? 8 : 0) {
            if let title {
                SectionHeader(title: title, detail: detail)
                    .padding(.horizontal, theme.isGlass ? 6 : 0)
            }
            Group(subviews: content()) { rows in
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        if index > 0 { separator }
                        row
                            .padding(.horizontal, theme.isGlass ? 16 : 0)
                            .padding(.vertical, theme.isGlass ? 9 : 11)
                            .frame(minHeight: theme.spacing.rowHeight, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(GroupSurface())
            }
            if let footer {
                Text(footer)
                    .themeText(.caption)
                    .foregroundStyle(theme.inkSecondary)
                    .padding(.horizontal, theme.isGlass ? 6 : 0)
                    .padding(.top, theme.isGlass ? 0 : 8)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var separator: some View {
        Rectangle()
            .fill(theme.hairline)
            .frame(height: theme.isGlass ? 0.5 : 1)
            .padding(.leading, theme.isGlass ? inset : 0)
    }
}

private struct GroupSurface: ViewModifier {
    @Environment(\.rommioTheme) private var theme

    func body(content: Content) -> some View {
        if theme.isGlass {
            content.themeSurface()
        } else {
            // Riso: rows sit on the page; a rule closes the group.
            content.overlay(alignment: .bottom) {
                Rectangle().fill(theme.hairline).frame(height: 1)
            }
        }
    }
}

/// Press feedback for whole-row buttons and links on themed groups.
struct RowButtonStyle: ButtonStyle {
    @Environment(\.rommioTheme) private var theme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .contentShape(Rectangle())
            .background {
                if configuration.isPressed {
                    Rectangle().fill(theme.isGlass ? theme.surfaceStrong : theme.hairline.opacity(0.6))
                        .padding(.horizontal, theme.isGlass ? -16 : -6)
                        .padding(.vertical, theme.isGlass ? -9 : -4)
                }
            }
    }
}

/// A navigation row: title, subtitle, optional leading and trailing views, and a chevron.
struct NavigationRow<Value: Hashable, Leading: View, Trailing: View>: View {
    @Environment(\.rommioTheme) private var theme
    let value: Value
    let title: String
    var subtitle: String?
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        NavigationLink(value: value) {
            ListRow(title: title, subtitle: subtitle, leading: leading) {
                HStack(spacing: 8) {
                    trailing()
                    Chevron()
                }
            }
        }
        .buttonStyle(RowButtonStyle())
    }
}

extension NavigationRow where Leading == EmptyView, Trailing == EmptyView {
    init(value: Value, title: String, subtitle: String? = nil) {
        self.init(value: value, title: title, subtitle: subtitle, leading: { EmptyView() }, trailing: { EmptyView() })
    }
}

extension NavigationRow where Trailing == EmptyView {
    init(value: Value, title: String, subtitle: String? = nil, @ViewBuilder leading: @escaping () -> Leading) {
        self.init(value: value, title: title, subtitle: subtitle, leading: leading, trailing: { EmptyView() })
    }
}

/// The disclosure chevron in the theme's tertiary ink.
struct Chevron: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        Image(systemName: theme.isRiso ? "arrow.right" : "chevron.right")
            .font(.system(size: theme.isRiso ? 13 : 13, weight: theme.isRiso ? .bold : .semibold))
            .foregroundStyle(theme.isRiso ? theme.ink : theme.inkTertiary)
            .accessibilityHidden(true)
    }
}
