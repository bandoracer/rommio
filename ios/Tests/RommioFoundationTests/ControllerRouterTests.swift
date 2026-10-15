import XCTest
#if canImport(GameController)
import GameController
#endif
@testable import RommioFoundation

final class ControllerRouterTests: XCTestCase {
    private func press(_ elements: ControllerElement...) -> ControllerSnapshot { ControllerSnapshot(pressed: Set(elements)) }

    func testControllersPlayByPositionOnTheirOwnPort() {
        var router = ControllerRouter()
        XCTAssertEqual(router.connect("pad"), 0)
        XCTAssertEqual(router.update("pad", press(.buttonA)), [.digital(port: 0, button: RetroJoypad.b, pressed: true)])
        XCTAssertEqual(router.update("pad", press(.buttonA, .dpadLeft)), [.digital(port: 0, button: RetroJoypad.left, pressed: true)])
        XCTAssertEqual(router.update("pad", press()), [
            .digital(port: 0, button: RetroJoypad.b, pressed: false),
            .digital(port: 0, button: RetroJoypad.left, pressed: false),
        ])
    }

    func testTwoControllersAreTwoPlayersThatDontReleaseEachOther() {
        var router = ControllerRouter()
        XCTAssertEqual(router.connect("one"), 0)
        XCTAssertEqual(router.connect("two"), 1)
        _ = router.update("one", press(.buttonB))
        XCTAssertEqual(router.update("two", press(.buttonB)), [.digital(port: 1, button: RetroJoypad.a, pressed: true)])
        // Player 2 letting go releases only player 2.
        XCTAssertEqual(router.update("two", press()), [.digital(port: 1, button: RetroJoypad.a, pressed: false)])
    }

    func testDisconnectingReleasesWhatTheControllerHeld() {
        var router = ControllerRouter()
        router.connect("pad")
        _ = router.update("pad", ControllerSnapshot(pressed: [.buttonA, .rightShoulder], leftStick: SIMD2(0.9, 0)))
        let events = router.disconnect("pad")
        XCTAssertEqual(Set(events.compactMap { event -> Int? in
            if case let .digital(0, button, false) = event { return button }
            return nil
        }), [RetroJoypad.b, RetroJoypad.r])
        XCTAssertTrue(events.contains(.motion(port: 0, stick: 0, x: 0, y: 0)), "the stick centres")
        XCTAssertTrue(router.ports.isEmpty)
        // A later report from the gone controller does nothing.
        XCTAssertEqual(router.update("pad", press(.buttonA)), [])
    }

    func testAControllerTakesTheLowestFreePlayer() {
        var router = ControllerRouter(maxPlayers: 2)
        router.connect("a")
        router.connect("b")
        XCTAssertNil(router.connect("c"), "every player is taken")
        _ = router.disconnect("a")
        XCTAssertEqual(router.connect("c"), 0)
        XCTAssertEqual(router.connect("b"), 1, "reconnecting keeps a controller's player")
    }

    func testTheKeyboardSharesPlayerOneWithoutReleasingTheController() {
        var router = ControllerRouter()
        router.connect("pad")
        XCTAssertEqual(router.update("pad", press(.buttonA)), [.digital(port: 0, button: RetroJoypad.b, pressed: true)])
        XCTAssertEqual(router.keyboard([KeyboardKey.z]), [], "already held by the controller")
        XCTAssertEqual(router.keyboard([]), [], "the controller still holds it")
        XCTAssertEqual(router.update("pad", press()), [.digital(port: 0, button: RetroJoypad.b, pressed: false)])
        XCTAssertEqual(router.keyboard([KeyboardKey.upArrow, KeyboardKey.returnOrEnter]), [
            .digital(port: 0, button: RetroJoypad.start, pressed: true),
            .digital(port: 0, button: RetroJoypad.up, pressed: true),
        ])
    }

