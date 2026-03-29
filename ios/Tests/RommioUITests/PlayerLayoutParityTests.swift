import CoreGraphics
import XCTest
@testable import RommioFoundation
@testable import RommioUI
@testable import RommioPlayerKit

@MainActor
final class PlayerLayoutParityTests: XCTestCase {
    func testPortraitTouchFamilyUsesTopViewportAndCenteredSystemRow() async throws {
        let controls = try await controlsState(platformSlug: "arcade")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            controlsState: controls
        )

        XCTAssertTrue(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .bottomBand)
        XCTAssertEqual(snapshot.menuPlacement, .portraitTopLeading)
        XCTAssertEqual(snapshot.systemPlacement, .portraitCenteredRow)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertFalse(snapshot.showsPointerOverlay)
        XCTAssertLessThan(snapshot.viewportFrame.minY, 140)
        XCTAssertLessThan(snapshot.viewportFrame.maxY, 640)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertLessThan(snapshot.menuCenter?.y ?? .greatestFiniteMagnitude, snapshot.viewportFrame.minY + 24)
        XCTAssertGreaterThan(snapshot.menuCenter?.x ?? 0, snapshot.viewportFrame.minX)
        XCTAssertLessThan(snapshot.menuCenter?.x ?? .greatestFiniteMagnitude, snapshot.viewportFrame.midX)
        XCTAssertNotNil(snapshot.systemFrame)
        XCTAssertEqual(snapshot.systemFrame?.midX ?? 0, snapshot.viewportFrame.midX, accuracy: 1)
    }

    func testLandscapeTouchFamilyUsesSideRailsAndLowerEdgeMenu() async throws {
        let controls = try await controlsState(platformSlug: "snes")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 932, height: 430),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 59, right: 59),
            controlsState: controls
        )

        XCTAssertFalse(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .sideRails)
        XCTAssertEqual(snapshot.menuPlacement, .landscapeLowerLeading)
        XCTAssertEqual(snapshot.systemPlacement, .landscapeTrailingColumn)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertEqual(snapshot.viewportFrame.midX, 466, accuracy: 1)
        XCTAssertEqual(snapshot.viewportFrame.midY, 215, accuracy: 1)
        XCTAssertNotNil(snapshot.leftRailInset)
        XCTAssertNotNil(snapshot.rightRailInset)
        XCTAssertLessThanOrEqual(snapshot.leftRailInset ?? .greatestFiniteMagnitude, 8)
        XCTAssertLessThanOrEqual(snapshot.rightRailInset ?? .greatestFiniteMagnitude, 8)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, 360)
        XCTAssertNotNil(snapshot.systemFrame)
        XCTAssertGreaterThan(snapshot.systemFrame?.maxY ?? 0, 360)
    }

    func testControllerFirstFamilyHidesGameplayControlsButKeepsMenuPath() async throws {
        let controls = try await controlsState(platformSlug: "psp")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 932, height: 430),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 59, right: 59),
            controlsState: controls
        )

        XCTAssertFalse(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .hiddenControllerFirst)
        XCTAssertEqual(snapshot.menuPlacement, .landscapeLowerLeading)
        XCTAssertEqual(snapshot.systemPlacement, .none)
        XCTAssertFalse(snapshot.showsGameplayTouchControls)
        XCTAssertFalse(snapshot.showsPointerOverlay)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, 360)
        XCTAssertNil(snapshot.leftRailInset)
        XCTAssertNil(snapshot.rightRailInset)
    }

    func testDSPortraitLayoutKeepsPointerOverlay() async throws {
        let controls = try await controlsState(platformSlug: "nds")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            controlsState: controls
        )

        XCTAssertEqual(controls.platformProfile.playerOrientationPolicy, .portraitOnly)
        XCTAssertTrue(snapshot.isPortrait)
        XCTAssertEqual(snapshot.menuPlacement, .portraitTopLeading)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertTrue(snapshot.showsPointerOverlay)
        XCTAssertLessThan(snapshot.menuCenter?.y ?? .greatestFiniteMagnitude, snapshot.viewportFrame.minY + 24)
    }

    private func controlsState(platformSlug: String) async throws -> PlayerControlsState {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let repository = PlayerControlsRepository(
            database: database,
            controllerMonitor: StaticControllerMonitor()
        )
        return try await repository.controls(platformSlug: platformSlug)
    }
}

private struct StaticControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}
