import Foundation
import RommioContract

public enum RommClientError: Error, LocalizedError, Sendable {
    case invalidResponse
    case http(status: Int, bodyPreview: String)
    case invalidMultipartFile(URL)

    public var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "The server returned an invalid response."
        case let .http(status, bodyPreview):
            return "The server returned HTTP \(status): \(bodyPreview)"
        case let .invalidMultipartFile(url):
            return "Unable to load multipart file at \(url.path)."
        }
    }
}

public protocol RequestDecorating: Sendable {
    func decorate(_ request: URLRequest) async throws -> URLRequest
}

public struct PassthroughRequestDecorator: RequestDecorating {
    public init() {}

    public func decorate(_ request: URLRequest) async throws -> URLRequest { request }
}

public protocol RommServicing: Sendable {
    func getCurrentUser() async throws -> UserDTO
    func getHeartbeat() async throws -> HeartbeatDTO
    func getPlatforms() async throws -> [PlatformDTO]
    func getRecentlyAdded() async throws -> ItemsResponse<RomDTO>
    func getRoms(query: RomQuery) async throws -> ItemsResponse<RomDTO>
    func getRom(id: Int) async throws -> RomDTO
    func getCollections() async throws -> [CollectionResponseDTO]
    func getSmartCollections() async throws -> [SmartCollectionResponseDTO]
    func getVirtualCollections(type: String, limit: Int?) async throws -> [VirtualCollectionResponseDTO]
    func listSaves(romID: Int, deviceID: String?) async throws -> [SaveDTO]
    func listStates(romID: Int) async throws -> [StateDTO]
    func registerDevice(_ request: DeviceRegistrationRequest) async throws -> DeviceRegistrationResponse
    func uploadSave(
        romID: Int,
        emulator: String?,
        slot: String?,
        deviceID: String?,
        overwrite: Bool?,
        fileURL: URL
    ) async throws -> SaveDTO
    func uploadState(
        romID: Int,
        emulator: String?,
        fileURL: URL
    ) async throws -> StateDTO
    func download(from absoluteURL: URL, to destinationURL: URL) async throws
}

public final class RommAPIClient: RommServicing, @unchecked Sendable {
    public let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private let decorator: RequestDecorating

    public init(
        baseURL: URL,
        session: URLSession = .shared,
        decoder: JSONDecoder = JSONDecoder(),
        encoder: JSONEncoder = JSONEncoder(),
        decorator: RequestDecorating = PassthroughRequestDecorator()
    ) {
        self.baseURL = baseURL
        self.session = session
        self.decoder = decoder
        self.encoder = encoder
        self.decorator = decorator
    }

    public func getCurrentUser() async throws -> UserDTO {
        try await send(.currentUser)
    }

    public func getHeartbeat() async throws -> HeartbeatDTO {
        try await send(.heartbeat)
    }

    public func getPlatforms() async throws -> [PlatformDTO] {
        try await send(.platforms)
    }

    public func getRecentlyAdded() async throws -> ItemsResponse<RomDTO> {
        try await send(.recentlyAdded)
    }

    public func getRoms(query: RomQuery = RomQuery()) async throws -> ItemsResponse<RomDTO> {
        try await send(.roms(query))
    }

    public func getRom(id: Int) async throws -> RomDTO {
        try await send(.rom(id: id))
    }

    public func getCollections() async throws -> [CollectionResponseDTO] {
        try await send(.collections)
    }

    public func getSmartCollections() async throws -> [SmartCollectionResponseDTO] {
        try await send(.smartCollections)
    }

    public func getVirtualCollections(type: String = "all", limit: Int? = nil) async throws -> [VirtualCollectionResponseDTO] {
        try await send(.virtualCollections(type: type, limit: limit))
    }

    public func listSaves(romID: Int, deviceID: String? = nil) async throws -> [SaveDTO] {
        try await send(.saves(romID: romID, deviceID: deviceID))
    }

    public func listStates(romID: Int) async throws -> [StateDTO] {
        try await send(.states(romID: romID))
    }

