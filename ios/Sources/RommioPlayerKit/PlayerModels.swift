import Foundation
import RommioContract
import RommioFoundation

#if canImport(UIKit)
import UIKit
public typealias PlayerHostController = UIViewController
#else
open class PlayerHostController: NSObject {}
#endif

public enum PlayerCapability: String, Codable, Sendable, CaseIterable {
    case ready = "READY"
    case missingCore = "MISSING_CORE"
    case missingBIOS = "MISSING_BIOS"
    case unsupported = "UNSUPPORTED"
}

public enum CoreProvisioningStatus: String, Codable, Sendable, CaseIterable {
    case ready = "READY"
    case missingCoreNotShipped = "MISSING_CORE_NOT_SHIPPED"
    case missingCoreInstallable = "MISSING_CORE_INSTALLABLE"
    case installingCore = "INSTALLING_CORE"
    case failedCoreInstall = "FAILED_CORE_INSTALL"
    case missingBIOS = "MISSING_BIOS"
    case unsupported = "UNSUPPORTED"
}

public enum RuntimeAvailabilityStatus: String, Codable, Sendable, CaseIterable {
    case playable = "PLAYABLE"
    case blockedByValidation = "BLOCKED_BY_VALIDATION"
    case missingBundledCore = "MISSING_BUNDLED_CORE"
    case missingBIOS = "MISSING_BIOS"
    case unsupportedByCurrentBuild = "UNSUPPORTED_BY_CURRENT_BUILD"
}

public enum PlayerShader: String, Codable, Sendable, CaseIterable {
    case `default` = "DEFAULT"
    case crt = "CRT"
    case lcd = "LCD"
    case sharp = "SHARP"
}

public enum IOSRenderBackend: String, Codable, Sendable, CaseIterable {
    case softwareFramebuffer = "SOFTWARE_FRAMEBUFFER"
    case hardwareRender = "HARDWARE_RENDER"
}

public enum PlayerInteractionProfile: String, Codable, Sendable, CaseIterable {
    case touch = "TOUCH"
    case controller = "CONTROLLER"
    case dualScreenTouch = "DUAL_SCREEN_TOUCH"
    case keyboardMouse = "KEYBOARD_MOUSE"
}

public enum IOSRuntimeValidationState: String, Codable, Sendable, CaseIterable {
    case playable = "PLAYABLE"
    case blockedByValidation = "BLOCKED_BY_VALIDATION"
}

public enum IOSCorePackagingPolicy: String, Codable, Sendable, CaseIterable {
    case bundledSignedDynamicLibrary = "BUNDLED_SIGNED_DYNAMIC_LIBRARY"
}

public struct IOSRuntimeProfile: Codable, Hashable, Sendable {
    public var runtimeID: String
    public var displayName: String
    public var platformSlugs: Set<String>
    public var bundleRelativePath: String
    public var installedFileName: String
    public var defaultVariables: [String: String]
    public var supportedExtensions: Set<String>
    public var requiredBIOSFiles: [String]
    public var supportsSaveStates: Bool
    public var shader: PlayerShader
    public var renderBackend: IOSRenderBackend
    public var interactionProfile: PlayerInteractionProfile
    public var validationState: IOSRuntimeValidationState
    public var validationBlockReason: String?
    public var packagingPolicy: IOSCorePackagingPolicy

    public init(
        runtimeID: String,
        displayName: String,
        platformSlugs: Set<String>,
        bundleRelativePath: String,
        installedFileName: String? = nil,
        defaultVariables: [String: String] = [:],
        supportedExtensions: Set<String> = [],
        requiredBIOSFiles: [String] = [],
        supportsSaveStates: Bool = true,
        shader: PlayerShader = .default,
        renderBackend: IOSRenderBackend = .softwareFramebuffer,
        interactionProfile: PlayerInteractionProfile = .touch,
        validationState: IOSRuntimeValidationState = .playable,
        validationBlockReason: String? = nil,
        packagingPolicy: IOSCorePackagingPolicy = .bundledSignedDynamicLibrary
    ) {
        self.runtimeID = runtimeID
        self.displayName = displayName
        self.platformSlugs = platformSlugs
        self.bundleRelativePath = bundleRelativePath
        self.installedFileName = installedFileName ?? URL(fileURLWithPath: bundleRelativePath).lastPathComponent
        self.defaultVariables = defaultVariables
        self.supportedExtensions = supportedExtensions
        self.requiredBIOSFiles = requiredBIOSFiles
        self.supportsSaveStates = supportsSaveStates
        self.shader = shader
        self.renderBackend = renderBackend
        self.interactionProfile = interactionProfile
        self.validationState = validationState
        self.validationBlockReason = validationBlockReason
        self.packagingPolicy = packagingPolicy
    }
}

public struct IOSRuntimeFamily: Codable, Hashable, Sendable {
    public var familyID: String
    public var displayName: String
    public var platformSlugs: Set<String>
    public var defaultRuntimeID: String
    public var runtimeOptions: [IOSRuntimeProfile]

    public init(
        familyID: String,
        displayName: String,
        platformSlugs: Set<String>,
        defaultRuntimeID: String,
        runtimeOptions: [IOSRuntimeProfile]
    ) {
        self.familyID = familyID
        self.displayName = displayName
        self.platformSlugs = platformSlugs
        self.defaultRuntimeID = defaultRuntimeID
        self.runtimeOptions = runtimeOptions
    }
}

public struct CoreResolution: Codable, Hashable, Sendable {
    public var capability: PlayerCapability
    public var provisioningStatus: CoreProvisioningStatus
    public var availabilityStatus: RuntimeAvailabilityStatus
    public var runtimeProfile: IOSRuntimeProfile?
    public var coreURL: URL?
    public var missingBIOS: [String]
    public var canAutoProvision: Bool
    public var canRetryProvisioning: Bool
    public var message: String?

