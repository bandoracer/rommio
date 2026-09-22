import Foundation
import RommioFoundation

public enum PinnedAppleCore {
    public static let sourceManifestSHA256 = "23740d3d136309aae6e08073104dbdd05a0d2614096326261af16b925cec9eb2"
    public static let cartridgeSourceManifestSHA256 = "77921c3f7efe1a6bfed93d75c8022747618237ce497fff70d5bab2232bb17433"
    public static let cartridgeRuntimes: Set<String> = ["gambatte", "mgba", "snes9x", "melonds_ds", "genesis_plus_gx", "pcsx_rearmed", "mupen64plus_next_gles3", "ppsspp"]
    public static func verify(core: URL, manifest: URL, runtimeID: String = "fceumm") throws -> String {
        let record = try JSONSerialization.jsonObject(with: Data(contentsOf: manifest)) as? [String: Any]
        let cartridge = cartridgeRuntimes.contains(runtimeID)
        guard record?["schemaVersion"] as? Int == 1, record?["runtimeId"] as? String == runtimeID,
              record?["sourceManifestSha256"] as? String == (cartridge ? cartridgeSourceManifestSHA256 : sourceManifestSHA256),
              record?["persistenceExtension"] as? String == (runtimeID == "ppsspp" ? "rommio-files-v1" : cartridge ? "rommio-cart-v1" : "rommio-fceumm-segments-v2"),
              record?["architecture"] as? String == CartridgePersistence.architecture,
              let expected = record?["sha256"] as? String, try ProgressFileHash.sha256(core) == expected else {
            throw ProgressStoreError.invalid("The Apple core does not match its pinned persistence build.")
        }
        return expected
    }
}
