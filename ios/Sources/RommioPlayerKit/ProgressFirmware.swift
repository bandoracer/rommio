import Foundation
import CryptoKit
import RommioFoundation

/// Immutable firmware inputs copied into the session; their hashes belong to
/// snapshot compatibility, independently of the portable game-save format.
public enum ProgressFirmware {
    public static let knownMD5: [String: String] = [
        "scph5500.bin": "8dd7d5296a650fac7319bce665a6a53c",
        "scph5501.bin": "490f666e1afb15b7362b406ed1cea246",
        "scph5502.bin": "32736f17079d0b2b7024407c39bd3050",
        "bios_CD_E.bin": "e66fa1dc5820d254611fdcdba0662372",
        "bios_CD_U.bin": "854b9150240a198070150e4566ae1290",
        "bios_CD_J.bin": "278a9397d192149e84e820ac621a8edd",
    ]
    public static func paths(runtimeID: String) -> [String] {
        switch runtimeID {
        case "pcsx_rearmed": return ["scph5500.bin", "scph5501.bin", "scph5502.bin"]
        case "genesis_plus_gx": return ["bios_CD_E.bin", "bios_CD_U.bin", "bios_CD_J.bin"]
        default: return []
        }
    }
    public static func required(runtime: IOSRuntimeProfile, platform: String) -> [String] {
        runtime.requiredBIOSFiles + (runtime.runtimeID == "genesis_plus_gx" && platform == "segacd" ? paths(runtimeID: runtime.runtimeID) : [])
    }
    public static func valid(_ url: URL, name: String) -> Bool {
        guard let expected = knownMD5[name],
              let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]),
              values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, (1...16*1024*1024).contains(size),
              let data = try? Data(contentsOf: url) else { return false }
        return Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined() == expected
    }
    public static func source(name: String, roots: [URL]) -> URL? {
        roots.map { $0.appending(path: name) }.first { valid($0, name: name) }
    }
    /// Recognize by bytes, so Files-provider renaming does not alter BIOS identity.
    public static func importBIOS(_ url: URL, destination: URL) throws -> String {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, (1...16*1024*1024).contains(size) else {
            throw ProgressStoreError.invalid("Choose a supported PlayStation or Sega CD BIOS file.")
        }
        let bytes = try Data(contentsOf: url)
        let digest = Insecure.MD5.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard let name = knownMD5.first(where: { $0.value == digest })?.key else {
            throw ProgressStoreError.invalid("This file does not match a supported PlayStation or Sega CD BIOS.")
        }
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let target = destination.appending(path: name)
        if FileManager.default.fileExists(atPath: target.path) && !valid(target, name: name) {
            let backup = destination.appending(path: "replaced/\(UUID().uuidString)/\(name)")
            try FileManager.default.createDirectory(at: backup.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: target, to: backup)
        }
        try ProgressDurability.write(bytes, to: target)
        guard valid(target, name: name) else { throw ProgressStoreError.invalid("The imported BIOS could not be verified.") }
        return name
    }
    private struct Asset: Decodable { let sha256: String; let sizeBytes: Int }
    private static func prepareAssets(_ root: URL, destination: URL) throws -> [String: String] {
        let records = try JSONDecoder().decode([String: Asset].self, from: Data(contentsOf: root.appending(path: "system-manifest.json")))
        guard (1...128).contains(records.count) else { throw ProgressStoreError.invalid("Invalid PSP asset inventory") }
        var hashes: [String: String] = [:]
        for (path, asset) in records {
            try ProgressProtocol.validatePath(path)
            guard path.hasPrefix("PPSSPP/"), (1...16*1024*1024).contains(asset.sizeBytes) else { throw ProgressStoreError.invalid("Invalid PSP asset") }
            let bytes = try Data(contentsOf: root.appending(path: "system/\(path)"))
            guard bytes.count == asset.sizeBytes, ProgressProtocol.sha256(bytes) == asset.sha256 else { throw ProgressStoreError.invalid("The bundled PSP assets changed") }
            let target = destination.appending(path: path)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try ProgressDurability.write(bytes, to: target); hashes[path] = asset.sha256
        }
        return hashes
    }
    public static func prepare(runtime: IOSRuntimeProfile, platform: String, roots: [URL], destination: URL, assets: URL? = nil) throws -> [String: String] {
        if runtime.runtimeID == "ppsspp" { guard let assets else { throw ProgressStoreError.invalid("The PSP system assets are missing") }; return try prepareAssets(assets, destination: destination) }
        let required = required(runtime: runtime, platform: platform)
        let missing = required.filter { source(name: $0, roots: roots) == nil }
        guard missing.isEmpty else { throw ProgressStoreError.invalid("Import valid BIOS files before playing: \(missing.joined(separator: ", ")).") }
        var hashes: [String: String] = [:]
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        for name in paths(runtimeID: runtime.runtimeID) {
            guard let file = source(name: name, roots: roots) else { continue }
            let bytes = try Data(contentsOf: file)
            let target = destination.appending(path: name)
            try ProgressDurability.write(bytes, to: target)
            guard valid(target, name: name) else { throw ProgressStoreError.invalid("Firmware changed while preparing the session") }
            hashes[name] = ProgressProtocol.sha256(bytes)
        }
        return hashes
    }
}
