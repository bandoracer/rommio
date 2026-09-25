import SwiftUI
import RommioFoundation

/// Colour roles for status: sync state, support tier, download state.
enum StatusTone: Sendable {
    case ok, wait, live, bad, neutral, tint

    /// The tone as a fill or mark; `.tint` is the page light's tint.
    func color(_ theme: RommioTheme, light: PageLight) -> Color {
        switch self {
        case .ok: theme.ok
        case .wait: theme.wait
        case .live: theme.live
        case .bad: theme.bad
        case .neutral: theme.inkSecondary
        case .tint: light.tint
        }
    }

    /// The tone as text or a meaningful glyph (docs/theme-layouts.md §1.2a):
    /// the readable tint, and `badText` for errors.
    func textColor(_ theme: RommioTheme, light: PageLight) -> Color {
        switch self {
        case .tint: light.readableTint
        case .bad: theme.badText
        default: color(theme, light: light)
        }
    }
}

// MARK: Headers

/// A section header above a group. Aurora: a quiet caps label with its count.
/// Riso: a display-caps heading, a 1.5 pt ink rule to the edge, a mono count.
struct SectionHeader: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var detail: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        if theme.isRiso {
            HStack(alignment: .center, spacing: 10) {
                Text(title)
                    .themeText(.section)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .fixedSize()
                    .accessibilityAddTraits(.isHeader)
                Rectangle().fill(theme.ink).frame(height: theme.material.strokeWidth)
                SectionHeaderTrailing(detail: detail, actionTitle: actionTitle, action: action)
            }
            .padding(.bottom, 8)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .themeText(.section)
                    .foregroundStyle(theme.inkSecondary)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                SectionHeaderTrailing(detail: detail, actionTitle: actionTitle, action: action)
            }
        }
    }
}

/// A header's count and its action.
private struct SectionHeaderTrailing: View {
    @Environment(\.rommioTheme) private var theme
    let detail: String?
    let actionTitle: String?
    let action: (() -> Void)?

    var body: some View {
        if let detail {
            Text(detail)
                .themeText(theme.isRiso ? .label : .numeral, size: theme.isRiso ? 10 : 12.5)
                .foregroundStyle(theme.isRiso ? theme.inkSecondary : theme.inkTertiary)
                .lineLimit(1)
                .fixedSize()
        }
        if let actionTitle, let action {
            SectionHeaderAction(title: actionTitle, action: action)
        }
    }
}

/// A header's action, in the readable tint of the page's light (Riso: underlined ink).
private struct SectionHeaderAction: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let title: String
    let action: () -> Void

    var body: some View {
        Button(title, action: action)
            .themeText(.label)
            .foregroundStyle(theme.isRiso ? theme.ink : light.readableTint)
            .underline(theme.isRiso)
            .buttonStyle(.plain)
            .frame(minHeight: 32)
            .contentShape(Rectangle())
            .fixedSize()
    }
}

// MARK: Status

/// A small status label: a tinted capsule with a dot (Aurora) or a rubber stamp
/// with a 1.5 pt ink outline and a square ink chip (Riso).
struct StatusChip: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var tone: StatusTone = .neutral
    var showsDot = true
    var systemImage: String?

    var body: some View {
        // Status labels are short and never truncate.
        if theme.isRiso {
            StatusStamp(title: title, tone: tone, showsDot: showsDot, systemImage: systemImage).fixedSize()
        } else if tone == .tint {
            // Only the tint tone is drawn in the page's light.
            LitStatusCapsule(title: title, showsDot: showsDot, systemImage: systemImage).fixedSize()
        } else {
            StatusCapsule(title: title, color: tone.color(theme, light: theme.unlitLight),
                          textColor: tone.textColor(theme, light: theme.unlitLight), showsDot: showsDot, systemImage: systemImage)
                .fixedSize()
        }
    }
}

/// Aurora's status capsule in the page's tint.
private struct LitStatusCapsule: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let title: String
    let showsDot: Bool
    let systemImage: String?

    var body: some View {
        StatusCapsule(title: title, color: StatusTone.tint.color(theme, light: light), textColor: StatusTone.tint.textColor(theme, light: light),
                      showsDot: showsDot, systemImage: systemImage)
    }
}

/// Aurora: a capsule washed with its tone, a dot (or glyph) and the label.
private struct StatusCapsule: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let color: Color
    let textColor: Color
    let showsDot: Bool
    let systemImage: String?

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: 11, weight: .bold))
            } else if showsDot {
                Circle().fill(color).frame(width: 6, height: 6)
            }
            Text(title)
                .themeText(.label)
                .lineLimit(1)
        }
        .foregroundStyle(textColor)
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .frame(minHeight: 24)
        .background(Capsule().fill(color.opacity(theme.isDark ? 0.16 : 0.13)))
        .accessibilityElement(children: .combine)
    }
}