    func testSticksUseTheDeadzoneAndLibretrosDownwardY() {
        var router = ControllerRouter(deadzone: 0.2)
        router.connect("pad")
        XCTAssertEqual(router.update("pad", ControllerSnapshot(leftStick: SIMD2(0.1, 0.15))), [], "inside the deadzone")
        XCTAssertEqual(router.update("pad", ControllerSnapshot(leftStick: SIMD2(0.5, 0.8))), [.motion(port: 0, stick: 0, x: 0.5, y: -0.8)])
        XCTAssertEqual(router.update("pad", ControllerSnapshot(leftStick: SIMD2(0.5, 0.8))), [], "no change, no report")
        XCTAssertEqual(router.update("pad", ControllerSnapshot()), [.motion(port: 0, stick: 0, x: 0, y: 0)])
    }

    func testHotkeysOpenThePauseMenuWithoutReachingTheGame() {
        var router = ControllerRouter()
        router.connect("pad")
        XCTAssertEqual(router.update("pad", press(.buttonHome)), [.pauseMenu])
        XCTAssertEqual(router.update("pad", press()), [])
        // Both stick clicks: the first reaches the game until the chord forms, then lets go.
        XCTAssertEqual(router.update("pad", press(.leftThumbstickButton)), [.digital(port: 0, button: RetroJoypad.l3, pressed: true)])
        XCTAssertEqual(router.update("pad", press(.leftThumbstickButton, .rightThumbstickButton)), [
            .pauseMenu, .digital(port: 0, button: RetroJoypad.l3, pressed: false),
        ])
        XCTAssertEqual(router.update("pad", press()), [])
    }

    func testKeyboardShortcuts() {
        var router = ControllerRouter()
        XCTAssertEqual(router.keyboard([KeyboardKey.escape]), [.pauseMenu])
        XCTAssertEqual(router.keyboard([]), [])
        XCTAssertEqual(router.keyboard([KeyboardKey.leftCommand]), [])
        XCTAssertEqual(router.keyboard([KeyboardKey.leftCommand, KeyboardKey.s]), [.quickSave], "S is X, but not with ⌘")
        XCTAssertEqual(router.keyboard([KeyboardKey.rightCommand, KeyboardKey.l]), [.quickLoad])
        XCTAssertEqual(router.keyboard([]), [])
    }

    func testReleasingAllLetsGoOfEveryDeviceAndPort() {
        var router = ControllerRouter()
        router.connect("one")
        router.connect("two")
        _ = router.update("one", press(.buttonA))
        _ = router.update("two", ControllerSnapshot(pressed: [.buttonY], rightStick: SIMD2(0, -1)))
        _ = router.keyboard([KeyboardKey.q])
        let events = router.releaseAll()
        let releases = events.filter {
            if case .digital(_, _, false) = $0 { return true }
            return false
        }
        XCTAssertEqual(releases.count, 3, "player 1's B and L, player 2's X")
        XCTAssertTrue(events.contains(.motion(port: 1, stick: 1, x: 0, y: 0)))
        XCTAssertEqual(router.ports.count, 2, "still connected")
        XCTAssertEqual(router.update("one", press(.buttonA)), [.digital(port: 0, button: RetroJoypad.b, pressed: true)])
    }

    func testRemappedBindingsApply() {
        var bindings = ControllerBindings.standard
        bindings.assign(.buttonA, to: RetroJoypad.a)
        var router = ControllerRouter(bindings: bindings)
        router.connect("pad")
        XCTAssertEqual(router.update("pad", press(.buttonA)), [.digital(port: 0, button: RetroJoypad.a, pressed: true)])
        _ = router.update("pad", press())
        XCTAssertEqual(router.update("pad", press(.buttonB)), [.digital(port: 0, button: RetroJoypad.b, pressed: true)], "swapped")
    }

