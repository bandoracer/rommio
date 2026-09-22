import Foundation
import Observation
import RommioFoundation

@MainActor
@Observable
public final class OneTouchSetupModel {
    public private(set) var pairing: SetupPairing?
    public private(set) var errorMessage: String?
    public private(set) var isApplying = false
    public private(set) var isApproved = false
    @ObservationIgnored private let service: any OneTouchSetupServing
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    public init(service: any OneTouchSetupServing = OneTouchSetupClient()) { self.service = service }

    public func start(deviceName: String, apply: @escaping @MainActor (SetupConfiguration) async throws -> Void) {
        guard task == nil else { return }
        let current = UUID()
        generation = current
        pairing = nil
        errorMessage = nil
        isApproved = false
        task = Task { [weak self] in
            guard let self else { return }
            defer { if self.generation == current { self.task = nil; self.isApplying = false } }
            do {
                let pairing = try await service.start(deviceName: deviceName)
                guard generation == current, !Task.isCancelled else {
                    await service.cancel(pairing)
                    return
                }
                self.pairing = pairing
                let deadline = ContinuousClock.now.advanced(by: .seconds(pairing.expiresInSeconds))
                while ContinuousClock.now < deadline {
                    try await Task.sleep(for: .seconds(pairing.pollIntervalSeconds))
                    let result = try await service.poll(pairing)
                    try Task.checkCancellation()
                    guard generation == current else { return }
                    switch result {
                    case .pending: continue
                    case .expired: throw OneTouchSetupError.expired
                    case .denied: throw OneTouchSetupError.denied
                    case .approved(let configuration):
                        try configuration.validate()
                        isApplying = true
                        try await apply(configuration)
                        // Local credentials are durable before acknowledgement. If
                        // acknowledgement is lost, the server expires this code.
                        try? await service.complete(pairing)
                        isApproved = true
                        return
                    }
                }
                throw OneTouchSetupError.expired
            } catch is CancellationError {
                // Dismissal is not a failed setup.
            } catch {
                if generation == current {
                    errorMessage = (error as? OneTouchSetupError)?.localizedDescription
                        ?? "Could not finish setup. Check your connection and try again."
                }
            }
        }
    }

    public func cancel() {
        guard !isApplying else { return }
        generation = UUID()
        task?.cancel()
        task = nil
        if let pairing {
            let service = service
            Task { await service.cancel(pairing) }
        }
        pairing = nil
    }
}
