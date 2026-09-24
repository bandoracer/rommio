import SwiftUI
import RommioFoundation

/// Colours for things drawn on a dark game frame or over the running game
/// (Recent saves tiles, the player's toast). The frame is dark in both schemes,
/// so these are Aurora's dark tokens whatever the appearance
/// (docs/theme-layouts.md, Play rules: frame tones).
enum DarkFrame {
    private static var tokens: ThemeColorTokens { ThemeTokens.aurora.dark }

    /// The frame's ground behind a letterboxed preview (#0b0a12).
    static var ground: Color { tokens.onAccent.color }
    /// The scrim over a frame (the dark ground).
    static var scrim: Color { tokens.ground.color }
    /// Dark glass pills over a frame or the game (chips, toasts).
    static var glass: Color { tokens.buttonInk.color }
    /// Text and glyphs on the frame.
    static var ink: Color { tokens.ink.color }
    /// The frame's keyline.
    static var keyline: Color { tokens.ink.color.opacity(0.1) }

    /// A sync state's tone on the frame: Aurora's dark ok/wait/live/bad.
    static func tone(_ status: SaveSyncStatus) -> Color {
        switch status {
        case .synced: tokens.ok.color
        case .waiting, .signInNeeded: tokens.wait.color
        case .syncing: tokens.live.color
        case .conflict: tokens.bad.color
        case .localOnly, .unknown: tokens.ink.color.opacity(0.7)
        }
    }

    /// An error on the frame or over the game.
    static var bad: Color { tokens.bad.color }
}
