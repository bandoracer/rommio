import SwiftUI

/// Dissolves from the previous value's view to the new one. The old view fades out as the
/// new one fades in, and the two are added (`plusLighter`) inside one group, so their
/// weights always sum to one: opaque content never shows what's behind it mid-way (the dip
/// of a plain `.transition(.opacity)`, where two stacked fading layers let the ground
/// through), and translucent content (art fading into the page) dissolves evenly instead
/// of leaving the old view showing through until it's removed. Used for Aurora's page
/// light and Play's hero art.
struct LayeredCrossfade<Value: Equatable, Content: View>: View {
    let value: Value
    var duration: Double = 0.55
    @ViewBuilder let content: (Value) -> Content
    @State private var shown: Value?
    @State private var under: Value?
    @State private var reveal = 1.0

    var body: some View {
        let top = shown ?? value
        ZStack {
            if let under {
                content(under)
                    .opacity(1 - reveal)
            }
            content(top)
                // Flattened first, so blends inside it (the field's screened lights) stay inside it.
                .compositingGroup()
                .opacity(under == nil ? 1 : reveal)
                .blendMode(under == nil ? .normal : .plusLighter)
        }
        // The two are summed with each other, then composited over the page as one.
        .compositingGroup()
        .onChange(of: value) { old, new in
            // A change mid-fade continues from what's on screen. The new view starts hidden
            // even when the change came inside an animation (a tap on a game).
            var instant = Transaction(animation: nil)
            instant.disablesAnimations = true
            withTransaction(instant) {
                under = reveal < 1 ? (under ?? old) : (shown ?? old)
                shown = new
                reveal = 0
            }
            withAnimation(.easeInOut(duration: duration)) {
                reveal = 1
            } completion: {
                if shown == new { under = nil }
            }
        }
    }
}
