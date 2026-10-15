import XCTest
#if canImport(GameController)
import GameController
#endif
@testable import RommioUI
@testable import RommioFoundation

@MainActor
final class PlayerGamepadInputTests: XCTestCase {
    #if canImport(GameController)
    /// What a controller reports, by position, read from a virtual controller.
    func testASnapshotReadsEveryControlByPosition() throws {
        let controller = GCController.withExtendedGamepad()
        let pad = try XCTUnwrap(controller.extendedGamepad)
        pad.buttonA.setValue(1)
        pad.rightShoulder.setValue(1)
        pad.leftTrigger.setValue(0.2)
        pad.dpad.setValueForXAxis(-1, yAxis: 0)
        pad.leftThumbstick.setValueForXAxis(0.5, yAxis: -0.75)

        let snapshot = PlayerGamepadInput.snapshot(pad)
        XCTAssertTrue(snapshot.pressed.isSuperset(of: [.buttonA, .rightShoulder, .dpadLeft]))
        XCTAssertFalse(snapshot.pressed.contains(.buttonB))
        XCTAssertFalse(snapshot.pressed.contains(.dpadRight))
        XCTAssertEqual(snapshot.leftStick.x, 0.5, accuracy: 0.01)
        XCTAssertEqual(snapshot.leftStick.y, -0.75, accuracy: 0.01)

        // Through the router: the bottom button and the shoulder press B and R on player 1.
        var router = ControllerRouter()
        router.connect("virtual")
        let events = router.update("virtual", snapshot)
        XCTAssertTrue(events.contains(.digital(port: 0, button: RetroJoypad.b, pressed: true)))
        XCTAssertTrue(events.contains(.digital(port: 0, button: RetroJoypad.r, pressed: true)))
        XCTAssertTrue(events.contains(.digital(port: 0, button: RetroJoypad.left, pressed: true)))
        XCTAssertTrue(events.contains(.motion(port: 0, stick: 0, x: 0.5, y: 0.75)))
    }
    #endif
}
