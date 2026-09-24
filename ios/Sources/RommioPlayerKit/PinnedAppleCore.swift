import Foundation
import RommioFoundation

public enum PinnedAppleCore {
    public static let sourceManifestSHA256 = "23740d3d136309aae6e08073104dbdd05a0d2614096326261af16b925cec9eb2"
    public static let cartridgeSourceManifestSHA256 = "77921c3f7efe1a6bfed93d75c8022747618237ce497fff70d5bab2232bb17433"
    public static let expandedCartridgeSourceManifestSHA256 = "394d1167a3504050d07fb8c55daa0fc4cb1e732704f9cd9a59193933c06d5c86"
    public static let newCartridgeRuntimes: Set<String> = ["stella", "prosystem", "handy"]
    public static let candidateRuntimes: Set<String> = ["azahar"]
    public static let cartridgeRuntimes: Set<String> = Set(["gambatte", "mgba", "snes9x", "melonds_ds", "genesis_plus_gx", "pcsx_rearmed", "mupen64plus_next_gles3", "ppsspp"]).union(newCartridgeRuntimes)
    public static func verify(core: URL, manifest: URL, runtimeID: String = "fceumm") throws -> String {
        let record = try JSONSerialization.jsonObject(with: Data(contentsOf: manifest)) as? [String: Any]
        if runtimeID == "azahar" {
            guard record?["schemaVersion"] as? Int == 1, record?["runtimeId"] as? String == "azahar",
                  record?["status"] as? String == "gpu-host-integration-probe",
                  record?["graphicsApi"] as? String == "Vulkan",
                  record?["gpuPatchSha256"] as? String == "5260630932f418c1b9de92b6a953ca1024ab6c54cc6fcc36d37168a40e52d396",
                  record?["sourceRevision"] as? String == "9e6f523a57fac9564ac0bf8286db3c3702d301ec",
                  record?["patchSha256"] as? String == "b595c864adc26c2a93edaee212f6d70105d4d0cf02cd8ea562b90dee98956468",
                  record?["extensionSha256"] as? String == "68ab666e51800bfb8eaad397426276daef4d911afd30c9fa94b43c9b986cd5ea",
                  record?["persistenceExtension"] as? String == "rommio-files-v1:azahar:3ds",
                  record?["architecture"] as? String == CartridgePersistence.architecture,
                  let expected = record?["sha256"] as? String, try ProgressFileHash.sha256(core) == expected else {
                throw ProgressStoreError.invalid("The Azahar Vulkan core does not match its pinned build.")
            }
            return expected
        }
        let cartridge = cartridgeRuntimes.contains(runtimeID)
        guard record?["schemaVersion"] as? Int == 1, record?["runtimeId"] as? String == runtimeID,
              (record?["sourceManifestSha256"] as? String).map({ digest in
                  if !cartridge { return digest == sourceManifestSHA256 }
                  if newCartridgeRuntimes.contains(runtimeID) { return digest == expandedCartridgeSourceManifestSHA256 }
                  return digest == cartridgeSourceManifestSHA256 || digest == expandedCartridgeSourceManifestSHA256
              }) == true,
              record?["persistenceExtension"] as? String == (runtimeID == "ppsspp" ? "rommio-files-v1" : cartridge ? "rommio-cart-v1" : "rommio-fceumm-segments-v2"),
              record?["architecture"] as? String == CartridgePersistence.architecture,
              let expected = record?["sha256"] as? String, try ProgressFileHash.sha256(core) == expected else {
            throw ProgressStoreError.invalid("The Apple core does not match its pinned persistence build.")
        }
        return expected
    }
}
