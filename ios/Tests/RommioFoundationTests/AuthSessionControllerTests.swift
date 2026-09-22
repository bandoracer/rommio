import XCTest
import Network
@testable import RommioContract
@testable import RommioFoundation

final class AuthSessionControllerTests: XCTestCase {
    override func tearDown() {
        super.tearDown()
        HTTPCookieStorage.shared.cookies?.forEach { HTTPCookieStorage.shared.deleteCookie($0) }
    }

    func testVerifiedAccountSurvivesLogoutAndRejectsAnotherAccountAfterControllerRecreation() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let session = makeStubSession(recorder: recorder)
        let harness = try ControllerHarness(session: session)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        let credentials = DirectLoginCredentials(username: "player", password: "password")
        try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: credentials)
        let owner = VerifiedAccount(baseURL: saved.baseURL, userID: 1)
        XCTAssertEqual(try harness.secretStore.verifiedAccount(profileID: saved.id), owner)
        try await harness.controller.logout(profileID: saved.id)
        let recreated = AuthSessionController(profileStore: harness.profileStore, secretStore: harness.secretStore, session: session)
        recorder.userID = 2
        do { try await recreated.loginWithDirectCredentials(profileID: saved.id, credentials: credentials); XCTFail("Rebound account") }
        catch is AccountBindingError { }
        XCTAssertNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
        XCTAssertEqual(try harness.secretStore.verifiedAccount(profileID: saved.id), owner)
        recorder.userID = 1
        try await recreated.loginWithDirectCredentials(profileID: saved.id, credentials: credentials)
        XCTAssertNotNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
    }

    func testWrongAccountCandidateDoesNotReplaceBearerOrBasicCredentials() async throws {
        for mode in [OriginAuthMode.rommBearerPassword, .rommBasicLegacy] {
            let recorder = ScopeRecordingProtocol.Recorder()
            let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
            let saved = profile(edge: .none, origin: mode)
            try await harness.profileStore.save(saved, makeActive: true)
            try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: DirectLoginCredentials(username: "original", password: "password"))
            let token = try harness.secretStore.readTokenBundle(profileID: saved.id)
            let basic = try harness.secretStore.readBasicCredentials(profileID: saved.id)
            recorder.userID = 99; recorder.tokenValue = "wrong-account-token"
            do {
                try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: DirectLoginCredentials(username: "different", password: "password"))
                XCTFail("Accepted another account")
            } catch is AccountBindingError { }
            XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id), token)
            XCTAssertEqual(try harness.secretStore.readBasicCredentials(profileID: saved.id), basic)
            XCTAssertEqual(try harness.secretStore.verifiedAccount(profileID: saved.id)?.userID, 1)
        }
    }

    func testUnverifiedTokenAndChangedServerCannotReplaceOwnedCredentials() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
        var saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        let credentials = DirectLoginCredentials(username: "player", password: "password")
        recorder.userStatus = 500
        do { try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: credentials); XCTFail("Unverified token installed") }
        catch { }
        XCTAssertNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
        XCTAssertNil(try harness.secretStore.verifiedAccount(profileID: saved.id))
        recorder.userStatus = 200
        try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: credentials)
        saved.baseURL = URL(string: "https://another.example.com")!
        try await harness.profileStore.save(saved, makeActive: true)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        do { _ = try await client.getCurrentUser(); XCTFail("Reused credentials on edited server") }
        catch is AccountBindingError { }
    }

    func testValidationRejectsChangedCookieAccountAndRetainsOwnership() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
        let saved = profile(edge: .none, origin: .rommOIDCSession)
        try await harness.profileStore.save(saved, makeActive: true)
        let first = try await harness.controller.validateProfile(profileID: saved.id)
        XCTAssertEqual(first, .connected)
        recorder.userID = 2
        do { _ = try await harness.controller.validateProfile(profileID: saved.id); XCTFail("Accepted a changed browser account") }
        catch is AccountBindingError { }
        let after = try await harness.profileStore.profile(id: saved.id)
        XCTAssertEqual(after?.sessionState.hasOriginSession, false)
        XCTAssertEqual(try harness.secretStore.verifiedAccount(profileID: saved.id)?.userID, 1)
    }

    func testRefreshCannotInstallTokenForAnotherAccount() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: DirectLoginCredentials(username: "player", password: "password"))
        var previous = try XCTUnwrap(harness.secretStore.readTokenBundle(profileID: saved.id))
        previous.expiresAt = "2000-01-01T00:00:00Z"
        try harness.secretStore.saveTokenBundle(previous, for: saved.id)
        recorder.userID = 2; recorder.tokenValue = "wrong-refresh-token"
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        do { _ = try await client.getCurrentUser(); XCTFail("Changed account on refresh") }
        catch is AccountBindingError { }
        XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id), previous)
    }

    func testShouldSendCookiesMatchesAndroidRules() {
        XCTAssertFalse(shouldSendCookies(for: profile(edge: .none, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .cloudflareAccessSession, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .genericCookieSSO, origin: .rommBasicLegacy)))
        XCTAssertFalse(shouldSendCookies(for: profile(edge: .cloudflareAccessService, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .cloudflareAccessService, origin: .rommOIDCSession)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .none, origin: .rommOIDCSession)))
    }

    func testCompleteEdgeAccessAttemptCapturesReusableCookieState() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessSession, origin: .rommBearerPassword)
        try await harness.profileStore.save(profile, makeActive: true)

        let cookie = try XCTUnwrap(
            HTTPCookie(properties: [
                .domain: "romm.example.com",
                .path: "/",
                .name: "CF_Authorization",
                .value: "cookie-value",
                .secure: "TRUE",
                .expires: Date(timeIntervalSinceNow: 300),
            ])
        )
        let config = try await harness.controller.interactiveSessionConfig(profileID: profile.id, provider: .edge)
        try await harness.controller.importBrowserCookies(config: config, cookies: [BrowserCookie(cookie)])

        let accessState = try await harness.controller.completeEdgeAccessAttempt(profileID: profile.id)
        let persisted = try await harness.profileStore.profile(id: profile.id)

        XCTAssertEqual(accessState.status, .checking)
        XCTAssertTrue(accessState.cookieNamesSeen.contains("CF_Authorization"))
        XCTAssertTrue(accessState.lastError?.contains("native access test") == true)
        XCTAssertEqual(persisted?.sessionState.hasEdgeSession, true)
    }

    func testLogoutClearsOnlyItsProfileCookiesAndRejectsOldBrowserCompletion() async throws {
        let harness = try ControllerHarness()
        var a = profile(edge: .cloudflareAccessSession, origin: .rommOIDCSession, status: .connected)
        a.id = "profile-a"
        var b = a; b.id = "profile-b"
        try await harness.profileStore.save(a, makeActive: true)
        try await harness.profileStore.save(b, makeActive: false)
        let aConfig = try await harness.controller.interactiveSessionConfig(profileID: a.id, provider: .edge)
        let bConfig = try await harness.controller.interactiveSessionConfig(profileID: b.id, provider: .edge)
        let cookie = BrowserCookie(name: "CF_Authorization", value: "synthetic", domain: a.baseURL.host!, path: "/", secure: true, httpOnly: true, expiresAt: Date().addingTimeInterval(300))
        try await harness.controller.importBrowserCookies(config: aConfig, cookies: [cookie])
        try await harness.controller.importBrowserCookies(config: bConfig, cookies: [cookie])
        try await harness.controller.logout(profileID: a.id)
        let aAfter = try await harness.controller.completeEdgeAccessAttempt(profileID: a.id)
        let bAfter = try await harness.controller.completeEdgeAccessAttempt(profileID: b.id)
        XCTAssertTrue(aAfter.cookieNamesSeen.isEmpty)
        XCTAssertEqual(bAfter.cookieNamesSeen, ["CF_Authorization"])
        do { try await harness.controller.importBrowserCookies(config: aConfig, cookies: [cookie]); XCTFail("Old browser restored signed-out cookies") }
        catch is CancellationError { }
        try await harness.controller.deleteProfile(profileID: a.id)
        let bStillValid = try await harness.controller.completeEdgeAccessAttempt(profileID: b.id)
        XCTAssertEqual(bStillValid.cookieNamesSeen, ["CF_Authorization"])
    }

    func testLegacySharedCookiesAreNotAssignedToAProfile() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessSession, origin: .rommOIDCSession)
        try await harness.profileStore.save(profile, makeActive: true)
        HTTPCookieStorage.shared.setCookie(HTTPCookie(properties: [.domain: profile.baseURL.host!, .path: "/", .name: "CF_Authorization", .value: "unowned", .secure: "TRUE"])!)
        let state = try await harness.controller.completeEdgeAccessAttempt(profileID: profile.id)
        XCTAssertTrue(state.cookieNamesSeen.isEmpty)
    }

    func testLogoutPreservesServerAccessByDefault() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessService, origin: .rommBearerPassword, status: .connected)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )
        try harness.secretStore.saveBasicCredentials(
            DirectLoginCredentials(username: "user", password: "pass"),
            for: profile.id
        )

        try await harness.controller.logout(profileID: profile.id, clearServerAccess: false)

        let persisted = try await harness.profileStore.profile(id: profile.id)
        XCTAssertEqual(persisted?.serverAccess.status, .ready)
        XCTAssertEqual(persisted?.status, .reauthRequiredOrigin)
        XCTAssertEqual(persisted?.sessionState.hasOriginSession, false)
        XCTAssertNotNil(try harness.secretStore.readCloudflareCredentials(profileID: profile.id))
        XCTAssertNil(try harness.secretStore.readBasicCredentials(profileID: profile.id))
    }

    func testLogoutCanClearServerAccess() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessService, origin: .rommOIDCSession, status: .connected)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )
        try harness.secretStore.saveDeviceID("device-ios-1", for: profile.id)

        try await harness.controller.logout(profileID: profile.id, clearServerAccess: true)

        let persisted = try await harness.profileStore.profile(id: profile.id)
        XCTAssertEqual(persisted?.serverAccess.status, .unknown)
        XCTAssertEqual(persisted?.status, .reauthRequiredOrigin)
        XCTAssertEqual(persisted?.sessionState.hasEdgeSession, false)
        XCTAssertNil(try harness.secretStore.readCloudflareCredentials(profileID: profile.id))
        XCTAssertNil(try harness.secretStore.readDeviceID(profileID: profile.id))
    }

    func testBearerPasswordGrantUsesPlayerScopesWithoutRequiringAnAdmin() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let session = makeStubSession(recorder: recorder)
        let harness = try ControllerHarness(session: session)
        let profile = profile(edge: .cloudflareAccessService, origin: .rommBearerPassword, status: .reauthRequiredOrigin)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )

        try await harness.controller.loginWithDirectCredentials(
            profileID: profile.id,
            credentials: DirectLoginCredentials(username: "player", password: " password ")
        )

        let capturedBody = try XCTUnwrap(recorder.tokenRequestBody)
        let decodedBody = capturedBody.replacingOccurrences(of: "+", with: " ")
            .removingPercentEncoding ?? capturedBody

        XCTAssertTrue(decodedBody.contains("grant_type=password"))
        XCTAssertTrue(decodedBody.contains("username=player"))
        XCTAssertTrue(decodedBody.contains("password= password "))
        XCTAssertTrue(decodedBody.contains("scope=me.read me.write roms.read platforms.read assets.read assets.write devices.read devices.write firmware.read collections.read roms.user.read roms.user.write"))
        XCTAssertFalse(decodedBody.contains("users.read"))
        XCTAssertFalse(decodedBody.contains("users.write"))
        XCTAssertEqual(recorder.currentUserAuthorization, "Bearer token-123")
        XCTAssertEqual(recorder.deviceRegistrationAuthorization, "Bearer token-123")
    }

    func testExpiredRequestsShareOneRefreshAndUseTheRotatedToken() async throws {
        let recorder = RefreshRecordingProtocol.Recorder()
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<20 { group.addTask { _ = try await client.getPlatforms() } }
            try await group.waitForAll()
        }
        XCTAssertEqual(recorder.tokenCalls, 1)
        XCTAssertEqual(recorder.apiCalls, 20)
        XCTAssertEqual(Set(recorder.authorizations), ["Bearer rotated"])
        XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id)?.refreshToken, "refresh-rotated")
    }

    func testRefreshFailuresKeepCredentialsUnlessRomMExplicitlyRejectsThem() async throws {
        for (status, body, shouldClear) in [
            (503, "unavailable", false), (401, "<html>Sign in</html>", false),
            (401, #"{"detail":"Unauthorized"}"#, false), (200, #"{"access_token":""}"#, false),
            (200, "invalid json", false), (401, #"{"detail":"Invalid refresh token"}"#, true),
            (400, #"{"error":"invalid_grant"}"#, true),
        ] {
            let recorder = RefreshRecordingProtocol.Recorder(tokenStatus: status, tokenBody: body)
            let harness = try refreshHarness(recorder: recorder)
            let saved = profile(edge: .none, origin: .rommBearerPassword)
            try await harness.profileStore.save(saved, makeActive: true)
            let original = expiringToken()
            try harness.secretStore.saveTokenBundle(original, for: saved.id)
            let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
            do { _ = try await client.getPlatforms(); XCTFail("Expected refresh error for \(status): \(body)") }
            catch is TokenRefreshError {} catch { XCTFail("Unexpected error: \(error)") }
            XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id), shouldClear ? nil : original)
            XCTAssertEqual(recorder.apiCalls, 0)
        }
    }

    func testUnauthorizedReadRefreshesOnceAndRetryIsBounded() async throws {
        for alwaysReject in [false, true] {
            let recorder = RefreshRecordingProtocol.Recorder(alwaysReject: alwaysReject, rejectOriginal: true)
            let harness = try refreshHarness(recorder: recorder)
            let saved = profile(edge: .none, origin: .rommBearerPassword)
            try await harness.profileStore.save(saved, makeActive: true)
            try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
            let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
            do {
                _ = try await client.getPlatforms()
                XCTAssertFalse(alwaysReject)
            } catch {
                guard alwaysReject, case RommClientError.http(status: 401, _) = error else { throw error }
            }
            XCTAssertEqual(recorder.tokenCalls, 1)
            XCTAssertEqual(recorder.apiCalls, 2)
            XCTAssertEqual(recorder.authorizations, ["Bearer original", "Bearer rotated"])
        }
    }

    func testUnauthorizedMutationIsNotReplayed() async throws {
        let recorder = RefreshRecordingProtocol.Recorder(alwaysReject: true)
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        do { _ = try await client.registerDevice(iosClientIdentity().deviceRegistrationRequest()); XCTFail("Expected 401") }
        catch { guard case RommClientError.http(status: 401, _) = error else { throw error } }
        XCTAssertEqual(recorder.apiCalls, 1)
        XCTAssertEqual(recorder.tokenCalls, 0)
    }

    func testSignOutDuringRefreshCannotRestoreCredentials() async throws {
        let recorder = RefreshRecordingProtocol.Recorder(blockRefresh: true)
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        let request = Task { try await client.getPlatforms() }
        await fulfillment(of: [recorder.refreshStarted], timeout: 5)
        try await harness.controller.logout(profileID: saved.id)
        recorder.releaseRefresh.signal()
        do { _ = try await request.value; XCTFail("Signed-out refresh must fail") } catch {}
        XCTAssertNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
        XCTAssertEqual(recorder.apiCalls, 0)
    }

    func testCancelingOneWaiterDoesNotCancelOtherRequestsRefresh() async throws {
        let recorder = RefreshRecordingProtocol.Recorder(blockRefresh: true)
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        let first = Task { try await client.getPlatforms() }
        await fulfillment(of: [recorder.refreshStarted], timeout: 5)
        let second = Task { try await client.getPlatforms() }
        first.cancel()
        recorder.releaseRefresh.signal()
        do { _ = try await first.value; XCTFail("Canceled request must stop") } catch {}
        _ = try await second.value
        XCTAssertEqual(recorder.tokenCalls, 1)
        XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id)?.accessToken, "rotated")
    }

    func testRealHTTPDownloadSharesRefreshWithAPIAndPreservesFileWhenRetryFails() async throws {
        let server = try AuthHTTPFixture()
        server.start()
        defer { server.stop() }
        await fulfillment(of: [server.ready], timeout: 5)
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let harness = try ControllerHarness(session: session)
        var saved = profile(edge: .none, origin: .rommBearerPassword)
        saved.baseURL = try XCTUnwrap(server.url)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        let target = root.appending(path: "game.rom")
        let romURL = saved.baseURL.appending(path: "rom")
        async let api = client.getPlatforms()
        async let download = client.download(from: romURL, to: target, expectedBytes: 11)
        _ = try await (api, download)
        XCTAssertEqual(try Data(contentsOf: target), Data("fixture-rom".utf8))
        XCTAssertEqual(server.tokenCalls, 1)

        // Server rejects a token before its recorded expiry; refresh and retry the download once.
        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        _ = try await client.download(from: saved.baseURL.appending(path: "rom"), to: target, expectedBytes: 11)
        XCTAssertEqual(server.tokenCalls, 2)
        XCTAssertEqual(server.romCalls, 3)

        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        do {
            _ = try await client.download(from: saved.baseURL.appending(path: "reject"), to: target, expectedBytes: 11)
            XCTFail("Both download attempts should fail")
        } catch {}
        XCTAssertEqual(server.tokenCalls, 3)
        XCTAssertEqual(server.rejectCalls, 2)
        XCTAssertEqual(try Data(contentsOf: target), Data("fixture-rom".utf8))
    }

    func testEdgeLoginResponseDoesNotForceOriginRefresh() async throws {
        let recorder = RefreshRecordingProtocol.Recorder(edgeHTML: true)
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        let client = await harness.controller.client(for: saved.id, baseURL: saved.baseURL)
        do { _ = try await client.getPlatforms(); XCTFail("Expected protected access failure") }
        catch { guard case RommClientError.http(status: 401, _) = error else { throw error } }
        XCTAssertEqual(recorder.apiCalls, 1)
        XCTAssertEqual(recorder.tokenCalls, 0)
        XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id)?.accessToken, "original")
    }

    func testValidationKeepsVerifiedSessionOnTransientRefreshFailure() async throws {
        let recorder = RefreshRecordingProtocol.Recorder(tokenStatus: 503, tokenBody: "unavailable")
        let harness = try refreshHarness(recorder: recorder)
        let saved = profile(edge: .cloudflareAccessService, origin: .rommBearerPassword, status: .connected)
        try await harness.profileStore.save(saved, makeActive: true)
        let original = expiringToken()
        try harness.secretStore.saveTokenBundle(original, for: saved.id)
        do { _ = try await harness.controller.validateProfile(profileID: saved.id); XCTFail("Expected refresh failure") }
        catch { guard case TokenRefreshError.unavailable(status: 503) = error else { throw error } }
        let persisted = try await harness.profileStore.profile(id: saved.id)
        XCTAssertEqual(persisted?.status, .connected)
        XCTAssertEqual(persisted?.serverAccess.status, .ready)
        XCTAssertEqual(persisted?.sessionState.hasOriginSession, true)
        XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id), original)
    }

    func testRejectedPasswordGrantCannotValidateAsPreviousAccount() async throws {
        for status in [401, 403, 503] {
            let recorder = RefreshRecordingProtocol.Recorder(tokenStatus: status, tokenBody: "{}")
            let harness = try refreshHarness(recorder: recorder)
            let saved = profile(edge: .none, origin: .rommBearerPassword, status: .connected)
            try await harness.profileStore.save(saved, makeActive: true)
            let previous = expiringToken(expired: false)
            try harness.secretStore.saveTokenBundle(previous, for: saved.id)
            do {
                try await harness.controller.loginWithDirectCredentials(profileID: saved.id,
                    credentials: DirectLoginCredentials(username: "different-user", password: "wrong"))
                XCTFail("Failed password grant must not fall back to the previous session")
            } catch {}
            XCTAssertEqual(recorder.apiCalls, 0)
            XCTAssertEqual(try harness.secretStore.readTokenBundle(profileID: saved.id), previous)
            XCTAssertNil(try harness.secretStore.readBasicCredentials(profileID: saved.id))
        }
    }

    func testExplicitBasicLoginVerifiesSuppliedCredentialsAndReplacesOldToken() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
        let saved = profile(edge: .none, origin: .rommBasicLegacy)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        let credentials = DirectLoginCredentials(username: "player", password: " password ")
        try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: credentials)
        XCTAssertNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
        XCTAssertEqual(try harness.secretStore.readBasicCredentials(profileID: saved.id), credentials)
        XCTAssertEqual(recorder.currentUserAuthorization, "Basic " + Data("player: password ".utf8).base64EncodedString())
        XCTAssertNil(recorder.tokenRequestBody)
    }

    func testMissingTokenEndpointFallsBackOnlyAfterBasicCredentialsAreVerified() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        recorder.tokenStatus = 404
        let harness = try ControllerHarness(session: makeStubSession(recorder: recorder))
        let saved = profile(edge: .none, origin: .rommBearerPassword)
        try await harness.profileStore.save(saved, makeActive: true)
        try harness.secretStore.saveTokenBundle(expiringToken(expired: false), for: saved.id)
        let credentials = DirectLoginCredentials(username: "player", password: " password ")
        try await harness.controller.loginWithDirectCredentials(profileID: saved.id, credentials: credentials)
        let persisted = try await harness.profileStore.profile(id: saved.id)
        XCTAssertEqual(persisted?.originAuthMode, .rommBasicLegacy)
        XCTAssertNil(try harness.secretStore.readTokenBundle(profileID: saved.id))
        XCTAssertEqual(try harness.secretStore.readBasicCredentials(profileID: saved.id), credentials)
        XCTAssertEqual(recorder.currentUserAuthorization, "Basic " + Data("player: password ".utf8).base64EncodedString())
    }

    private func expiringToken(expired: Bool = true) -> TokenBundle {
        TokenBundle(accessToken: "original", refreshToken: "refresh-original", tokenType: "Bearer",
                    expiresAt: ISO8601DateFormatter().string(from: Date(timeIntervalSinceNow: expired ? -60 : 3600)))
    }

    private func refreshHarness(recorder: RefreshRecordingProtocol.Recorder) throws -> ControllerHarness {
        RefreshRecordingProtocol.recorder = recorder
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RefreshRecordingProtocol.self]
        return try ControllerHarness(session: URLSession(configuration: config))
    }

    private func profile(
        edge: EdgeAuthMode,
        origin: OriginAuthMode,
        status: AuthStatus = .reauthRequiredOrigin
    ) -> ServerProfile {
        ServerProfile(
            id: "server-\(UUID().uuidString)",
            label: "Test Server",
            baseURL: URL(string: "https://romm.example.com")!,
            edgeAuthMode: edge,
            originAuthMode: origin,
            capabilities: AuthCapabilities(
                cloudflareAccessDetected: edge != .none,
                genericCookieSSODetected: edge == .genericCookieSSO,
                rommOIDCAvailable: origin == .rommOIDCSession,
                rommTokenAvailable: origin == .rommBearerPassword
            ),
            serverAccess: ServerAccessState(status: .ready, verifiedAt: "2026-03-18T12:00:00Z"),
            sessionState: SessionState(
                hasEdgeSession: edge != .none,
                hasOriginSession: true,
                lastValidatedAt: "2026-03-18T12:00:00Z"
            ),
            isActive: true,
            status: status,
            lastValidationAt: "2026-03-18T12:00:00Z",
            createdAt: "2026-03-18T11:00:00Z",
            updatedAt: "2026-03-18T12:00:00Z"
        )
    }
}

