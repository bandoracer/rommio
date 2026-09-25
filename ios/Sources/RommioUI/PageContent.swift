import Foundation
import Observation

/// Page content derived from feature models: built off the render path (never in a
/// view's `body`), rebuilt once per change of what it reads, and published only when
/// it differs. Pages read `value` and nothing else, so a refresh that leaves a page's
/// content unchanged doesn't touch the page at all.
///
/// The build closure reads observable feature models; every property it reads is
/// tracked. Changes made in one run of the main actor (a refresh assigning several
/// properties) coalesce into one rebuild on the next turn.
@MainActor
final class DerivedContent<Value: Equatable>: Observable {
    private let registrar = ObservationRegistrar()
    private let build: @MainActor () -> Value
    private var stored: Value?

    init(_ build: @escaping @MainActor () -> Value) {
        self.build = build
    }

    /// The content (built on first use).
    var value: Value {
        registrar.access(self, keyPath: \.value)
        if let stored { return stored }
        let first = track()
        stored = first
        return first
    }

    /// Builds now instead of on first use (for a page about to appear).
    func prepare() { _ = value }

    private func track() -> Value {
        withObservationTracking { build() } onChange: { [weak self] in
            // Called as the first input changes (before it is set): rebuild on the next turn.
            Task { @MainActor [weak self] in self?.rebuild() }
        }
    }

    private func rebuild() {
        let new = track()
        guard new != stored else { return }
        registrar.withMutation(of: self, keyPath: \.value) { stored = new }
    }
}

/// What a page's actions promise so a section can take them as an input without
/// defeating diffing: actions capture only stable references (the app model,
/// navigation state) and read current data when called, so any two action sets of a
/// page are interchangeable.
protocol PageActions: Equatable {}

extension PageActions {
    nonisolated static func == (lhs: Self, rhs: Self) -> Bool { true }
}

extension Observable where Self: AnyObject {
    /// Assigns only when the value changes: `@Observable` notifies observers on every
    /// assignment, equal or not.
    @MainActor
    func update<T: Equatable>(_ keyPath: ReferenceWritableKeyPath<Self, T>, _ value: T) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }
}
