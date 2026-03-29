import XCTest
@testable import RommioContract

final class ClientIdentityTests: XCTestCase {
    func testDeviceRegistrationRequestUsesExplicitIdentity() {
        let identity = ClientIdentity(
            platform: "ios",
            client: "romm-ios-native",
            clientVersion: "0.1.0",
            hostname: "iPhone",
            deviceRegistrationName: "RomM Native iPhone"
        )

        let request = identity.deviceRegistrationRequest()

        XCTAssertEqual(request.platform, "ios")
        XCTAssertEqual(request.client, "romm-ios-native")
        XCTAssertEqual(request.clientVersion, "0.1.0")
        XCTAssertEqual(request.hostname, "iPhone")
        XCTAssertEqual(request.name, "RomM Native iPhone")
    }
}