private struct ControllerHarness {
    let profileStore: GRDBServerProfileStore
    let secretStore: InMemorySecretStore
    let controller: AuthSessionController

    init(session: URLSession = .shared) throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let profileStore = GRDBServerProfileStore(database: database)
        let secretStore = InMemorySecretStore()
        self.profileStore = profileStore
        self.secretStore = secretStore
        self.controller = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            session: session
        )
    }
}

private final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private var storage: [String: Data] = [:]
    private let lock = NSLock()

    func save(_ value: Data, for key: String) throws {
        lock.lock()
        storage[key] = value
        lock.unlock()
    }

    func read(key: String) throws -> Data? {
        lock.lock()
        let value = storage[key]
        lock.unlock()
        return value
    }

    func remove(key: String) throws {
        lock.lock()
        storage.removeValue(forKey: key)
        lock.unlock()
    }
}

private final class ScopeRecordingProtocol: URLProtocol, @unchecked Sendable {
    final class Recorder: @unchecked Sendable {
        var tokenRequestBody: String?
        var tokenStatus = 200
        var tokenValue = "token-123"
        var userID = 1
        var userStatus = 200
        var currentUserAuthorization: String?
        var deviceRegistrationAuthorization: String?
    }

    nonisolated(unsafe) static var recorder: Recorder?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        switch (request.httpMethod ?? "GET", url.path) {
        case ("POST", "/api/token"):
            Self.recorder?.tokenRequestBody = requestBodyString(request)
            respond(
                status: Self.recorder?.tokenStatus ?? 200,
                headers: ["Content-Type": "application/json"],
                body: "{\"access_token\":\"\(Self.recorder?.tokenValue ?? "token-123")\",\"refresh_token\":\"refresh-123\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
            )
        case ("GET", "/api/heartbeat"):
            respond(
                status: 200,
                headers: ["Content-Type": "application/json"],
                body: #"{"SYSTEM":{"VERSION":"1.0.0","SHOW_SETUP_WIZARD":false}}"#
            )
        case ("GET", "/api/users/me"):
            Self.recorder?.currentUserAuthorization = request.value(forHTTPHeaderField: "Authorization")
            respond(
                status: Self.recorder?.userStatus ?? 200,
                headers: ["Content-Type": "application/json"],
                body: "{\"id\":\(Self.recorder?.userID ?? 1),\"username\":\"player\",\"email\":\"player@example.com\",\"role\":\"admin\"}"
            )
        case ("POST", "/api/devices"):
            Self.recorder?.deviceRegistrationAuthorization = request.value(forHTTPHeaderField: "Authorization")
            respond(
                status: 201,
                headers: ["Content-Type": "application/json"],
                body: #"{"device_id":"device-123"}"#
            )
        default:
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
        }
    }

    override func stopLoading() {}

    private func respond(status: Int, headers: [String: String], body: String) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private func requestBodyString(_ request: URLRequest) -> String? {
        if let httpBody = request.httpBody {
            return String(data: httpBody, encoding: .utf8)
        }

        guard let bodyStream = request.httpBodyStream else {
            return nil
        }

        bodyStream.open()
        defer { bodyStream.close() }

        let bufferSize = 1024
        var data = Data()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while bodyStream.hasBytesAvailable {
            let read = bodyStream.read(buffer, maxLength: bufferSize)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }

        return String(data: data, encoding: .utf8)
    }
}

