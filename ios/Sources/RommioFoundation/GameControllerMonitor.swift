import Foundation

#if canImport(GameController)
import GameController
#endif

public protocol ControllerMonitoring: Sendable {
    func currentControllers() -> [ConnectedController]
    func updates() -> AsyncStream<[ConnectedController]>
}

public final class GameControllerMonitor: @unchecked Sendable, ControllerMonitoring {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<[ConnectedController]>.Continuation] = [:]

    public init() {
        #if canImport(GameController)
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(handleControllerChange),
            name: .GCControllerDidConnect,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleControllerChange),
            name: .GCControllerDidDisconnect,
            object: nil
        )
        #endif
    }

    deinit {
        #if canImport(GameController)
        NotificationCenter.default.removeObserver(self)
        #endif
    }

    public func currentControllers() -> [ConnectedController] {
        #if targetEnvironment(simulator)
        // A Simulator counts only the Debug virtual controller (PlayerGamepadInput).
        guard ProcessInfo.processInfo.environment["ROMMIO_VIRTUAL_CONTROLLER"] == "1" else { return [] }
        #endif
        #if canImport(GameController)
        // Only full gamepads count: a controller that can play (touch controls hide for it),
        // identified for as long as it stays connected (the router's players use the same ID).
        return GCController.controllers()
            .filter { $0.extendedGamepad != nil }
            .filter { controller in
                #if targetEnvironment(simulator)
                // Not the Simulator's own stand-in gamepad (the Mac's input).
                return controller.vendorName == "Apple Touch Controller"
                #else
                return true
                #endif
            }
            .map { controller in
                ConnectedController(
                    deviceID: Self.deviceID(controller),
                    name: controller.vendorName ?? "Game Controller"
                )
            }
        #else
        return []
        #endif
    }

    public func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            lock.unlock()
            continuation.yield(currentControllers())
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    #if canImport(GameController)
    /// A connected controller's identity while it stays connected.
    public static func deviceID(_ controller: GCController) -> Int {
        ObjectIdentifier(controller).hashValue
    }

    @objc
    private func handleControllerChange() {
        let snapshot = currentControllers()
        lock.lock()
        let values = Array(continuations.values)
        lock.unlock()
        values.forEach { $0.yield(snapshot) }
    }
    #endif
}
