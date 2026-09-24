import SwiftUI

/// A display headline. Riso prints it twice: the ink, and the pink plate off
/// register behind it (multiply on paper, screen on black stock). Aurora sets it
/// plainly.
struct DisplayText: View {
    @Environment(\.rommioTheme) private var theme
    let text: String
    var role: TypeRole = .display
    var size: CGFloat?
    /// Plate offset in multiples of the misregistration (the studio uses 1.25–2).
    var plateOffset: CGFloat = 1.5
    var lineLimit: Int? = 3

    var body: some View {
        let label = Text(text)
            .themeText(role, size: size)
            .lineLimit(lineLimit)
            .minimumScaleFactor(0.55)
            .fixedSize(horizontal: false, vertical: true)
        if theme.isRiso {
            label
                .foregroundStyle(theme.ink)
                .background(alignment: .topLeading) {
                    label
                        .foregroundStyle(theme.accent1)
                        .offset(x: theme.material.misregistration * plateOffset, y: theme.material.misregistration * plateOffset)
                        .blendMode(theme.isDark ? .screen : .multiply)
                        .accessibilityHidden(true)
                }
                .accessibilityAddTraits(.isHeader)
        } else {
            label
                .foregroundStyle(theme.ink)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

/// A dateline label. Riso: an ink (or pink, yellow) block in mono caps, then a
/// muted mono detail. Aurora: an eyebrow (bold caps) and its detail.
struct Kicker: View {
    enum Fill { case ink, pink, yellow }
    @Environment(\.rommioTheme) private var theme
    let label: String
    var detail: String?
    var fill: Fill = .ink

    var body: some View {
        if theme.isRiso {
            HStack(spacing: 9) {
                Text(label)
                    .themeText(.label)
                    .foregroundStyle(fill == .ink ? theme.ground : theme.onAccent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 5)
                    .background(fill == .ink ? theme.ink : fill == .pink ? theme.accent1 : theme.accent3)
                    .fixedSize()
                if let detail {
                    Text(detail)
                        .themeText(.label)
                        .foregroundStyle(theme.inkSecondary)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(label).themeText(.section).foregroundStyle(theme.ink)
                if let detail {
                    Text(detail).themeText(.section).foregroundStyle(theme.inkSecondary).lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// Dot leaders between an entry and its value (Riso indexes).
struct DotLeader: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        Canvas { context, size in
            var path = Path()
            var x: CGFloat = 1
            let y = size.height - 2
            while x < size.width - 1 {
                path.addEllipse(in: CGRect(x: x - 0.9, y: y - 0.9, width: 1.8, height: 1.8))
                x += 6
            }
            context.fill(path, with: .color(theme.inkSecondary.opacity(0.8)))
        }
        .frame(minWidth: 14, maxWidth: .infinity)
        .frame(height: 8)
        .accessibilityHidden(true)
    }
}

/// A printed rule: a hairline, the 1.5 pt keyline, or the 3 pt heavy rule.
struct PrintRule: View {
    enum Weight { case hairline, keyline, heavy }
    @Environment(\.rommioTheme) private var theme
    var weight: Weight = .keyline

    var body: some View {
        Rectangle()
            .fill(weight == .hairline ? theme.hairline : theme.ink)
            .frame(height: weight == .hairline ? 1 : weight == .keyline ? theme.material.strokeWidth : 3)
            .accessibilityHidden(true)
    }
}

/// A dateline: a heavy rule above, a fine one below, mono caps between.
struct Dateline: View {
    @Environment(\.rommioTheme) private var theme
    let leading: String
    var trailing: String?

    var body: some View {
        HStack {
            Text(leading).themeText(.label, size: 10)
            Spacer(minLength: 10)
            if let trailing { Text(trailing).themeText(.label, size: 10).foregroundStyle(theme.inkSecondary) }
        }
        .foregroundStyle(theme.ink)
        .lineLimit(1)
        .padding(.vertical, 7)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1) }
    }
}

/// A boxed feature (Riso "where you left off", details): a 2 pt ink box with an
/// ink header bar. Aurora: a glass card with a caps header.
struct InkBox<Content: View>: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var detail: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(title).themeText(.section, size: 15).tracking(1.5)
                    Spacer(minLength: 8)
                    if let detail { Text(detail).themeText(.label, size: 10) }
                }
                .foregroundStyle(theme.ground)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(theme.ink)
                .accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: 10) { content() }
                    .padding(12)
            }
            .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 2) }
        } else {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(title).themeText(.section).foregroundStyle(theme.inkSecondary).accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    if let detail { Text(detail).themeText(.numeral, size: 12.5).foregroundStyle(theme.inkTertiary) }
                }
                content()
            }
            .padding(16)
            .themeSurface()
        }
    }
}

/// A mono term and its value in a details box.
struct SpecRow: View {
    @Environment(\.rommioTheme) private var theme
    let term: String
    let value: String

    var body: some View {
        if theme.isRiso {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(term).themeText(.label, size: 10).foregroundStyle(theme.ink).frame(width: 96, alignment: .leading)
                Text(value).themeText(.body, size: 14.5).foregroundStyle(theme.ink).frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)
        } else {
            HStack(alignment: .firstTextBaseline) {
                Text(term).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                Spacer(minLength: 12)
                Text(value).themeText(.bodyEmphasis).foregroundStyle(theme.ink).multilineTextAlignment(.trailing)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// Two columns sharing the width by a ratio (the studio's `1.5fr 1fr` grids),
/// top-aligned.
struct RatioColumns: Layout {
    /// The first column's share of the width, 0...1.
    var ratio: CGFloat = 0.6
    var spacing: CGFloat = 14

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 360
        let widths = columnWidths(width)
        var height: CGFloat = 0
        for (index, subview) in subviews.prefix(2).enumerated() {
            height = max(height, subview.sizeThatFits(ProposedViewSize(width: widths[index], height: nil)).height)
        }
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let widths = columnWidths(bounds.width)
        var x = bounds.minX
        for (index, subview) in subviews.prefix(2).enumerated() {
            subview.place(at: CGPoint(x: x, y: bounds.minY), proposal: ProposedViewSize(width: widths[index], height: nil))
            x += widths[index] + spacing
        }
    }

    private func columnWidths(_ width: CGFloat) -> [CGFloat] {
        let available = max(0, width - spacing)
        return [available * ratio, available * (1 - ratio)]
    }
}

/// An italic caption under a figure.
struct FigureCaption: View {
    @Environment(\.rommioTheme) private var theme
    let text: String

    var body: some View {
        Text(text)
            .themeText(.caption, size: 13)
            .foregroundStyle(theme.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}
