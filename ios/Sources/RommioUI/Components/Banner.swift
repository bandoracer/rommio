import SwiftUI

/// A transient notice or error at the bottom of the screen.
struct Banner: View {
    @Environment(\.rommioTheme) private var theme
    let message: String
    let isError: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(isError ? theme.badText : theme.ok)
                .accessibilityHidden(true)
            Text(message)
                .themeText(.bodyEmphasis, size: 14)
                .foregroundStyle(theme.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .themeSurface(radius: theme.radius.button, strong: true)
        .shadow(color: .black.opacity(0.12), radius: 14, y: 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(isError ? "banner.error" : "banner.notice")
    }
}