private func makeStubSession(recorder: ScopeRecordingProtocol.Recorder) -> URLSession {
    ScopeRecordingProtocol.recorder = recorder
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScopeRecordingProtocol.self]
    configuration.httpCookieStorage = HTTPCookieStorage.shared
    return URLSession(configuration: configuration)
}

private final class RefreshRecordingProtocol: URLProtocol, @unchecked Sendable {
    final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var tokens = 0
        private var calls = 0
        private var headers: [String] = []
        let tokenStatus: Int
        let tokenBody: String
        let alwaysReject: Bool
        let rejectOriginal: Bool
        let blockRefresh: Bool
        let edgeHTML: Bool
        let refreshStarted = XCTestExpectation(description: "Refresh started")
        let releaseRefresh = DispatchSemaphore(value: 0)
        var tokenCalls: Int { lock.withLock { tokens } }
        var apiCalls: Int { lock.withLock { calls } }
        var authorizations: [String] { lock.withLock { headers } }

        init(tokenStatus: Int = 200,
             tokenBody: String = #"{"access_token":"rotated","refresh_token":"refresh-rotated","token_type":"Bearer","expires_in":3600}"#,
             alwaysReject: Bool = false, rejectOriginal: Bool = false, blockRefresh: Bool = false, edgeHTML: Bool = false) {
            self.tokenStatus = tokenStatus; self.tokenBody = tokenBody
            self.alwaysReject = alwaysReject; self.rejectOriginal = rejectOriginal; self.blockRefresh = blockRefresh
            self.edgeHTML = edgeHTML
        }
        func reply(to request: URLRequest) -> (Int, String) {
            if request.url?.path == "/api/token" {
                lock.withLock { tokens += 1 }
                refreshStarted.fulfill()
                if blockRefresh { _ = releaseRefresh.wait(timeout: .now() + 10) }
                return (tokenStatus, tokenBody)
            }
            let header = request.value(forHTTPHeaderField: "Authorization") ?? ""
            lock.withLock { calls += 1; headers.append(header) }
            if edgeHTML { return (401, "<html>Sign in</html>") }
            if alwaysReject || (rejectOriginal && header == "Bearer original") { return (401, "{}") }
            return (200, "[]")
        }
    }
    nonisolated(unsafe) static var recorder: Recorder?
    private let lock = NSLock()
    private var stopped = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let recorder = Self.recorder!
        DispatchQueue.global().async { [self] in
            let (status, body) = recorder.reply(to: request)
            lock.withLock {
                guard !stopped else { return }
                let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                               headerFields: ["Content-Type": body.hasPrefix("<") ? "text/html" : "application/json"])!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data(body.utf8))
                client?.urlProtocolDidFinishLoading(self)
            }
        }
    }
    override func stopLoading() { lock.withLock { stopped = true } }
}