    func testAssigningSwapsSoEveryButtonStaysReachable() {
        var bindings = ControllerBindings.standard
        bindings.assign(.buttonA, to: RetroJoypad.a)
        XCTAssertEqual(bindings.buttons[.buttonA], RetroJoypad.a)
        XCTAssertEqual(bindings.buttons[.buttonB], RetroJoypad.b)
        XCTAssertEqual(bindings.elements(for: RetroJoypad.b), [.buttonB])

        bindings.assign(key: KeyboardKey.space, to: RetroJoypad.b)
        XCTAssertEqual(bindings.keys(for: RetroJoypad.b), [KeyboardKey.space])
        XCTAssertNil(bindings.keys[KeyboardKey.z], "Z pressed nothing new (space pressed nothing)")

        bindings.assign(.buttonHome, to: RetroJoypad.start)
        bindings.assign(key: KeyboardKey.leftShift, to: RetroJoypad.start)
        bindings.assign(key: KeyboardKey.escape, to: RetroJoypad.start)
        XCTAssertNil(bindings.buttons[.buttonHome], "Home always opens the pause menu")
        XCTAssertNil(bindings.keys[KeyboardKey.leftShift], "modifiers make shortcuts")
        XCTAssertNil(bindings.keys[KeyboardKey.escape])
        XCTAssertFalse(bindings.isStandard)
    }

    func testBindingsRoundTripThroughJSON() throws {
        var bindings = ControllerBindings.standard
        bindings.assign(.leftTrigger, to: RetroJoypad.select)
        bindings.assign(key: KeyboardKey.space, to: RetroJoypad.a)
        let data = try JSONEncoder().encode(bindings)
        XCTAssertEqual(try JSONDecoder().decode(ControllerBindings.self, from: data), bindings)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertTrue(text.contains("\"leftTrigger\""), "stored by name: \(text)")
    }

    #if canImport(GameController)
    func testKeyCodesAreGameControllersHIDUsages() {
        XCTAssertEqual(GCKeyCode.keyA.rawValue, KeyboardKey.a)
        XCTAssertEqual(GCKeyCode.keyZ.rawValue, KeyboardKey.z)
        XCTAssertEqual(GCKeyCode.keyX.rawValue, KeyboardKey.x)
        XCTAssertEqual(GCKeyCode.keyS.rawValue, KeyboardKey.s)
        XCTAssertEqual(GCKeyCode.keyL.rawValue, KeyboardKey.l)
        XCTAssertEqual(GCKeyCode.keyQ.rawValue, KeyboardKey.q)
        XCTAssertEqual(GCKeyCode.keyW.rawValue, KeyboardKey.w)
        XCTAssertEqual(GCKeyCode.returnOrEnter.rawValue, KeyboardKey.returnOrEnter)
        XCTAssertEqual(GCKeyCode.escape.rawValue, KeyboardKey.escape)
        XCTAssertEqual(GCKeyCode.spacebar.rawValue, KeyboardKey.space)
        XCTAssertEqual(GCKeyCode.upArrow.rawValue, KeyboardKey.upArrow)
        XCTAssertEqual(GCKeyCode.downArrow.rawValue, KeyboardKey.downArrow)
        XCTAssertEqual(GCKeyCode.leftArrow.rawValue, KeyboardKey.leftArrow)
        XCTAssertEqual(GCKeyCode.rightArrow.rawValue, KeyboardKey.rightArrow)
        XCTAssertEqual(GCKeyCode.rightShift.rawValue, KeyboardKey.rightShift)
        XCTAssertEqual(GCKeyCode.leftGUI.rawValue, KeyboardKey.leftCommand)
        XCTAssertEqual(GCKeyCode.rightGUI.rawValue, KeyboardKey.rightCommand)
        XCTAssertEqual(KeyboardKey.name(KeyboardKey.z), "Z")
        XCTAssertEqual(KeyboardKey.name(GCKeyCode.one.rawValue), "1")
        XCTAssertEqual(KeyboardKey.name(KeyboardKey.upArrow), "↑")
    }
    #endif
}