    public init(
        capability: PlayerCapability,
        provisioningStatus: CoreProvisioningStatus,
        availabilityStatus: RuntimeAvailabilityStatus = .unsupportedByCurrentBuild,
        runtimeProfile: IOSRuntimeProfile? = nil,
        coreURL: URL? = nil,
        missingBIOS: [String] = [],
        canAutoProvision: Bool = false,
        canRetryProvisioning: Bool = false,
        message: String? = nil
    ) {
        self.capability = capability
        self.provisioningStatus = provisioningStatus
        self.availabilityStatus = availabilityStatus
        self.runtimeProfile = runtimeProfile
        self.coreURL = coreURL
        self.missingBIOS = missingBIOS
        self.canAutoProvision = canAutoProvision
        self.canRetryProvisioning = canRetryProvisioning
        self.message = message
    }
}

public struct CoreInventoryEntry: Hashable, Sendable, Identifiable {
    public var familyID: String
    public var familyName: String
    public var runtimeID: String
    public var runtimeName: String
    public var provisioningStatus: CoreProvisioningStatus
    public var availabilityStatus: RuntimeAvailabilityStatus
    public var renderBackend: IOSRenderBackend
    public var interactionProfile: PlayerInteractionProfile
    public var message: String
    public var provisionedAt: String?

    public var id: String { runtimeID }

    public init(
        familyID: String,
        familyName: String,
        runtimeID: String,
        runtimeName: String,
        provisioningStatus: CoreProvisioningStatus,
        availabilityStatus: RuntimeAvailabilityStatus,
        renderBackend: IOSRenderBackend,
        interactionProfile: PlayerInteractionProfile,
        message: String,
        provisionedAt: String? = nil
    ) {
        self.familyID = familyID
        self.familyName = familyName
        self.runtimeID = runtimeID
        self.runtimeName = runtimeName
        self.provisioningStatus = provisioningStatus
        self.availabilityStatus = availabilityStatus
        self.renderBackend = renderBackend
        self.interactionProfile = interactionProfile
        self.message = message
        self.provisionedAt = provisionedAt
    }
}

public struct PlayerSession: Hashable, Sendable {
    public var romID: Int
    public var fileID: Int
    public var romTitle: String
    public var romURL: URL
    public var coreURL: URL
    public var runtimeProfile: IOSRuntimeProfile
    public var systemDirectory: URL
    public var savesDirectory: URL
    public var saveRAMURL: URL
    public var saveStatesDirectory: URL
    public var preferredViewportAspectRatio: Double?
    public var variables: [String: String]
    public var initialSaveRAM: Data?

    public init(
        romID: Int,
        fileID: Int,
        romTitle: String,
        romURL: URL,
        coreURL: URL,
        runtimeProfile: IOSRuntimeProfile,
        systemDirectory: URL,
        savesDirectory: URL,
        saveRAMURL: URL,
        saveStatesDirectory: URL,
        preferredViewportAspectRatio: Double? = nil,
        variables: [String: String] = [:],
        initialSaveRAM: Data? = nil
    ) {
        self.romID = romID
        self.fileID = fileID
        self.romTitle = romTitle
        self.romURL = romURL
        self.coreURL = coreURL
        self.runtimeProfile = runtimeProfile
        self.systemDirectory = systemDirectory
        self.savesDirectory = savesDirectory
        self.saveRAMURL = saveRAMURL
        self.saveStatesDirectory = saveStatesDirectory
        self.preferredViewportAspectRatio = preferredViewportAspectRatio
        self.variables = variables
        self.initialSaveRAM = initialSaveRAM
    }
}

public enum PlayerMotionSource: String, Codable, Sendable, CaseIterable {
    case dpad = "DPAD"
    case analogLeft = "ANALOG_LEFT"
    case analogRight = "ANALOG_RIGHT"
    case pointer = "POINTER"
}

public struct PlayerInputConfiguration: Codable, Hashable, Sendable {
    public var deadzone: Double

    public init(deadzone: Double = 0.2) {
        self.deadzone = deadzone
    }
}

public struct PlayerControllerDescriptor: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var description: String

    public init(id: Int, description: String) {
        self.id = id
        self.description = description
    }
}

public struct PlayerRumbleSignal: Codable, Hashable, Sendable {
    public var port: Int
    public var weakStrength: Double
    public var strongStrength: Double

    public init(port: Int, weakStrength: Double, strongStrength: Double) {
        self.port = port
        self.weakStrength = weakStrength
        self.strongStrength = strongStrength
    }
}

public protocol PlayerEngine: AnyObject, Sendable {
    func prepare(session: PlayerSession) async throws
    func makeHostController() throws -> PlayerHostController
    func start() async throws
    func stop() async
    func persistSaveRAM() async throws -> URL?
    func saveState(to url: URL) async throws -> URL
    func saveState(slot: Int) async throws -> URL
    func loadState(from url: URL) async throws -> Bool
    func setPaused(_ paused: Bool) async throws
    func reset() async throws
    func updateVariables(_ variables: [String: String]) async throws
    func dispatchDigital(keyCode: Int, pressed: Bool, port: Int) async throws
    func dispatchMotion(source: PlayerMotionSource, x: Double, y: Double, port: Int) async throws
    func updateInputConfiguration(_ configuration: PlayerInputConfiguration) async throws
    func availableControllerTypes(port: Int) async throws -> [PlayerControllerDescriptor]
    func setControllerType(port: Int, controllerTypeID: Int) async throws
    func hotkeySignals() -> AsyncStream<PlayerHotkeyAction>
    func rumbleSignals() -> AsyncStream<PlayerRumbleSignal>
    func detach()
}
