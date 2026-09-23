#if DEBUG && canImport(UIKit)
import Foundation
import UIKit
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Opt-in fixture runner. It uses the real app model, downloader, player and sync service.
/// Configurations are accepted only from an explicitly disposable application root.
@MainActor
extension RommioAppModel {
    func runProgressProbe(services: RommioServices) async {
        let localPhase = Bundle.main.bundleIdentifier?.hasSuffix(".paritytest") == true ? ProcessInfo.processInfo.environment["ROMMIO_PROGRESS_LOCAL_PHASE"] : nil
        let root: URL
        let config: [String: Any]
        do {
            if let localPhase {
                root = services.libraryStore.rootDirectory.deletingLastPathComponent()
                config = ["baseUrl": "http://127.0.0.1:18765", "phase": localPhase, "romId": 1, "fileId": 1]
            } else {
                guard let path = ProcessInfo.processInfo.environment["ROMMIO_PROGRESS_PROBE_CONFIG"],
                      let rootPath = ProcessInfo.processInfo.environment["ROMMIO_APP_ROOT"],
                      rootPath.hasSuffix("rommio-progress-fixture"), path.hasPrefix(rootPath + "/") else { return }
                root = URL(fileURLWithPath: rootPath)
                config = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
            }
        } catch { errorMessage = error.localizedDescription; return }
        do {
            guard let baseURL = config["baseUrl"] as? String,
                  (baseURL == "http://127.0.0.1:18765" || (config["allowLiveServer"] as? Bool == true && (config["username"] as? String)?.hasPrefix("codex-") == true)),
                  let phase = config["phase"] as? String,
                  let romID = config["romId"] as? Int, let fileID = config["fileId"] as? Int else { throw ProgressStoreError.invalid("Invalid disposable probe configuration") }
            if phase == "expansion-native" { try runExpansionNativeProbe(root: root); return }
            if phase.hasPrefix("backup-") { try await runBackupProbe(phase: phase, root: root, services: services); return }
            if phase == "seed-local" { try await prepareLocalProgressFixture(services: services) }
            if phase == "controls-local" { try await prepareControlsFixture(services: services, root: root, config: config) }
            if phase == "seed" || phase == "play" || phase == "boot" || phase == "receive" || phase == "reconnect" || phase == "peer" || phase == "cold-peer" || phase == "resolve" {
                let profile = try await services.authController.configureProfile(baseURL: baseURL, edgeAuthMode: .none, originAuthMode: .rommBearerPassword)
                _ = try await services.authController.testServerAccess(profileID: profile.id)
                try await services.authController.loginWithDirectCredentials(profileID: profile.id, credentials: DirectLoginCredentials(username: config["username"] as! String, password: config["password"] as! String))
            }
            await reloadProfiles()
            let rom: RomDTO
            if let romFile = config["romMetadata"] as? String {
                let metadata = root.appending(path: romFile)
                guard metadata.deletingLastPathComponent() == root else { throw ProgressStoreError.invalid("Invalid fixture metadata path") }
                rom = try JSONDecoder().decode(RomDTO.self, from: Data(contentsOf: metadata))
                guard rom.id == romID, rom.files.first?.id == fileID else { throw ProgressStoreError.invalid("Fixture content differs") }
            } else {
                rom = RomDTO(id: romID, name: "Original NES counter", platformSlug: "nes", fsName: "counter.nes", files: [RomFileDTO(id: fileID, romID: romID, fileName: "counter.nes", fileExtension: "nes", fileSizeBytes: 16400)])
            }
            if phase == "seed" || phase == "play" || phase == "boot" || phase == "peer" || phase == "cold-peer" {
                try await services.downloadQueue.enqueue(rom: rom, file: rom.files[0])
                for _ in 0..<(phase == "boot" ? 18000 : 1800) {
                    if try await services.downloadQueue.verifiedLocalURL(profileID: activeProfile!.id, romID: romID, fileID: fileID) != nil { break }
                    try await Task.sleep(for: .milliseconds(100))
                }
            }
            // Make the actual SwiftUI player visible; its display link advances the native core.
            route = .app
            await launchInstalledGame(rom: rom, file: rom.files[0])
            guard let feature = activePlayer, let owner = feature.progressOwner else { throw ProgressStoreError.invalid(errorMessage ?? "Production progress owner was not activated") }
            if phase == "boot" {
                feature.preferColdBoot = true
                if let message = feature.cloudMessage { throw ProgressStoreError.invalid(message) }
                await feature.startIfNeeded()
                guard feature.hasStarted else { throw ProgressStoreError.invalid(feature.errorMessage ?? "3DS title failed to start") }
                try writeProbe(["status": "passed", "phase": phase, "platform": rom.platformSlug], root: root)
                return
            }
            if phase == "resolve" {
                guard let selected = config["selectedGameHead"] as? String,
                      let expected = config["expectedGameHeads"] as? [String] else { throw ProgressStoreError.invalid("Explicit QA branch choice required") }
                try await owner.resolve(reference: "game/current", selected: selected, expected: expected)
                let result = try await services.progress.synchronize(owner.identity)
                guard result.complete else { throw ProgressStoreError.invalid(result.message) }
                try writeProbe(["status": "passed", "phase": phase], root: root)
                return
            }
            if phase == "cold-peer" {
                guard rom.platformSlug == "3ds" else { throw ProgressStoreError.invalid("3DS cold peer requires a 3DS fixture") }
                feature.preferColdBoot = true
                if let message = feature.cloudMessage { throw ProgressStoreError.invalid(message) }
                await feature.startIfNeeded()
                guard feature.hasStarted, feature.noticeMessage == "Loaded game-save data." else {
                    throw ProgressStoreError.invalid(feature.errorMessage ?? "3DS cold boot did not load its game save")
                }
                let expected = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: root.appending(path: "expected.json")))
                let save = services.progress.root.appending(path: "sessions/\(owner.sessionID)/saves/Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
                guard try ProgressProtocol.sha256(Data(contentsOf: save)) == expected["gameSave"] else {
                    throw ProgressStoreError.invalid("Remote 3DS game save differs after independent cold boot")
                }
                try writeProbe(["status": "passed", "phase": phase, "gameSave": expected["gameSave"] ?? ""], root: root)
                return
            }
            if phase == "seed" || phase == "play" || phase == "receive" { feature.preferColdBoot = true }
            if phase != "verify" && phase != "verify-local" && !phase.hasSuffix("-local") {
                for _ in 0..<5 where feature.cloudMessage != nil {
                    try await Task.sleep(for: .seconds(1))
                    await feature.retryCloudCheck()
                }
            }
            if phase == "verify" || phase == "verify-local" || phase == "peer" {
                // Explicit unchecked-cloud continuation is the same choice exposed in the UI.
                if phase == "peer", let message = feature.cloudMessage { throw ProgressStoreError.invalid(message) }
                if phase != "peer" { feature.cloudMessage = nil }
                await feature.startIfNeeded()
                guard feature.hasStarted, feature.noticeMessage == "Resumed snapshot and its matching game-save data." else {
                    throw ProgressStoreError.invalid(feature.errorMessage ?? "The matching resume snapshot was not loaded")
                }
                try await owner.pause()
                let capture = try await feature.probeCapture()
                let expected = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appending(path: "expected.json"))) as! [String: String]
                let store = try await services.progress.store()
                let refs = try await store.references(owner.identity)
                let snapshots = refs.filter { $0.key.hasPrefix("state/resume/") }.values.flatMap { $0 }.compactMap(\.checkpoint)
                let threeDSSave = services.progress.root.appending(path: "sessions/\(owner.sessionID)/saves/Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
                let gameMatches = rom.platformSlug == "3ds"
                    ? (try ProgressProtocol.sha256(Data(contentsOf: threeDSSave)) == expected["gameSave"])
                    : ProgressProtocol.sha256(capture.memory[0] ?? Data()) == expected["game"]
                guard gameMatches,
                      expected["clock"] == nil || ProgressProtocol.sha256(capture.memory[1] ?? Data()) == expected["clock"],
                      snapshots.contains(where: { $0.stateCompatibility == owner.qualification.state && $0.artifacts.contains(where: { $0.role == "state" && $0.sha256 == expected["state"] }) }),
                      owner.qualification.state.coreId != "fceumm" || ProgressProtocol.sha256(capture.state!) == expected["state"] else { throw ProgressStoreError.invalid("Restored game data or snapshot differs from the committed bytes") }
                try writeProbe(["status": "passed", "phase": phase, "pid": String(ProcessInfo.processInfo.processIdentifier)], root: root)
                return
            }
            if localPhase != nil || phase == "controls-local" || phase == "offline-play" { feature.cloudMessage = nil }
            if feature.cloudMessage != nil { throw ProgressStoreError.invalid(feature.cloudMessage!) }
            if phase == "receive" { feature.preferColdBoot = true }
            await feature.startIfNeeded()
            guard feature.hasStarted else { throw ProgressStoreError.invalid(feature.errorMessage ?? "Player did not start") }
            if phase == "controls-local" {
                try writeProbe(["status": "ready", "phase": phase], root: root)
                return
            }
            if phase == "play" {
                // The operator exercises the game's own save UI, then requests a
                // checkpoint through this disposable-root-only command file.
                let command = root.appending(path: "capture-request")
                try writeProbe(["status": "ready", "phase": phase], root: root)
                for _ in 0..<1800 {
                    if FileManager.default.fileExists(atPath: command.path) { break }
                    try await Task.sleep(for: .seconds(1))
                }
                guard FileManager.default.fileExists(atPath: command.path) else {
                    throw ProgressStoreError.invalid("Gameplay capture was not requested")
                }
                try FileManager.default.removeItem(at: command)
            }
            if phase == "receive" {
                try await owner.pause()
                let capture = try await feature.probeCapture()
                let expected = config["expectedGame"] as! String
                guard ProgressProtocol.sha256(capture.memory[0] ?? Data()) == expected,
                      config["expectedClock"] == nil || ProgressProtocol.sha256(capture.memory[1] ?? Data()) == config["expectedClock"] as? String else { throw ProgressStoreError.invalid("Android game-save data was not selected on iOS") }
                try await owner.resume()
            }
            if phase == "seed", rom.platformSlug == "3ds", config["azaharGameSave"] as? Bool == true {
                let save = services.progress.root.appending(path: "sessions/\(owner.sessionID)/saves/Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
                for _ in 0..<50 where !FileManager.default.fileExists(atPath: save.path) {
                    try await Task.sleep(for: .milliseconds(100))
                }
                let initial = try Data(contentsOf: save)
                // The config is created before the game's main menu is ready to receive touch.
                try await Task.sleep(for: .seconds(2))
                func tap(_ x: Double, _ y: Double) async throws {
                    let frameX = (x + 40) / 400
                    let frameY = (y + 240) / 480
                    feature.enqueuePointer(normalizedX: frameX, normalizedY: frameY, pressed: true)
                    try await Task.sleep(for: .milliseconds(150))
                    feature.enqueuePointer(normalizedX: frameX, normalizedY: frameY, pressed: false)
                    try await Task.sleep(for: .milliseconds(150))
                }
                try await tap(270, 210)
                try await tap(50, 22)
                try await tap(45, 210)
                guard try Data(contentsOf: save) != initial else { throw ProgressStoreError.invalid("3DS player touch did not create a game-authored save") }
            }
            if (phase == "seed" || phase == "offline-play"),
               config["syntheticPSPSave"] as? Bool == true {
                guard owner.qualification.save.system == "psp" else { throw ProgressStoreError.invalid("PSP fixture requires PSP") }
                try await owner.pause()
                let saves = services.progress.root.appending(path: "sessions/\(owner.sessionID)/saves/PSP/SAVEDATA/ROMMIOQA")
                try FileManager.default.createDirectory(at: saves.appending(path: "EMPTY"), withIntermediateDirectories: true)
                try Data("Synthetic file-bundle transport fixture: \(phase)".utf8).write(to: saves.appending(path: "DATA.BIN"))
                try Data("Synthetic slot metadata".utf8).write(to: saves.appending(path: "SLOT.TXT"))
                try await owner.resume()
            }
            try await Task.sleep(for: .seconds(1))
            await feature.sceneBecameInactive()
            guard feature.errorMessage == nil else { throw ProgressStoreError.invalid(feature.errorMessage!) }
            let pausedPayloads = try await services.progress.store().payloads(owner.identity).count
            await feature.capturePeriodicProgressIfActive()
            guard try await services.progress.store().payloads(owner.identity).count == pausedPayloads else {
                throw ProgressStoreError.invalid("Periodic capture wrote progress while paused")
            }
            let capture = try await feature.probeCapture()
            let refs = try await services.progress.store().references(owner.identity)
            let snapshot = refs.filter { $0.key.hasPrefix("state/resume/") }.values.flatMap { $0 }.compactMap(\.checkpoint).first { $0.stateCompatibility == owner.qualification.state }
            guard let stateHash = snapshot?.artifacts.first(where: { $0.role == "state" })?.sha256 else { throw ProgressStoreError.invalid("No committed snapshot") }
            var expected = ["game": ProgressProtocol.sha256(capture.memory[0] ?? Data()), "clock": ProgressProtocol.sha256(capture.memory[1] ?? Data()), "state": stateHash]
            if rom.platformSlug == "3ds" {
                let save = services.progress.root.appending(path: "sessions/\(owner.sessionID)/saves/Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
                expected["gameSave"] = try ProgressProtocol.sha256(Data(contentsOf: save))
            }
            try ProgressDurability.write(JSONEncoder().encode(expected), to: root.appending(path: "expected.json"))
            if phase == "offline-play" {
                let pending = try await services.progress.store().outbox(owner.identity).filter { $0.status != "PUBLISHED" }
                guard !pending.isEmpty else { throw ProgressStoreError.invalid("Offline capture did not retain publication work") }
            } else if localPhase == nil {
                var result = try await services.progress.synchronize(owner.identity)
                // A game-first drain can finish while its paired snapshot is
                // still committing. Join subsequent queued work before claiming
                // the entire probe checkpoint has reached the server.
                for _ in 0..<3 where !result.complete && result.errors.isEmpty && result.gameConflicts == 0 && result.stateConflicts == 0 {
                    try await Task.sleep(for: .milliseconds(200))
                    result = try await services.progress.synchronize(owner.identity)
                }
                guard result.complete else { throw ProgressStoreError.invalid(result.message) }
            }
            try writeProbe(expected.merging(["status": "passed", "phase": phase, "pid": String(ProcessInfo.processInfo.processIdentifier)], uniquingKeysWith: { _, b in b }), root: root)
        } catch {
            errorMessage = error.localizedDescription
            try? writeProbe(["status": "failed", "error": error.localizedDescription], root: root)
        }
    }
    private func runBackupProbe(phase: String, root: URL, services: RommioServices) async throws {
        func digest() async throws -> String {
            let store = try await services.progress.store()
            var hashes: [String] = []
            for identity in try await store.identities() {
                hashes += try await store.payloads(identity).map(ProgressProtocol.sha256)
            }
            return ProgressProtocol.sha256(Data(hashes.sorted().joined(separator: "\n").utf8))
        }
        let record = root.appending(path: "backup-expected.json")
        if phase == "backup-seed" {
            try await prepareLocalProgressFixture(services: services)
            await reloadProfiles(); route = .app
            let rom = RomDTO(id: 1, name: "Original NES counter", platformSlug: "nes", fsName: "counter.nes", files: [RomFileDTO(id: 1, romID: 1, fileName: "counter.nes", fileExtension: "nes", fileSizeBytes: 16400)])
            func play() async throws {
                await launchInstalledGame(rom: rom, file: rom.files[0])
                guard let feature = activePlayer, feature.progressOwner != nil else { throw ProgressStoreError.invalid(errorMessage ?? "Native owner unavailable") }
                feature.cloudMessage = nil; await feature.startIfNeeded()
                guard feature.hasStarted else { throw ProgressStoreError.invalid(feature.errorMessage ?? "Player did not start") }
                try await Task.sleep(for: .milliseconds(300))
                await feature.leaveGame()
                guard activePlayer == nil else { throw ProgressStoreError.invalid("Failed to close player") }
            }
            try await play()
            let expected = try await digest()
            let archive = try await services.progress.exportProgress()
            try await play()
            let previous = try await digest()
            guard previous != expected else { throw ProgressStoreError.invalid("Fixture needs distinct pre-import history") }
            let preview = try await services.progress.inspectImport(archive)
            try ProgressDurability.write(JSONEncoder().encode(["expected": expected, "previous": previous, "preview": preview.id]), to: record)
        } else if phase == "backup-import" {
            let expected = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: record))
            try await services.progress.importProgress(expected["preview"]!)
        } else if phase == "backup-verify" {
            try await services.progress.recoverImport()
            let expected = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: record))
            guard try await digest() == expected["expected"] else { throw ProgressStoreError.invalid("Recovered metadata differs from the backup") }
            let store = try await services.progress.store()
            for identity in try await store.identities() {
                for payload in try await store.payloads(identity) {
                    for artifact in try ProgressProtocol.decode(payload).checkpoints.flatMap(\.artifacts) { _ = try await store.artifact(identity, artifact) }
                }
            }
            let previous = try await services.progress.exportPrevious(expected["preview"]!)
            guard FileManager.default.fileExists(atPath: previous.path) else { throw ProgressStoreError.invalid("Original progress was not retained") }
            route = .app; selectedTab = .settings
        }
        try writeProbe(["status": "passed", "phase": phase, "pid": String(ProcessInfo.processInfo.processIdentifier)], root: root)
    }
    private func prepareControlsFixture(services: RommioServices, root: URL, config: [String: Any]) async throws {
        try await prepareLocalProgressFixture(services: services)
        guard let name = config["fixtureFile"] as? String, !name.contains("/"),
              let metadataName = config["romMetadata"] as? String, !metadataName.contains("/") else { throw ProgressStoreError.invalid("Local fixture filename required") }
        let rom = try JSONDecoder().decode(RomDTO.self, from: Data(contentsOf: root.appending(path: metadataName)))
        let source = root.appending(path: name)
        let bytes = try Data(contentsOf: source)
        let file = services.libraryStore.rootDirectory.appending(path: "controls/" + name)
        try ProgressDurability.write(bytes, to: file)
        let profileID = "isolated-physical-progress"
        let account = VerifiedAccount(baseURL: URL(string: "http://127.0.0.1:18765")!, userID: 1)
        var record = DownloadRecord(profileID: profileID, romID: rom.id, fileID: rom.files[0].id, romName: rom.name ?? "Controls fixture", platformSlug: rom.platformSlug, fileName: name, fileSizeBytes: Int64(bytes.count), status: .completed, progressPercent: 100, bytesDownloaded: Int64(bytes.count), totalBytes: Int64(bytes.count), localPath: file.path, enqueuedAtEpochMS: 1, updatedAtEpochMS: 1)
        record.owner = account; record.contentSHA256 = ProgressProtocol.sha256(bytes)
        try services.database.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO download_records (profile_id, rom_id, file_id, payload) VALUES (?, ?, ?, ?)", arguments: [profileID, rom.id, rom.files[0].id, try services.database.encoded(record)])
        }
    }

    private func prepareLocalProgressFixture(services: RommioServices) async throws {
        let base = URL(string: "http://127.0.0.1:18765")!
        let profile = ServerProfile(id: "isolated-physical-progress", label: "Isolated local fixture", baseURL: base, edgeAuthMode: .none, originAuthMode: .none, capabilities: AuthCapabilities(), serverAccess: ServerAccessState(), sessionState: SessionState(), isActive: true, status: .connected, createdAt: "2026-09-18T00:00:00Z", updatedAt: "2026-09-18T00:00:00Z")
        try await services.profileStore.save(profile, makeActive: true)
        let account = VerifiedAccount(baseURL: base, userID: 1)
        try services.secretStore.save(JSONEncoder().encode(account), for: "verified_account_" + ProgressProtocol.sha256(Data(profile.id.utf8)))
        var bytes = Data(repeating: 0, count: 16400)
        bytes.replaceSubrange(0..<7, with: [0x4e,0x45,0x53,0x1a,1,0,2])
        let program: [UInt8] = [0x78,0xee,0,0x60,0xd0,8,0xee,1,0x60,0xd0,3,0xee,2,0x60,0x4c,1,0x80]
        bytes.replaceSubrange(16..<16+program.count, with: program)
        for offset in [0x3ffa,0x3ffc,0x3ffe] { bytes[16+offset] = 0; bytes[17+offset] = 0x80 }
        let file = services.libraryStore.rootDirectory.appending(path: "fixture/counter.nes")
        try ProgressDurability.write(bytes, to: file)
        var record = DownloadRecord(profileID: profile.id, romID: 1, fileID: 1, romName: "Original NES counter", platformSlug: "nes", fileName: "counter.nes", fileSizeBytes: Int64(bytes.count), status: .completed, progressPercent: 100, bytesDownloaded: Int64(bytes.count), totalBytes: Int64(bytes.count), localPath: file.path, enqueuedAtEpochMS: 1, updatedAtEpochMS: 1)
        record.owner = account; record.contentSHA256 = ProgressProtocol.sha256(bytes)
        try services.database.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO download_records (profile_id, rom_id, file_id, payload) VALUES (?, ?, ?, ?)", arguments: [profile.id, 1, 1, try services.database.encoded(record)])
        }
    }
    private func writeProbe(_ result: [String: String], root: URL) throws { try ProgressDurability.write(JSONEncoder().encode(result), to: root.appending(path: "probe-result.json")) }
}
#endif