/// Riso: a rubber stamp with an ink outline and a square chip in the tone's plate.
private struct StatusStamp: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let tone: StatusTone
    let showsDot: Bool
    let systemImage: String?

    var body: some View {
        let isBad = tone == .bad
        HStack(spacing: 6) {
            if tone == .live {
                Image(systemName: systemImage ?? "arrow.triangle.2.circlepath")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(theme.accent2)
            } else if isBad {
                Image(systemName: systemImage ?? "exclamationmark.triangle")
                    .font(.system(size: 10, weight: .bold))
            } else if let systemImage {
                Image(systemName: systemImage).font(.system(size: 10, weight: .bold))
            } else if showsDot {
                Rectangle()
                    .fill(chip)
                    .frame(width: 9, height: 9)
                    .overlay { Rectangle().strokeBorder(isBad ? .clear : theme.ink, lineWidth: 1.5) }
            }
            Text(title)
                .themeText(.label, size: 10)
                .lineLimit(1)
        }
        .foregroundStyle(isBad ? theme.onAccent : theme.ink)
        .padding(.horizontal, 7)
        .frame(minHeight: 22)
        .background(isBad ? theme.bad : Color.clear)
        .overlay { if !isBad { Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth) } }
        .accessibilityElement(children: .combine)
    }

    private var chip: Color {
        switch tone {
        case .ok: theme.accent4
        case .wait: theme.accent3
        case .live, .tint: theme.accent2
        case .bad: theme.accent1
        case .neutral: .clear
        }
    }
}

/// Remote progress state, named as in save-sync-spec §11.
enum SyncBadgeState: Sendable {
    case synced, waiting, syncing, conflict, localOnly, signInNeeded, failed

    var title: String {
        switch self {
        case .synced: "Synced"
        case .waiting: "Waiting to sync"
        case .syncing: "Syncing"
        case .conflict: "Conflict"
        case .localOnly: "Local only"
        case .signInNeeded: "Sign-in needed"
        case .failed: "State sync failed"
        }
    }

    var tone: StatusTone {
        switch self {
        case .synced: .ok
        case .waiting, .signInNeeded: .wait
        case .syncing: .live
        case .conflict, .failed: .bad
        case .localOnly: .neutral
        }
    }
}

struct SyncBadge: View {
    let state: SyncBadgeState
    var detail: String?

    var body: some View {
        StatusChip(title: detail.map { "\(state.title) · \($0)" } ?? state.title, tone: state.tone,
                   systemImage: state == .syncing ? "arrow.triangle.2.circlepath" : nil)
    }
}

/// Touch, Controller or Unsupported: a glass capsule with its symbol (Aurora);
/// a solid ink, outlined or dashed tier stamp (Riso).
struct SupportTierBadge: View {
    @Environment(\.rommioTheme) private var theme
    let tier: EmbeddedSupportTier

    var body: some View {
        if theme.isRiso {
            label
                .foregroundStyle(tier == .touchSupported ? theme.ground : tier == .unsupported ? theme.inkSecondary : theme.ink)
                .padding(.horizontal, 7)
                .frame(minHeight: 22)
                .background(tier == .touchSupported ? theme.ink : .clear)
                .overlay { TierOutline(tier: tier) }
                .accessibilityElement(children: .combine)
        } else {
            label
                .foregroundStyle(tier == .unsupported ? theme.inkSecondary : theme.ink)
                .padding(.horizontal, 10)
                .frame(minHeight: 26)
                .background { GlassFill(shape: Capsule()) }
                .overlay { GlassEdge(shape: Capsule()) }
                .accessibilityElement(children: .combine)
        }
    }

    private var label: some View {
        HStack(spacing: 5) {
            Image(systemName: symbol).font(.system(size: theme.isRiso ? 10 : 12, weight: .semibold))
            Text(tier.title).themeText(.label, size: theme.isRiso ? 10 : 12.5).lineLimit(1)
        }
    }

    private var symbol: String {
        switch tier {
        case .touchSupported: "hand.tap"
        case .controllerSupported: "gamecontroller"
        case .unsupported: "eye"
        }
    }
}

/// Riso's tier outline: none (solid ink), keyline or dashed.
private struct TierOutline: View {
    @Environment(\.rommioTheme) private var theme
    let tier: EmbeddedSupportTier

