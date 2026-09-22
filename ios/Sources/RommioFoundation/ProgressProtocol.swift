import CryptoKit
import Foundation
import CoreFoundation
import RommioContract

public enum ProgressProtocolError: Error, Equatable, Sendable {
    case unsupportedSchema(Int64)
    case invalid(String)
}

public enum ProgressProtocol {
    public static let maxPayloadBytes = 1_048_576
    private static func check(_ value: Bool, _ message: String = "Invalid progress record") throws {
        if !value { throw ProgressProtocolError.invalid(message) }
    }
    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: "^(?:\(pattern))\\z", options: .regularExpression) != nil
    }
    private static func uuid(_ value: String) -> Bool { matches(value, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") }
    private static func digest(_ value: String) -> Bool { matches(value, "[0-9a-f]{64}") }
    private static func identifier(_ value: String) -> Bool { matches(value, "[A-Za-z0-9._-]{1,128}") }
    private static func safeInteger(_ value: Int64) -> Bool { (-9_007_199_254_740_991...9_007_199_254_740_991).contains(value) }

    public static func encode(_ operation: ProgressOperation) throws -> Data {
        try validate(operation)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(operation)
        try check(data.count <= maxPayloadBytes)
        return data
    }

    public static func decode(_ data: Data) throws -> ProgressOperation {
        try check(data.count <= maxPayloadBytes, "Progress envelope exceeds size limit")
        try check(String(data: data, encoding: .utf8) != nil, "Invalid UTF-8")
        let tree = try JSONSerialization.jsonObject(with: data)
        try checkJSONTokens(data)
        guard let object = tree as? [String: Any], let version = object["schemaVersion"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID() else { throw ProgressProtocolError.invalid("Missing schema version") }
        if ![2, 3].contains(version.int64Value) { throw ProgressProtocolError.unsupportedSchema(version.int64Value) }
        if version.int64Value == 2 {
            try check(!(object["checkpoints"] as? [[String: Any]] ?? []).contains { $0.keys.contains("gameSaveOperationId") }, "Pairing edges require schema 3")
        }
        try validateShape(tree, type: "ProgressOperation")
        let operation = try JSONDecoder().decode(ProgressOperation.self, from: data)
        try validate(operation)
        return operation
    }

    // Foundation checks JSON syntax; this pass rejects duplicate keys and non-decimal integer tokens.
    private static func checkJSONTokens(_ data: Data) throws {
        let bytes = [UInt8](data)
        var containers: [Set<String>?] = []
        var i = 0
        while i < bytes.count {
            switch bytes[i] {
            case 123: containers.append([]); try check(containers.count <= 32); i += 1
            case 91: containers.append(nil); try check(containers.count <= 32); i += 1
            case 125, 93: _ = containers.popLast(); i += 1
            case 34:
                let start = i; i += 1
                while i < bytes.count {
                    if bytes[i] == 92 { i += 2; continue }
                    if bytes[i] == 34 { break }
                    i += 1
                }
                i += 1
                var next = i
                while next < bytes.count && [9, 10, 13, 32].contains(bytes[next]) { next += 1 }
                if next < bytes.count && bytes[next] == 58 {
                    let key = try JSONDecoder().decode(String.self, from: Data(bytes[start..<i]))
                    guard !containers.isEmpty, var keys = containers.last! else { throw ProgressProtocolError.invalid("Invalid JSON key") }
                    try check(keys.insert(key).inserted, "Duplicate JSON key")
                    containers[containers.count - 1] = keys
                }
            case 45, 48...57:
                let start = i
                while i < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[i]) { i += 1 }
                let raw = String(decoding: bytes[start..<i], as: UTF8.self)
                try check(matches(raw, "-?(0|[1-9][0-9]*)") && Int64(raw) != nil, "Progress numbers must be decimal integers")
            default: i += 1
            }
        }
    }

    private static func validateShape(_ raw: Any, type: String) throws {
        if type.hasSuffix("?") {
            if raw is NSNull { return }
            try validateShape(raw, type: String(type.dropLast())); return
        }
        if type.hasPrefix("List<") {
            guard let array = raw as? [Any] else { throw ProgressProtocolError.invalid("Expected array") }
            for value in array { try validateShape(value, type: String(type.dropFirst(5).dropLast())) }
        } else if type == "Map<String, String>" {
            guard let map = raw as? [String: Any] else { throw ProgressProtocolError.invalid("Expected map") }
            for value in map.values { try check(value is String) }
        } else if type == "String" { try check(raw is String) }
        else if type == "Boolean" {
            guard let number = raw as? NSNumber else { throw ProgressProtocolError.invalid("Expected boolean") }
            try check(CFGetTypeID(number) == CFBooleanGetTypeID())
        } else if type == "Int" || type == "Long" {
            guard let number = raw as? NSNumber else { throw ProgressProtocolError.invalid("Expected integer") }
            try check(CFGetTypeID(number) != CFBooleanGetTypeID())
            if type == "Int" { try check(number.int64Value >= Int32.min && number.int64Value <= Int32.max) }
        } else {
            guard let shape = shapes[type], let object = raw as? [String: Any] else { throw ProgressProtocolError.invalid("Expected object") }
            try check(Set(object.keys).isSubset(of: Set(shape.keys)), "Unknown progress field")
            for (key, fieldType) in shape {
                if let value = object[key] { try validateShape(value, type: fieldType) }
                else { try check(fieldType.hasSuffix("?"), "Missing progress field") }
            }
        }
    }

    public static func validateIdentity(_ identity: ProgressIdentity) throws {
        try check(uuid(identity.namespaceId) && uuid(identity.contentId))
        try validateBinding(identity.binding)
    }

    public static func validateBinding(_ binding: ProgressBinding) throws {
        guard var url = URLComponents(string: binding.serverKey), let scheme = url.scheme,
              ["http", "https"].contains(scheme), let host = url.host, !host.isEmpty else { throw ProgressProtocolError.invalid("Invalid server binding") }
        try check(url.user == nil && url.password == nil && url.query == nil && url.fragment == nil)
        url.host = host.lowercased()
        if (scheme == "http" && url.port == 80) || (scheme == "https" && url.port == 443) { url.port = nil }
        for part in url.percentEncodedPath.split(separator: "/") {
            let decoded = String(part).removingPercentEncoding ?? ""
            try check(decoded != "." && decoded != "..")
        }
        var normalized = url.string ?? ""
        if normalized.hasSuffix("/") { normalized.removeLast() }
        try check(normalized == binding.serverKey, "Server binding must use a normalized origin and base path")
        try check(binding.serverKey.utf8.allSatisfy { (33...126).contains($0) })
        try check(binding.accountId.utf8.elementsEqual(binding.accountId.precomposedStringWithCanonicalMapping.utf8))
        try check(!binding.accountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && binding.accountId.utf16.count <= 128 &&
                  !binding.accountId.unicodeScalars.contains { $0.value < 32 || (127...159).contains($0.value) })
        try check(binding.romId >= 0 && safeInteger(binding.romId) && (1...64).contains(binding.files.count))
        try check(Set(binding.files.map(\.fileId)).count == binding.files.count)
        for (index, file) in binding.files.enumerated() {
            try check(file.fileId >= 0 && safeInteger(file.fileId) && file.discIndex == index && digest(file.contentSha256))
            if let container = file.containerSha256 { try check(digest(container)) }
            if let member = file.memberPath { try validatePath(member); try check(file.containerSha256 != nil) }
        }
    }

    public static func validate(_ operation: ProgressOperation) throws {
        if ![2, 3].contains(operation.schemaVersion) { throw ProgressProtocolError.unsupportedSchema(Int64(operation.schemaVersion)) }
        try check(uuid(operation.operationId) && uuid(operation.deviceId) && uuid(operation.sessionId))
        try validateBinding(operation.binding)
        try check(safeInteger(operation.createdAtEpochMs) && (1...64).contains(operation.changes.count) && operation.checkpoints.count <= 64)
        try check(Set(operation.changes.map(\.reference)).count == operation.changes.count)
        try check(Set(operation.checkpoints.map(\.checkpointId)).count == operation.checkpoints.count)
        let modern = operation.schemaVersion == 3
        let gameChanges = operation.changes.filter { $0.reference.hasPrefix("game/") }
        if modern { try check(gameChanges.isEmpty || (gameChanges.count == 1 && operation.changes.count == 1), "Game and state operations must be independent") }
        for checkpoint in operation.checkpoints {
            if modern && checkpoint.stateCompatibility != nil {
                try check(checkpoint.gameSaveOperationId.map(uuid) == true && checkpoint.gameSaveOperationId != operation.operationId)
            } else { try check(checkpoint.gameSaveOperationId == nil) }
            try check(checkpoint.schemaVersion == operation.schemaVersion && uuid(checkpoint.checkpointId) && checkpoint.binding == operation.binding)
            try check(uuid(checkpoint.deviceId) && uuid(checkpoint.sessionId) && safeInteger(checkpoint.createdAtEpochMs))
            try check(["periodic", "manual", "pause", "background", "exit", "safety", "restore", "import"].contains(checkpoint.captureReason))
            let save = checkpoint.saveCompatibility
            try check([save.system, save.format, save.layoutVersion].allSatisfy(identifier))
            if let core = save.coreId { try check(identifier(core)) }
            if let state = checkpoint.stateCompatibility {
                try check([state.coreId, state.stateFormat, state.platform, state.architecture].allSatisfy(identifier))
                try check(digest(state.coreSha256) && state.options.count <= 128 && state.systemFiles.count <= 128)
                for (key, value) in state.options { try check(identifier(key) && value.utf8.count <= 4096 && value.utf8.elementsEqual(value.precomposedStringWithCanonicalMapping.utf8)) }
                for (path, hash) in state.systemFiles { try validatePath(path); try check(digest(hash)) }
            }
            try check(checkpoint.artifacts.count <= 1024)
            let paths = checkpoint.artifacts.map { $0.relativePath.lowercased() }
            try check(Set(paths).count == paths.count)
            try check(!paths.contains { path in paths.contains { $0.hasPrefix(path + "/") } }, "Artifact path is also a directory")
            for artifact in checkpoint.artifacts {
                try validatePath(artifact.relativePath)
                try check(digest(artifact.sha256) && (0...8_589_934_592).contains(artifact.byteLength) && identifier(artifact.format))
                try check(["sram", "rtc", "memoryCard", "saveFile", "state"].contains(artifact.role))
                try check(artifact.byteLength > 0 || (artifact.role != "state" && save.allowsEmpty))
            }
            let states = checkpoint.artifacts.filter { $0.role == "state" }.count
            try check(states <= 1 && (states == 1) == (checkpoint.stateCompatibility != nil))
            try check(checkpoint.saveDataComplete || checkpoint.artifacts.allSatisfy { $0.role == "state" })
            try check(!checkpoint.saveDataComplete || save.allowsEmpty || checkpoint.artifacts.contains { $0.role != "state" })
        }
        for change in operation.changes {
            let game = modern ? "game/current" : "save/current"
            let resume = modern ? "state/resume/" : "resume/"
            let slot = modern ? "state/slot/" : "slot/"
            let archive = modern && change.reference.hasPrefix("game/archive/") && uuid(String(change.reference.dropFirst(13)))
            try check(change.reference == game || archive ||
                (change.reference.hasPrefix(resume) && digest(String(change.reference.dropFirst(resume.count)))) ||
                (change.reference.hasPrefix(slot) && uuid(String(change.reference.dropFirst(slot.count)))))
            try check(change.parents.count <= 256 && Set(change.parents).count == change.parents.count && change.parents.allSatisfy { uuid($0) && $0 != operation.operationId })
            try check(change.tombstone == (change.checkpointId == nil))
            if !change.tombstone {
                guard let checkpoint = operation.checkpoints.first(where: { $0.checkpointId == change.checkpointId }) else { throw ProgressProtocolError.invalid("Reference target manifest is missing") }
                if change.reference == game || archive { try check(checkpoint.saveDataComplete && (!modern || checkpoint.stateCompatibility == nil)) }
                if modern && change.reference.hasPrefix(slot) { try check(checkpoint.saveDataComplete && checkpoint.stateCompatibility != nil) }
                if change.reference.hasPrefix(resume) {
                    try check(change.reference == "\(resume)\(try stateCompatibilityKey(checkpoint))")
                    try check(checkpoint.saveDataComplete && (modern || operation.changes.contains { $0.reference == "save/current" && !$0.tombstone && $0.checkpointId == checkpoint.checkpointId }))
                }
            }
        }
        try check(operation.checkpoints.allSatisfy { checkpoint in operation.changes.contains { $0.checkpointId == checkpoint.checkpointId } })
    }

    public static func validatePath(_ path: String) throws {
        try check(!path.isEmpty && path.utf8.count <= 1024 && path.utf8.elementsEqual(path.precomposedStringWithCanonicalMapping.utf8))
        try check(!path.contains("\\") && !path.contains(":") && !path.unicodeScalars.contains { $0.value < 32 || (127...159).contains($0.value) })
        try check(path.components(separatedBy: "/").allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }, "Unsafe artifact path")
    }

    public static func bindingKey(_ binding: ProgressBinding) -> String {
        var fields = ["rommio-binding-v2", binding.serverKey, binding.accountId, String(binding.romId), String(binding.files.count)]
        for file in binding.files { fields += [String(file.fileId), String(file.discIndex), file.contentSha256, file.containerSha256 ?? "", file.memberPath ?? ""] }
        return fieldHash(fields)
    }

    public static func stateCompatibilityKey(_ checkpoint: ProgressCheckpoint) throws -> String {
        guard let state = checkpoint.stateCompatibility else { throw ProgressProtocolError.invalid("No state compatibility descriptor") }
        let save = checkpoint.saveCompatibility
        var fields = ["rommio-state-v2", bindingKey(checkpoint.binding), save.system, save.format, save.layoutVersion, save.coreId ?? "", String(save.allowsEmpty),
                      state.coreId, state.coreSha256, state.stateFormat, state.platform, state.architecture]
        for map in [state.options, state.systemFiles] {
            fields.append(String(map.count))
            for key in map.keys.sorted(by: { $0.utf8.lexicographicallyPrecedes($1.utf8) }) { fields += [key, map[key]!] }
        }
        return fieldHash(fields)
    }
    private static func fieldHash(_ fields: [String]) -> String { sha256(Data(fields.map { "\($0.utf8.count):\($0)" }.joined().utf8)) }
    public static func sha256(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }

    public static func dependencies(_ operation: ProgressOperation) -> [String] {
        Array(Set(operation.changes.flatMap(\.parents) + operation.checkpoints.compactMap(\.gameSaveOperationId)))
    }
    public static func gameArtifacts(_ checkpoint: ProgressCheckpoint) -> [ProgressArtifact] {
        checkpoint.artifacts.filter { $0.role != "state" }.sorted { $0.relativePath < $1.relativePath }
    }

    public static func validateParents(_ operation: ProgressOperation, known: [String: ProgressOperation]) throws {
        for state in operation.checkpoints where state.gameSaveOperationId != nil {
            guard let game = known[state.gameSaveOperationId!], game.schemaVersion == 3, game.binding == operation.binding,
                let change = game.changes.first(where: { $0.reference.hasPrefix("game/") && !$0.tombstone }),
                let saved = game.checkpoints.first(where: { $0.checkpointId == change.checkpointId }) else { throw ProgressProtocolError.invalid("Missing paired game operation") }
            try check(state.saveCompatibility == saved.saveCompatibility && gameArtifacts(state) == gameArtifacts(saved), "State/game-save pairing differs")
        }
        for change in operation.changes {
            for id in change.parents {
                guard let parent = known[id] else { throw ProgressProtocolError.invalid("Missing parent \(id)") }
                try check(parent.schemaVersion == operation.schemaVersion && parent.binding == operation.binding && parent.changes.contains { $0.reference == change.reference }, "Parent belongs to another reference or content")
            }
        }
        var all = known; all[operation.operationId] = operation
        var visiting = Set<String>(); var visited = Set<String>()
        var stack: [(String, Bool)] = [(operation.operationId, false)]
        while let (id, exit) = stack.popLast() {
            if exit { visiting.remove(id); visited.insert(id); continue }
            if visited.contains(id) { continue }
            try check(visiting.insert(id).inserted, "Cyclic progress ancestry")
            stack.append((id, true))
            for parent in Set(all[id].map(dependencies) ?? []) { stack.append((parent, false)) }
        }
    }

    private static let shapes: [String: [String: String]] = [
        "ProgressContentFile": ["fileId": "Long", "discIndex": "Int", "contentSha256": "String", "containerSha256": "String?", "memberPath": "String?"],
        "ProgressBinding": ["serverKey": "String", "accountId": "String", "romId": "Long", "files": "List<ProgressContentFile>"],
        "ProgressSaveCompatibility": ["system": "String", "format": "String", "layoutVersion": "String", "coreId": "String?", "allowsEmpty": "Boolean"],
        "ProgressStateCompatibility": ["coreId": "String", "coreSha256": "String", "stateFormat": "String", "platform": "String", "architecture": "String", "options": "Map<String, String>", "systemFiles": "Map<String, String>"],
        "ProgressArtifact": ["sha256": "String", "byteLength": "Long", "role": "String", "relativePath": "String", "format": "String"],
        "ProgressCheckpoint": ["schemaVersion": "Int", "checkpointId": "String", "binding": "ProgressBinding", "saveCompatibility": "ProgressSaveCompatibility", "stateCompatibility": "ProgressStateCompatibility?", "saveDataComplete": "Boolean", "artifacts": "List<ProgressArtifact>", "deviceId": "String", "sessionId": "String", "captureReason": "String", "createdAtEpochMs": "Long", "gameSaveOperationId": "String?"],
        "ProgressRefChange": ["reference": "String", "parents": "List<String>", "checkpointId": "String?", "tombstone": "Boolean"],
        "ProgressOperation": ["schemaVersion": "Int", "operationId": "String", "binding": "ProgressBinding", "changes": "List<ProgressRefChange>", "checkpoints": "List<ProgressCheckpoint>", "deviceId": "String", "sessionId": "String", "createdAtEpochMs": "Long"],
        "ProgressIdentity": ["namespaceId": "String", "contentId": "String", "binding": "ProgressBinding"],
    ]
}
