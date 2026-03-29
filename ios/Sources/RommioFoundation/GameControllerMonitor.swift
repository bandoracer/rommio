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
        []
        #else
        #if canImport(GameController)
        GCController.controllers()
            .enumerated()
            .map { index, controller in
                ConnectedController(
                    deviceID: index,
                    name: controller.vendorName ?? "Game Controller"
                )
            }
        #else
        []
        #endif
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