    var body: some View {
        switch tier {
        case .touchSupported: EmptyView()
        case .controllerSupported: Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth)
        case .unsupported: Rectangle().strokeBorder(theme.ink, style: StrokeStyle(lineWidth: 1.5, dash: [3, 2]))
        }
    }
}

// MARK: Buttons

/// The page's main action. Aurora: a solid capsule that glows in the page's
/// light. Riso: an ink block with the misregistered pink plate behind it.
struct PrimaryButtonStyle: ButtonStyle {
    var fullWidth = true
    var large = true

    func makeBody(configuration: Configuration) -> some View {
        PrimaryButtonBody(configuration: configuration, fullWidth: fullWidth, large: large)
    }
}

private struct PrimaryButtonBody: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    @Environment(\.isEnabled) private var isEnabled
    let configuration: ButtonStyleConfiguration
    let fullWidth: Bool
    let large: Bool

    var body: some View {
        let height: CGFloat = large ? (theme.isRiso ? 52 : 54) : 44
        let label = configuration.label
            .themeText(.button, size: theme.isRiso ? (large ? 20 : 17) : (large ? 17 : 15))
            .foregroundStyle(theme.buttonInk)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, theme.isRiso ? 20 : 24)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
        Group {
            if theme.isRiso {
                // Disabled: the ink block fades and the plate is not printed.
                label
                    .background(theme.buttonFill)
                    .background {
                        if isEnabled {
                            theme.accent1.offset(x: theme.material.misregistration * 2, y: theme.material.misregistration * 2)
                        }
                    }
                    .compositingGroup()
            } else {
                label
                    .background(Capsule().fill(theme.buttonFill))
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.28), lineWidth: 0.5).mask(LinearGradient(colors: [.white, .clear], startPoint: .top, endPoint: .center)))
                    .shadow(color: light.mark1.opacity(isEnabled ? (theme.isDark ? 0.5 : 0.55) : 0), radius: 17, y: 14)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 2)
            }
        }
        .opacity(isEnabled ? (configuration.isPressed ? 0.84 : 1) : 0.4)
        .scaleEffect(configuration.isPressed ? 0.985 : 1)
        .animation(.snappy(duration: 0.15), value: configuration.isPressed)
        .contentShape(Rectangle())
    }
}

/// A quieter action. Aurora: a glass capsule. Riso: a 2 pt ink outline.
struct SecondaryButtonStyle: ButtonStyle {
    var fullWidth = false
    var destructive = false

    func makeBody(configuration: Configuration) -> some View {
        SecondaryButtonBody(configuration: configuration, fullWidth: fullWidth, destructive: destructive)
    }
}

private struct SecondaryButtonBody: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.isEnabled) private var isEnabled
    let configuration: ButtonStyleConfiguration
    let fullWidth: Bool
    let destructive: Bool

    var body: some View {
        let ink = destructive ? theme.badText : theme.ink
        let label = configuration.label
            .themeText(.button, size: theme.isRiso ? 17 : 15)
            .foregroundStyle(ink)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, theme.isRiso ? 14 : 18)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
        Group {
            if theme.isRiso {
                label
                    .background(configuration.isPressed ? theme.hairline : .clear)
                    .overlay { Rectangle().strokeBorder(destructive ? theme.badText : theme.ink, lineWidth: 2) }
            } else {
                label
                    .background { GlassFill(shape: Capsule(), strong: configuration.isPressed) }
                    .overlay { GlassEdge(shape: Capsule()) }
                    .shadow(color: .black.opacity(theme.isDark ? 0.3 : 0.06), radius: 10, y: 5)
            }
        }
        .opacity(isEnabled ? 1 : 0.4)
        .contentShape(Rectangle())
    }
}

/// A round icon button. Aurora: glass. Riso: an ink disc with the pink plate
/// (prominent) or an outlined square.
struct IconButtonStyle: ButtonStyle {
    var size: CGFloat = 44
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        IconButtonBody(configuration: configuration, size: size, prominent: prominent)
    }
}