    public func registerDevice(_ request: DeviceRegistrationRequest) async throws -> DeviceRegistrationResponse {
        try await send(.registerDevice, body: request)
    }

    public func uploadSave(
        romID: Int,
        emulator: String?,
        slot: String?,
        deviceID: String?,
        overwrite: Bool?,
        fileURL: URL
    ) async throws -> SaveDTO {
        try await sendMultipart(
            .uploadSave(romID: romID, emulator: emulator, slot: slot, deviceID: deviceID, overwrite: overwrite),
            fileURL: fileURL,
            partName: "saveFile"
        )
    }

    public func uploadState(
        romID: Int,
        emulator: String?,
        fileURL: URL
    ) async throws -> StateDTO {
        try await sendMultipart(
            .uploadState(romID: romID, emulator: emulator),
            fileURL: fileURL,
            partName: "stateFile"
        )
    }

    public func download(from absoluteURL: URL, to destinationURL: URL) async throws {
        var request = URLRequest(url: absoluteURL)
        request.httpMethod = "GET"
        request = try await decorator.decorate(request)
        let (temporaryURL, response) = try await session.download(for: request)
        try validate(response: response, responseBody: "")
        try FileManager.default.createDirectory(at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: destinationURL.path) {
            try FileManager.default.removeItem(at: destinationURL)
        }
        try FileManager.default.moveItem(at: temporaryURL, to: destinationURL)
    }

    private func send<Response: Decodable>(_ endpoint: RommEndpoint) async throws -> Response {
        try await send(endpoint, body: Optional<Data>.none)
    }

    private func send<Response: Decodable, Body: Encodable>(_ endpoint: RommEndpoint, body: Body?) async throws -> Response {
        var request = URLRequest(url: try endpoint.url(baseURL: baseURL))
        request.httpMethod = endpoint.method
        if let body {
            request.httpBody = try encoder.encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request = try await decorator.decorate(request)
        let (data, response) = try await session.data(for: request)
        try validate(response: response, responseBody: String(decoding: data, as: UTF8.self))
        return try decoder.decode(Response.self, from: data)
    }

    private func sendMultipart<Response: Decodable>(
        _ endpoint: RommEndpoint,
        fileURL: URL,
        partName: String
    ) async throws -> Response {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw RommClientError.invalidMultipartFile(fileURL)
        }

        let boundary = "RommioBoundary-\(UUID().uuidString)"
        var request = URLRequest(url: try endpoint.url(baseURL: baseURL))
        request.httpMethod = endpoint.method
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try MultipartFormDataBuilder(boundary: boundary)
            .addFile(partName: partName, fileURL: fileURL)
            .build()
        request = try await decorator.decorate(request)
        let (data, response) = try await session.data(for: request)
        try validate(response: response, responseBody: String(decoding: data, as: UTF8.self))
        return try decoder.decode(Response.self, from: data)
    }

    private func validate(response: URLResponse, responseBody: String) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RommClientError.invalidResponse
        }
        guard (200 ..< 300).contains(httpResponse.statusCode) else {
            throw RommClientError.http(status: httpResponse.statusCode, bodyPreview: String(responseBody.prefix(240)))
        }
    }
}

private struct MultipartFormDataBuilder {
    let boundary: String
    private var data = Data()

    init(boundary: String) {
        self.boundary = boundary
    }

    func addFile(partName: String, fileURL: URL) throws -> MultipartFormDataBuilder {
        var copy = self
        let filename = fileURL.lastPathComponent
        let fileData = try Data(contentsOf: fileURL)
        copy.data.append("--\(boundary)\r\n".data(using: .utf8)!)
        copy.data.append("Content-Disposition: form-data; name=\"\(partName)\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        copy.data.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        copy.data.append(fileData)
        copy.data.append("\r\n".data(using: .utf8)!)
        return copy
    }

    func build() -> Data {
        var copy = data
        copy.append("--\(boundary)--\r\n".data(using: .utf8)!)
        return copy
    }
}