private final class AuthHTTPFixture: @unchecked Sendable {
    let ready = XCTestExpectation(description: "Auth HTTP fixture listening")
    private let listener: NWListener
    private let lock = NSLock()
    private var tokens = 0
    private var roms = 0
    private var rejected = 0
    var tokenCalls: Int { lock.withLock { tokens } }
    var romCalls: Int { lock.withLock { roms } }
    var rejectCalls: Int { lock.withLock { rejected } }
    var url: URL? { listener.port.flatMap { URL(string: "http://127.0.0.1:\($0.rawValue)") } }
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start() {
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.ready.fulfill() }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: .global())
            receive(connection, prefix: Data())
        }
        listener.start(queue: .global())
    }
    private func receive(_ connection: NWConnection, prefix: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [self] data, _, complete, error in
            let collected = prefix + (data ?? Data())
            let text = String(decoding: collected, as: UTF8.self)
            guard let boundary = text.range(of: "\r\n\r\n") else {
                if complete || error != nil { connection.cancel() } else { receive(connection, prefix: collected) }
                return
            }
            let headers = String(text[..<boundary.lowerBound])
            let length = headers.components(separatedBy: "\r\n")
                .first { $0.lowercased().hasPrefix("content-length:") }
                .flatMap { Int($0.split(separator: ":", maxSplits: 1)[1].trimmingCharacters(in: .whitespaces)) } ?? 0
            guard text[boundary.upperBound...].utf8.count >= length else {
                if complete || error != nil { connection.cancel() } else { receive(connection, prefix: collected) }
                return
            }
            let path = headers.components(separatedBy: " ").dropFirst().first ?? ""
            let status: Int
            let body: String
            if path == "/api/token" {
                lock.withLock { tokens += 1 }
                status = 200
                body = #"{"access_token":"rotated","refresh_token":"refresh-rotated","token_type":"Bearer","expires_in":3600}"#
            } else {
                lock.withLock {
                    if path == "/rom" { roms += 1 }
                    if path == "/reject" { rejected += 1 }
                }
                let authorized = headers.lowercased().contains("authorization: bearer rotated")
                status = authorized && path != "/reject" ? 200 : 401
                body = status == 401 ? "unauthorized" : (path == "/rom" ? "fixture-rom" : "[]")
            }
            let response = "HTTP/1.1 \(status) Response\r\nContent-Type: application/octet-stream\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }
    func stop() { listener.cancel() }
}