private struct IconButtonBody: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let configuration: ButtonStyleConfiguration
    let size: CGFloat
    let prominent: Bool

    var body: some View {
        let label = configuration.label
            .font(.system(size: size * 0.38, weight: .semibold))
            .frame(width: size, height: size)
        Group {
            if theme.isRiso {
                if prominent {
                    label
                        .foregroundStyle(theme.buttonInk)
                        .background(Circle().fill(theme.buttonFill))
                        .background(Circle().fill(theme.accent1).offset(x: theme.material.misregistration * 1.25, y: theme.material.misregistration * 1.25))
                } else {
                    label
                        .foregroundStyle(theme.ink)
                        .background(configuration.isPressed ? theme.hairline : theme.ground.opacity(0.001))
                        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 2) }
                }
            } else {
                label
                    .foregroundStyle(prominent ? theme.buttonInk : theme.ink)
                    .background {
                        if prominent { Circle().fill(theme.buttonFill) } else { GlassFill(shape: Circle(), strong: configuration.isPressed) }
                    }
                    .overlay { if !prominent { GlassEdge(shape: Circle()) } }
                    .shadow(color: prominent ? light.mark1.opacity(0.45) : .black.opacity(theme.isDark ? 0.3 : 0.08), radius: 10, y: 5)
            }
        }
        .scaleEffect(configuration.isPressed ? 0.96 : 1)
        .contentShape(Circle())
    }
}

// MARK: Progress

/// Determinate progress. Aurora: a slim bar lit in the page's light. Riso: ink
/// over a halftone field, outlined.
struct ThemedProgressBar: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let value: Double
    var tone: StatusTone = .tint
    var height: CGFloat?

    var body: some View {
        let fraction = min(max(value, 0), 1)
        let barHeight = height ?? (theme.isRiso ? 14 : 5)
        let lit = tone == .tint || tone == .live
        GeometryReader { proxy in
            if theme.isRiso {
                ZStack(alignment: .leading) {
                    HalftoneField(dot: theme.ink.opacity(theme.isDark ? 0.45 : 0.85), pitch: 5)
                    Rectangle()
                        .fill(lit ? theme.accent2 : tone.color(theme, light: light))
                        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                        .frame(width: proxy.size.width * fraction)
                }
                .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
            } else {
                let colour = tone.color(theme, light: light)
                ZStack(alignment: .leading) {
                    Capsule().fill(theme.ink.opacity(0.12))
                    Capsule()
                        .fill(LinearGradient(colors: lit ? [light.mark2, light.mark1] : [colour, colour], startPoint: .leading, endPoint: .trailing))
                        .frame(width: max(barHeight, proxy.size.width * fraction))
                        .shadow(color: (lit ? light.mark1 : colour).opacity(0.7), radius: 6)
                        .opacity(fraction > 0 ? 1 : 0)
                }
            }
        }
        .frame(height: barHeight)
        .accessibilityElement()
        .accessibilityValue(Text("\(Int((fraction * 100).rounded())) percent"))
    }
}

/// A field of halftone dots (Riso progress tracks, empty chart days).
struct HalftoneField: View {
    let dot: Color
    var pitch: CGFloat = 5
    var coverage: CGFloat = CGFloat(RisoArtTokens.halftone)

    var body: some View {
        Canvas { context, size in
            let radius = pitch * coverage * 0.5 * 0.5
            var path = Path()
            var y = pitch / 2
            while y < size.height {
                var x = pitch / 2
                while x < size.width {
                    path.addEllipse(in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
                    x += pitch
                }
                y += pitch
            }
            context.fill(path, with: .color(dot))
        }
        .accessibilityHidden(true)
    }
}

// MARK: Figures

/// A figure with its label: summary numbers on Stats, Settings and Downloads.
/// Aurora: a glass card. Riso: a mono label over a display figure between rules.
struct StatCard: View, Equatable {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let value: String
    var detail: String?
    var systemImage: String?

    nonisolated static func == (lhs: StatCard, rhs: StatCard) -> Bool {
        lhs.title == rhs.title && lhs.value == rhs.value && lhs.detail == rhs.detail && lhs.systemImage == rhs.systemImage
    }

    var body: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 5) {
                Text(title).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                Text(value)
                    .themeText(.statValue, size: 27)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if let detail {
                    Text(detail).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .padding(.horizontal, 12)
            .accessibilityElement(children: .combine)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    if let systemImage { LitGlyph(systemImage: systemImage) }
                    Text(title).themeText(.section).foregroundStyle(theme.inkSecondary)
                }
                Text(value)
                    .themeText(.statValue, size: 34)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)
                if let detail {
                    Text(detail).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .themeSurface()
            .accessibilityElement(children: .combine)
        }
    }
}

/// A small glyph in the page's first mark (stat cards).
private struct LitGlyph: View {
    @Environment(\.pageLight) private var light
    let systemImage: String

    var body: some View {
        Image(systemName: systemImage).font(.caption.weight(.bold)).foregroundStyle(light.mark1)
    }
}

