import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioPlayerKit
@testable import RommioUI

/// Work that used to run on the main thread on every refresh: hashing core binaries,
/// resolving a platform's profile per game, and walking the library folder.
final class BackgroundWorkTests: XCTestCase {
    override func setUp() {
        super.setUp()
        BundledCoreVerificationCache.reset()
        LibrarySizeCache.reset()
    }

    func testEachCoreIsVerifiedOncePerFileIdentity() throws {
        var runs = 0
        for _ in 0..<5 {
            try BundledCoreVerificationCache.verify(key: "snes9x|/core|1:2") { runs += 1 }
        }
        XCTAssertEqual(runs, 1)
        // A changed file (new size or modification time) is verified again.
        try BundledCoreVerificationCache.verify(key: "snes9x|/core|3:4") { runs += 1 }
        XCTAssertEqual(runs, 2)
    }

    func testAFailedVerificationIsRememberedWithItsMessage() {
        struct Mismatch: LocalizedError { var errorDescription: String? { "The core does not match its pinned build." } }
        var runs = 0
        for _ in 0..<3 {
            XCTAssertThrowsError(try BundledCoreVerificationCache.verify(key: "ppsspp|/core|5:6") { runs += 1; throw Mismatch() }) {
                XCTAssertEqual($0.localizedDescription, "The core does not match its pinned build.")
            }
        }
        XCTAssertEqual(runs, 1)
    }

    func testGroupingGamesResolvesEachPlatformOnce() {
        let roms = (1...90).map { RomDTO(id: $0, name: "Game \($0)", platformName: "P", platformSlug: ["snes", "gba", "psx"][$0 % 3]) }
        var calls: [String: Int] = [:]
        let sections = groupedRomSections(roms: roms, context: .platformDetail) { slug in
            calls[slug, default: 0] += 1
            var profile = PlatformControlProfileResolver().resolve(platformSlug: slug)
            profile.supportTier = slug == "psx" ? .controllerSupported : .touchSupported
            return profile
        }
        XCTAssertEqual(calls, ["snes": 1, "gba": 1, "psx": 1])
        XCTAssertEqual(sections.map(\.roms.count), [60, 30])
    }

    func testLibrarySizeIsReusedWhileInstalledFilesAreUnchanged() {
        var walks = 0
        let root = URL(fileURLWithPath: "/library")
        let measure: (URL) -> Int64 = { _ in walks += 1; return 42 }
        XCTAssertEqual(LibrarySizeCache.size(of: root, installedPaths: ["/a", "/b"], measure: measure), 42)
        XCTAssertEqual(LibrarySizeCache.size(of: root, installedPaths: ["/b", "/a"], measure: measure), 42)
        XCTAssertEqual(walks, 1)
        _ = LibrarySizeCache.size(of: root, installedPaths: ["/a"], measure: measure)
        XCTAssertEqual(walks, 2, "a game was removed, so the folder is measured again")
    }
}
