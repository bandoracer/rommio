import Foundation
import RommioContract

public struct RommProgressTransport: ProgressTransport {
    private let client: RommAPIClient
    private let binding: ProgressBinding
    private let verifyOwner: @Sendable () async throws -> Void
    public init(client: RommAPIClient, binding: ProgressBinding, verifyOwner: @escaping @Sendable () async throws -> Void) {
        self.client = client; self.binding = binding; self.verifyOwner = verifyOwner
    }
    public func qualify() async throws {
        try await verifyOwner()
        guard ApplicationProgress.serverKey(client.baseURL) == binding.serverKey else { throw AccountBindingError() }
        guard try await client.getHeartbeat().system.version == "5.2.0" else { throw ProgressStoreError.invalid("Sync requires qualified RomM 5.2.0. Progress remains local.") }
        guard try await String(client.getCurrentUser().id) == binding.accountId else { throw AccountBindingError() }
    }
    public func inventory() async throws -> [ProgressRemoteRecord] {
        try await verifyOwner()
        return try await client.listStates(romID: Int(binding.romId)).map { state in
            guard state.romID == binding.romId else { throw ProgressStoreError.invalid("Wrong ROM in inventory") }
            return ProgressRemoteRecord(name: state.fileName, id: state.id, byteLength: state.fileSizeBytes)
        }
    }
    public func read(_ record: ProgressRemoteRecord, to destination: URL, maxBytes: Int64) async throws {
        try await verifyOwner()
        guard record.byteLength >= 0 && record.byteLength <= maxBytes else { throw ProgressStoreError.invalid("Remote artifact exceeds declared size") }
        let url = client.baseURL.appending(path: "api/states/\(record.id)/content")
        _ = try await client.download(from: url, to: destination, expectedBytes: record.byteLength)
    }
    public func upload(name: String, source: URL) async throws -> ProgressRemoteRecord {
        try await verifyOwner()
        guard name.hasPrefix("__rommio3_\(ProgressProtocol.bindingKey(binding))_"), !name.contains("/") else { throw ProgressStoreError.invalid("Invalid transport name") }
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appending(path: name)
        try FileManager.default.copyItem(at: source, to: file)
        let state = try await client.uploadState(romID: Int(binding.romId), emulator: "rommio-progress-v3", fileURL: file)
        guard state.romID == binding.romId else { throw ProgressStoreError.invalid("Wrong upload binding") }
        return ProgressRemoteRecord(name: state.fileName, id: state.id, byteLength: state.fileSizeBytes)
    }
}