/// A row's symbol: a coloured tile with a white glyph (Aurora); a bare ink
/// glyph in a keyline square (Riso).
struct IconTile: View {
    @Environment(\.rommioTheme) private var theme
    let systemImage: String
    var tone: StatusTone = .tint

    var body: some View {
        if theme.isRiso {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(theme.ink)
                .frame(width: 32, height: 32)
                .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                .accessibilityHidden(true)
        } else if tone == .tint {
            LitIconTile(systemImage: systemImage)
        } else {
            AuroraIconTile(systemImage: systemImage, fill: tone == .neutral ? Color(white: 0.55) : tone.color(theme, light: theme.unlitLight))
        }
    }
}

private struct LitIconTile: View {
    @Environment(\.pageLight) private var light
    let systemImage: String

    var body: some View { AuroraIconTile(systemImage: systemImage, fill: light.tint) }
}

private struct AuroraIconTile: View {
    let systemImage: String
    let fill: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(fill))
            .accessibilityHidden(true)
    }
}

// MARK: Rows

/// A row: optional leading view, title, subtitle, trailing view.
struct ListRow<Leading: View, Trailing: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            leading()
            RowText(title: title, subtitle: subtitle)
            Spacer(minLength: 8)
            trailing()
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}

/// A row's title and subtitle.
struct RowText: View, Equatable {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let subtitle: String?

    nonisolated static func == (lhs: RowText, rhs: RowText) -> Bool { lhs.title == rhs.title && lhs.subtitle == rhs.subtitle }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .themeText(.bodyEmphasis, size: 16)
                .foregroundStyle(theme.ink)
                .lineLimit(2)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .themeText(.secondary, size: 13)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(2)
            }
        }
    }
}

extension ListRow where Leading == EmptyView, Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle, leading: { EmptyView() }, trailing: { EmptyView() })
    }
}

extension ListRow where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, @ViewBuilder leading: @escaping () -> Leading) {
        self.init(title: title, subtitle: subtitle, leading: leading, trailing: { EmptyView() })
    }
}

extension ListRow where Leading == EmptyView {
    init(title: String, subtitle: String? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.init(title: title, subtitle: subtitle, leading: { EmptyView() }, trailing: trailing)
    }
}

/// A label and its value on one row (storage, status).
struct ValueRow: View, Equatable {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let value: String

    nonisolated static func == (lhs: ValueRow, rhs: ValueRow) -> Bool { lhs.title == rhs.title && lhs.value == rhs.value }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
            if theme.isRiso { DotLeader() } else { Spacer(minLength: 8) }
            Text(value)
                .themeText(.numeral, size: theme.isRiso ? 12.5 : 15)
                .foregroundStyle(theme.isRiso ? theme.ink : theme.inkSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A toggle row with a title and an optional explanation.
struct ToggleRow: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                if let subtitle {
                    Text(subtitle).themeText(.secondary, size: 13).foregroundStyle(theme.inkSecondary)
                }
            }
        }
        .tint(light.readableTint)
    }
}

// MARK: Empty states and titles

/// An empty or error state in the page's voice.
struct EmptyStateView: View, Equatable {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let systemImage: String
    var message: String?

    nonisolated static func == (lhs: EmptyStateView, rhs: EmptyStateView) -> Bool {
        lhs.title == rhs.title && lhs.systemImage == rhs.systemImage && lhs.message == rhs.message
    }

    var body: some View {
        VStack(spacing: 10) {
            if theme.isRiso {
                Text(title)
                    .themeText(.headline)
                    .foregroundStyle(theme.ink)
                    .multilineTextAlignment(.center)
                Rectangle().fill(theme.ink).frame(width: 48, height: 3)
            } else {
                Image(systemName: systemImage)
                    .font(.system(size: 34, weight: .regular))
                    .foregroundStyle(theme.inkTertiary)
                    .padding(.bottom, 4)
                Text(title)
                    .themeText(.headline)
                    .foregroundStyle(theme.ink)
                    .multilineTextAlignment(.center)
            }
            if let message {
                Text(message)
                    .themeText(theme.isRiso ? .body : .secondary, size: theme.isRiso ? 15 : nil)
                    .italic(theme.isRiso)
                    .foregroundStyle(theme.inkSecondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .padding(.horizontal, 24)
        .accessibilityElement(children: .combine)
    }
}

/// A page title inside scrolling content, for pages that draw their own header.
struct PageTitle: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .themeText(.title)
                .foregroundStyle(theme.ink)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle)
                    .themeText(theme.isRiso ? .body : .secondary, size: theme.isRiso ? 15 : nil)
                    .italic(theme.isRiso)
                    .foregroundStyle(theme.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
