import XCTest
@testable import RommioContract

final class RommEndpointTests: XCTestCase {
    func testRomsEndpointPreservesPagingAndFilterContract() throws {
        let baseURL = URL(string: "https://romm.example.com")!
        let url = try RommEndpoint.roms(
            .init(
                platformIDs: 1,
                collectionID: 42,
                limit: 25,
                offset: 50,
                orderBy: "name",
                orderDirection: "asc"
            )
        ).url(baseURL: baseURL)

        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let items = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })

        XCTAssertEqual(url.path, "/api/roms")
        XCTAssertEqual(items["platform_ids"], "1")
        XCTAssertEqual(items["collection_id"], "42")
        XCTAssertEqual(items["limit"], "25")
        XCTAssertEqual(items["offset"], "50")
        XCTAssertEqual(items["group_by_meta_id"], "1")
        XCTAssertEqual(items["order_by"], "name")
        XCTAssertEqual(items["order_dir"], "asc")
    }

    func testDeviceRegistrationEndpointBuildsExpectedPath() throws {
        let baseURL = URL(string: "https://romm.example.com")!
        let url = try RommEndpoint.registerDevice.url(baseURL: baseURL)

        XCTAssertEqual(url.absoluteString, "https://romm.example.com/api/devices")
    }
}
