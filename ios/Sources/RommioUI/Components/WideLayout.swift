import SwiftUI

/// Whether a page has room for its wide layout (revised 2026-10-03). A regular size class
/// isn't enough: the Library's detail column on an iPad in portrait is regular but about
/// 570 points wide, narrower than a phone in landscape, and a two- or three-column page there
/// squeezes its columns until words break mid-letter.
enum WideLayout {
    /// Aurora's two columns.
    static let twoColumns: CGFloat = 720
    /// Riso's three.
    static let threeColumns: CGFloat = 820

    /// A regular size class and at least `minimum` points (before the width is measured,
    /// the size class alone).
    static func fits(_ sizeClass: UserInterfaceSizeClass?, width: CGFloat?, minimum: CGFloat) -> Bool {
        sizeClass == .regular && (width ?? .infinity) >= minimum
    }
}

extension View {
    /// Keeps `width` up to date with the width this view is given.
    func measuringWidth(_ width: Binding<CGFloat?>) -> some View {
        onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width.wrappedValue = $0 }
    }
}
