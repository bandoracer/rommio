import CoreText
import Foundation

/// Registers the bundled theme faces (SIL OFL; see THIRD_PARTY_NOTICES.md)
/// for this process. Safe to call repeatedly and from any thread.
enum RommioFonts {
    private static let registration: [String] = {
        var failures: [String] = []
        for name in ThemeFontFiles.all {
            guard let url = Bundle.module.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
                ?? Bundle.module.url(forResource: name, withExtension: "ttf") else {
                failures.append(name)
                continue
            }
            var error: Unmanaged<CFError>?
            if !CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) {
                let code = error.map { CFErrorGetCode($0.takeRetainedValue()) } ?? 0
                // Already registered (another process-scope registration) is fine.
                if code != CTFontManagerError.alreadyRegistered.rawValue { failures.append(name) }
            }
        }
        return failures
    }()

    /// Font files that could not be registered (empty when all faces are available).
    @discardableResult
    static func registerIfNeeded() -> [String] { registration }
}
