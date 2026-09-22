import Foundation
import RommioContract
import RommioFoundation
import RommioPlayerBridge

public struct NativeProgressCapture: Sendable {
    public let memory: [Int: Data]
    public let state: Data?
    public let stateError: String?
    public let options: [String: String]
    public let descriptor: String
    public init(memory: [Int: Data], state: Data?, stateError: String?, options: [String: String], descriptor: String) {
        self.memory = memory; self.state = state; self.stateError = stateError; self.options = options; self.descriptor = descriptor
    }
    public static func read(_ bridge: RMLLibretroBridge, includeState: Bool) throws -> Self {
        let raw = try bridge.captureProgress(includingState: includeState)
        guard let regions = raw["memory"] as? [NSNumber: Data], let options = raw["variables"] as? [String: String], let descriptor = raw["descriptor"] as? String else { throw ProgressStoreError.invalid("Incomplete native capture") }
        return Self(memory: Dictionary(uniqueKeysWithValues: regions.map { ($0.key.intValue, $0.value) }), state: raw["state"] as? Data, stateError: raw["stateError"] as? String, options: options, descriptor: descriptor)
    }
}

public protocol ProgressPlayerEngine: PlayerEngine {
    func startProgress(paused: Bool) async throws
    func captureProgress(includeState: Bool) async throws -> NativeProgressCapture
    func restoreProgress(memory: [Int: Data], state: Data?) async throws
}

public struct CartridgePersistence: Sendable {
    public static let regions = [0: ("sram", "save.srm"), 1: ("rtc", "save.rtc"), 256: ("sram", "save-1.srm"), 512: ("sram", "save-2.srm"), 768: ("sram", "save-3.srm")]
    public let save: ProgressSaveCompatibility
    public let state: ProgressStateCompatibility
    public let sizes: [Int: Int]
    public let descriptor: String
    public var fileBundle: Bool { descriptor == "rommio-files-v1:ppsspp:psp" }
    public var artifactFormat: String { fileBundle ? "psp-memory-stick-v1" : "libretro-memory" }
    public func artifactInfo(_ region: Int) -> (String, String) {
        if fileBundle && region == 0 { return ("saveFile", "memory-stick.rmmf") }
        return Self.regions[region]!
    }
    public func validSize(region: Int, count: Int) -> Bool {
        fileBundle && region == 0 ? (12...64*1024*1024).contains(count) : sizes[region] == count
    }
    public init(capture: NativeProgressCapture, coreSHA256: String, platform: String = Self.platform, architecture: String = Self.architecture, systemFiles: [String: String] = [:]) throws {
        let fields = capture.descriptor.split(separator: ":").map(String.init)
        if capture.descriptor == "rommio-files-v1:ppsspp:psp" {
            descriptor = capture.descriptor; sizes = [0: 12, 1: 0]
            save = ProgressSaveCompatibility(system: "psp", format: "ppsspp-memory-stick", layoutVersion: "v1", coreId: "ppsspp", allowsEmpty: false)
            state = ProgressStateCompatibility(coreId: "ppsspp", coreSha256: coreSHA256, stateFormat: "ppsspp-native-v1", platform: platform, architecture: architecture, options: capture.options, systemFiles: systemFiles)
            try validate(capture); return
        }
        if fields.first == "rommio-cart-v1" {
            guard fields.count == 5, let ram = Int(fields[3]), let rtc = Int(fields[4]),
                  Self.validLayout(core: fields[1], system: fields[2], ram: ram, rtc: rtc) else {
                throw ProgressStoreError.invalid("This cartridge persistence layout is not supported")
            }
            descriptor = capture.descriptor; sizes = [0: ram, 1: rtc]
            save = ProgressSaveCompatibility(system: fields[2], format: "\(fields[1])-cart", layoutVersion: "v1-s\(ram)-r\(rtc)", coreId: fields[1], allowsEmpty: ram == 0 && rtc == 0)
            state = ProgressStateCompatibility(coreId: fields[1], coreSha256: coreSHA256, stateFormat: "\(fields[1])-native-v1", platform: platform, architecture: architecture, options: capture.options, systemFiles: systemFiles)
            try validate(capture)
            return
        }
        guard fields.count == 7, fields[0] == "fceumm-ines-v1", ["1", "2"].contains(fields[1]),
              let mapper = Int(fields[2]), [0,19].contains(mapper), fields[3] == "0", ["0", "1"].contains(fields[4]),
              let prg = Int(fields[5]), (0...8192).contains(prg), fields[6] == "0", fields[4] == "1" || prg == 0 else {
            throw ProgressStoreError.invalid("This cartridge layout has not been qualified for complete persistence. Existing progress is preserved.")
        }
        let battery = fields[4] == "1"
        descriptor = capture.descriptor
        sizes = Dictionary(uniqueKeysWithValues: Self.regions.keys.map { ($0, battery ? ($0 == 0 ? 8192 : ($0 == 256 && mapper == 19 ? 128 : 0)) : 0) })
        save = ProgressSaveCompatibility(system: "nes", format: "fceumm-cart", layoutVersion: "ines\(fields[1])-m\(mapper)-s0-b\(fields[4])-p\(prg)-c0", coreId: "fceumm", allowsEmpty: !battery)
        state = ProgressStateCompatibility(coreId: "fceumm", coreSha256: coreSHA256, stateFormat: "fceumm-native-v1", platform: platform, architecture: architecture, options: capture.options, systemFiles: systemFiles)
        try validate(capture)
    }
    static func validLayout(core: String, system: String, ram: Int, rtc: Int) -> Bool {
        switch (core, system) {
        case ("gambatte", "gb"): return [0, 512, 2048, 8192, 32768, 65536, 131072].contains(ram) && [0, 24].contains(rtc)
        case ("mgba", "gba"): return ram == 131080 && rtc == 24
        case ("snes9x", "snes"): return ram >= 0 && ram <= 131072 && (ram == 0 || ram.nonzeroBitCount == 1) && rtc == 0
        case ("melonds_ds", "nds"): return [512, 8192, 65536, 131072, 262144, 524288, 1048576, 8388608].contains(ram - 2308) && rtc == 0
        case ("genesis_plus_gx", "genesis"), ("genesis_plus_gx", "sms"), ("genesis_plus_gx", "gamegear"):
            return [0, 65536].contains(ram) && rtc == 0
        case ("genesis_plus_gx", "segacd"):
            return [0, 16384, 32768, 65536, 131072, 262144, 524288].contains(ram - 8192) && rtc == 0
        case ("mupen64plus_next_gles3", "n64"): return ram == 296960 && rtc == 24
        case ("pcsx_rearmed", "psx"): return ram == 262144 && rtc == 0
        default: return false
        }
    }
    public func validate(_ capture: NativeProgressCapture) throws {
        guard capture.descriptor == descriptor, capture.options == state.options,
              Set(capture.memory.keys) == Set(sizes.keys), sizes.allSatisfy({ validSize(region: $0.key, count: capture.memory[$0.key]?.count ?? -1) }) else { throw ProgressStoreError.invalid("The core's persistent memory layout or options changed.") }
    }
    public static var platform: String {
        #if targetEnvironment(simulator)
        "ios-simulator"
        #elseif os(iOS)
        "ios"
        #else
        "macos-test"
        #endif
    }
    public static var architecture: String {
        #if arch(arm64)
        "arm64"
        #else
        "x86_64"
        #endif
    }
}

public extension ProgressPlayerEngine {
    func startProgress(paused: Bool) async throws { try await start(); try await setPaused(paused) }
}

// Source compatibility for the original audited NES route and its fixtures.
public typealias FceummPersistence = CartridgePersistence
