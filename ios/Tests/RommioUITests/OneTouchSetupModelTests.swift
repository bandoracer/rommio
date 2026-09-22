import XCTest
@testable import RommioFoundation
@testable import RommioUI

@MainActor
final class OneTouchSetupModelTests: XCTestCase {
    func testApprovalIsAcknowledgedOnlyAfterConfigurationIsStored() async throws {
        let service = FakeSetupService()
        let model = OneTouchSetupModel(service: service)
        var applied = false
        model.start(deviceName: "Phone") { _ in
            let acknowledgements = await service.completions
            XCTAssertEqual(acknowledgements, 0)
            applied = true
        }
        try await wait { model.isApproved }
        XCTAssertTrue(applied)
        let acknowledgements = await service.completions
        XCTAssertEqual(acknowledgements, 1)
        XCTAssertNil(model.errorMessage)
    }
    func testFailedStorageDoesNotAcknowledgeConfiguration() async throws {
        let service = FakeSetupService()
        let model = OneTouchSetupModel(service: service)
        model.start(deviceName: "Phone") { _ in throw OneTouchSetupError.unavailable }
        try await wait { model.errorMessage != nil }
        let acknowledgements = await service.completions
        XCTAssertEqual(acknowledgements, 0)
        XCTAssertFalse(model.isApproved)
        model.cancel()
    }
    func testCancelAndRestartCannotApplyOldApproval() async throws {
        let service = FakeSetupService()
        let model = OneTouchSetupModel(service: service)
        var staleApplied = false
        model.start(deviceName: "Old") { _ in staleApplied = true }
        try await wait { model.pairing != nil }
        model.cancel()
        model.start(deviceName: "New") { _ in }
        try await wait { model.isApproved }
        XCTAssertFalse(staleApplied)
        let starts = await service.starts
        XCTAssertEqual(starts, 2)
    }
    func testDeniedRequestShowsRetryableErrorWithoutApplying() async throws {
        let service = FakeSetupService(denied: true)
        let model = OneTouchSetupModel(service: service)
        model.start(deviceName: "Phone") { _ in XCTFail("Denied setup applied") }
        try await wait { model.errorMessage != nil }
        XCTAssertTrue(model.errorMessage?.contains("denied") == true)
        model.cancel()
    }
    private func wait(until condition: () -> Bool) async throws {
        for _ in 0..<100 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTFail("Setup did not reach expected state")
    }
}

private actor FakeSetupService: OneTouchSetupServing {
    let denied: Bool
    var starts = 0
    var completions = 0
    init(denied: Bool = false) { self.denied = denied }
    func start(deviceName: String) async throws -> SetupPairing {
        starts += 1
        return try JSONDecoder().decode(SetupPairing.self, from: Data("{\"code\":\"ABCDEF\",\"secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"approveUrl\":\"https://example.com/a/ABCDEF\",\"expiresInSeconds\":600,\"pollIntervalSeconds\":1}".utf8))
    }
    func poll(_ pairing: SetupPairing) async throws -> SetupPollResult {
        if denied { return .denied }
        return .approved(try JSONDecoder().decode(SetupConfiguration.self, from: Data("{\"rommUrl\":\"https://romm.example.com\",\"accessProfile\":{\"type\":\"serviceToken\",\"clientId\":\"test-id\",\"clientSecret\":\"test-secret\"}}".utf8)))
    }
    func complete(_ pairing: SetupPairing) async throws { completions += 1 }
    func cancel(_ pairing: SetupPairing) async {}
}
