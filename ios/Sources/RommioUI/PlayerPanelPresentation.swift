import CoreGraphics

/// Where the player's panels (Pause, Save states, Controls) open. On a wide
/// window (iPhone Duo open, iPad) they are a floating inspector beside the game,
/// which the stage keeps uncovered (`PlayerStageContext.trailingPaneWidth`);
/// otherwise a sheet over the game (docs/theme-layouts.md, iPhone Duo).
enum PlayerPanelPresentation: Equatable, Sendable {
    case sheet
    case sidePane

    /// The narrowest window that takes a pane beside the game.
    static let minimumSidePaneWindowWidth: CGFloat = 700
    /// The pane's width: at most 380 pt, at most 45% of the window.
    static let maximumPaneWidth: CGFloat = 380
    static let maximumPaneFraction: CGFloat = 0.45

    static func resolve(regularWidth: Bool, windowWidth: CGFloat) -> PlayerPanelPresentation {
        regularWidth && windowWidth >= minimumSidePaneWindowWidth ? .sidePane : .sheet
    }

    /// The pane's width in a window (before iOS 27.1's ArrangementView sizes it around the fold).
    static func paneWidth(windowWidth: CGFloat) -> CGFloat {
        min(maximumPaneWidth, windowWidth * maximumPaneFraction)
    }
}
