import Foundation
import CoreGraphics
import Observation
import RommioContract
import RommioFoundation
import RommioPlayerKit

#if canImport(UIKit)
import UIKit
#endif

@MainActor
@Observable
public final class PlayerFeatureModel: Identifiable {
    public let id: UUID
    let presentation: PlayerPresentationState

    var controlsState: PlayerControlsState?
    var pauseSheetPresented = false
    var controlsSheetPresented = false
    var stateBrowserPresented = false
    var isPaused = false
    var isStarting = false
    var hasStarted = false
    var errorMessage: String?
    var noticeMessage: String?
    var primaryControlsVisible = true
    var tertiaryControlsVisible = true
    var lastSavedStateURL: URL?
    var availableControllerTypes: [PlayerControllerDescriptor] = []
    var stateBrowser = GameStateBrowser()
    var syncPresentation = GameSyncPresentation()

    @ObservationIgnored
    private let engine: PlayerEngine
    @ObservationIgnored
    private let services: RommioServices
    @ObservationIgnored
    private let rom: RomDTO
    @ObservationIgnored
    private let file: RomFileDTO
    @ObservationIgnored
    private let installation: InstalledROMReference
    @ObservationIgnored
    private let runtimeID: String
    @ObservationIgnored
    private let onLeave: @MainActor () async -> Void
    @ObservationIgnored
    private var primaryFadeTask: Task<Void, Never>?
    @ObservationIgnored
    private var tertiaryFadeTask: Task<Void, Never>?
    @ObservationIgnored
    private var controllerUpdatesTask: Task<Void, Never>?
    @ObservationIgnored
    private var hotkeyTask: Task<Void, Never>?
    @ObservationIgnored
    private var rumbleTask: Task<Void, Never>?
    @ObservationIgnored
    private var syncRetryTask: Task<Void, Never>?
    @ObservationIgnored
    private var continuitySyncTask: Task<Void, Never>?
    @ObservationIgnored
    private var initialLaunchTarget: PlayerLaunchTarget?
    @ObservationIgnored
    private var sessionStartedAtEpochMS: Int64?

    init(
        presentation: PlayerPresentationState,
        launchPreparation: PlayerLaunchPreparation = PlayerLaunchPreparation(),
        engine: PlayerEngine,
        services: RommioServices,
        rom: RomDTO,
        file: RomFileDTO,
        installation: InstalledROMReference,
        runtimeID: String,
        onLeave: @escaping @MainActor () async -> Void
    ) {
        self.id = presentation.id
        self.presentation = presentation
        self.engine = engine
        self.services = services
        self.rom = rom
        self.file = file
        self.installation = installation
        self.runtimeID = runtimeID
        self.onLeave = onLeave
        self.initialLaunchTarget = launchPreparation.launchTarget
        self.syncPresentation = launchPreparation.syncPresentation
        if launchPreparation.resumeConflict != nil {
            self.noticeMessage = "Resume conflict detected. Review resume, save slots, and snapshots."
        }
    }

    var theme: PlayerVisualTheme {
        resolvePlayerVisualTheme(controls: controlsState)
    }

    var orientationPolicy: PlayerOrientationPolicy {
        controlsState?.platformProfile.playerOrientationPolicy ?? .auto
    }

    var supportTier: EmbeddedSupportTier {
        controlsState?.platformProfile.supportTier ?? .unsupported
    }

    var controllerFallbackMessage: String? {
        controlsState?.platformProfile.controllerFallbackMessage
    }

    func overlaySnapshot(
        containerSize: CGSize,
        safeAreaInsets: PlayerViewportSafeAreaInsets
    ) -> PlayerOverlaySnapshot? {
        guard let controlsState else { return nil }
        return resolvePlayerOverlaySnapshot(
            containerSize: containerSize,
            safeAreaInsets: safeAreaInsets,
            controlsState: controlsState
        )
    }

    func makeHostController() throws -> PlayerHostController {
        try engine.makeHostController()
    }

    func startIfNeeded() async {
        guard !hasStarted, !isStarting else { return }
        isStarting = true
        defer { isStarting = false }
        await reloadControlsState()

        do {
            if let deadzone = controlsState?.hardwareBinding.deadzone {
                try await engine.updateInputConfiguration(PlayerInputConfiguration(deadzone: deadzone))
            }
            if let controllerTypeID = controlsState?.hardwareBinding.controllerTypeID {
                try await engine.setControllerType(port: 0, controllerTypeID: controllerTypeID)
            }
            availableControllerTypes = (try? await engine.availableControllerTypes(port: 0)) ?? []
            startSignalTasksIfNeeded()
            try await engine.start()
            hasStarted = true
            sessionStartedAtEpochMS = Int64(Date().timeIntervalSince1970 * 1000)
            if let target = initialLaunchTarget {
                _ = try await engine.loadState(from: URL(fileURLWithPath: target.localStatePath))
                noticeMessage = "Loaded \(target.label ?? "state")."
                initialLaunchTarget = nil
            }
            await refreshStateBrowser()
            scheduleContinuitySync()
            bumpPrimaryControls()
            bumpTertiaryControls(wakePrimary: false)
        } catch PlayerEngineError.frameLoopAlreadyRunning {
            hasStarted = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func leaveGame() async {
        primaryFadeTask?.cancel()
        tertiaryFadeTask?.cancel()
        controllerUpdatesTask?.cancel()
        hotkeyTask?.cancel()
        rumbleTask?.cancel()
        syncRetryTask?.cancel()
        continuitySyncTask?.cancel()
        await captureContinuity(syncManualSlots: false, sessionActive: false)
        await engine.stop()
        engine.detach()
        await onLeave()
    }

    func openPauseMenu() async {
        bumpTertiaryControls(wakePrimary: true)
        do {
            try await engine.setPaused(true)
            isPaused = true
            pauseSheetPresented = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resumeGame() async {
        do {
            try await engine.setPaused(false)
            isPaused = false
            pauseSheetPresented = false
            bumpPrimaryControls()
            bumpTertiaryControls(wakePrimary: false)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func quickSave() async {
        do {
            let url = try await engine.saveState(slot: 0)
            if let profile = await currentProfile() {
                try await services.syncBridge.recordManualState(
                    profile: profile,
                    installation: installation,
                    slot: 0,
                    fileURL: url
                )
            }
            lastSavedStateURL = url
            noticeMessage = "Saved Save slot 0."
            await refreshStateBrowser()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func quickLoadPreferredState() async {
        if let resumePath = stateBrowser.resume?.localPath,
           FileManager.default.fileExists(atPath: resumePath) {
            await loadState(from: URL(fileURLWithPath: resumePath), label: "Resume")
            return
        }
        if let state = stateBrowser.saveSlots.first ?? stateBrowser.snapshots.first {
            await loadBrowsableState(state)
            return
        }
        errorMessage = "No emulator state exists yet."
    }

    func openStateBrowser() async {
        await refreshStateBrowser()
        pauseSheetPresented = false
        stateBrowserPresented = true
    }

    func loadResumeState() async {
        guard let localPath = stateBrowser.resume?.localPath else {
            errorMessage = "No resume state exists yet."
            return
        }
        await loadState(from: URL(fileURLWithPath: localPath), label: "Resume")
    }

    func loadBrowsableState(_ state: BrowsableGameState) async {
        await loadState(from: URL(fileURLWithPath: state.localPath), label: state.label)
    }

    func deleteState(_ state: BrowsableGameState) async {
        guard let profile = await currentProfile() else {
            errorMessage = "Sign in again before deleting a state."
            return
        }
        do {
            try await services.syncBridge.deleteBrowsableState(
                profile: profile,
                installation: installation,
                state: state
            )
            await refreshStateBrowser()
            noticeMessage = "Deleted \(state.label)."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func syncSaves() async {
        let profile: ServerProfile?
        if let cachedProfile = services.profileStoreActiveCache {
            profile = cachedProfile
        } else {
            profile = await currentProfile()
        }
        guard let profile else {
            errorMessage = "Sign in again before syncing saves."
            return
        }

        guard services.networkMonitor.connectivity == .online else {
            noticeMessage = "Save sync deferred until connectivity returns."
            queueDeferredSync()
            return
        }

        do {
            try await captureContinuityLocally()
            let summary = try await services.syncBridge.syncGame(
                profile: profile,
                installation: installation,
                rom: rom,
                runtimeID: runtimeID
            )
            syncPresentation = try services.syncBridge.cachedPlayerLaunch(
                profile: profile,
                installation: installation,
                connectivity: services.networkMonitor.connectivity
            ).syncPresentation
            await refreshStateBrowser()
            noticeMessage = "Sync finished. Uploaded \(summary.uploaded), downloaded \(summary.downloaded)."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resetCore() async {
        do {
            try await engine.reset()
            noticeMessage = "Core reset."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setTouchControlsEnabled(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setTouchControlsEnabled(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setAutoHideTouchOnController(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setAutoHideTouchOnController(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setRumbleToDeviceEnabled(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setRumbleToDeviceEnabled(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setOLEDBlackModeEnabled(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setOLEDBlackModeEnabled(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setConsoleColorsEnabled(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setConsoleColorsEnabled(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func updateTouchOpacity(_ value: Double) async {
        await mutateTouchLayout { $0.opacity = value }
    }

    func updateTouchGlobalScale(_ value: Double) async {
        await mutateTouchLayout { $0.globalScale = value }
    }

    func setLeftHanded(_ enabled: Bool) async {
        await mutateTouchLayout { $0.leftHanded = enabled }
    }

    func updateDeadzone(_ deadzone: Double) async {
        guard let controlsState else { return }
        let binding = HardwareBindingProfile(
            platformFamilyID: controlsState.platformProfile.familyID,
            controllerTypeID: controlsState.hardwareBinding.controllerTypeID,
            deadzone: deadzone
        )
        do {
            try await services.playerControlsRepository.saveHardwareBinding(binding)
            try await engine.updateInputConfiguration(PlayerInputConfiguration(deadzone: deadzone))
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setControllerType(_ controllerTypeID: Int?) async {
        guard let controlsState else { return }
        let binding = HardwareBindingProfile(
            platformFamilyID: controlsState.platformProfile.familyID,
            controllerTypeID: controllerTypeID,
            deadzone: controlsState.hardwareBinding.deadzone
        )
        do {
            try await services.playerControlsRepository.saveHardwareBinding(binding)
            if let controllerTypeID {
                try await engine.setControllerType(port: 0, controllerTypeID: controllerTypeID)
            }
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resetControlTuning() async {
        guard let controlsState else { return }
        do {
            try await services.playerControlsRepository.resetTouchLayout(platformSlug: presentation.platformSlug)
            try await services.playerControlsRepository.saveHardwareBinding(
                HardwareBindingProfile(platformFamilyID: controlsState.platformProfile.familyID)
            )
            try await engine.updateInputConfiguration(PlayerInputConfiguration(deadzone: 0.2))
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func press(_ action: ControlAction) async {
        clearTransientMessages()
        bumpPrimaryControls()
        switch action {
        case let .digital(_, keyCode, _):
            do {
                try await engine.dispatchDigital(keyCode: keyCode, pressed: true, port: 0)
            } catch {
                errorMessage = error.localizedDescription
            }
        case .pointer:
            break
        }
    }

    func release(_ action: ControlAction) async {
        switch action {
        case let .digital(_, keyCode, _):
            do {
                try await engine.dispatchDigital(keyCode: keyCode, pressed: false, port: 0)
            } catch {
                errorMessage = error.localizedDescription
            }
        case .pointer:
            break
        }
    }

    func dispatchPointer(normalizedX: Double, normalizedY: Double) async {
        bumpPrimaryControls()
        do {
            try await engine.dispatchMotion(source: .pointer, x: normalizedX, y: normalizedY, port: 0)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func bumpPrimaryControls() {
        primaryControlsVisible = true
        guard !pauseSheetPresented, !controlsSheetPresented else { return }
        primaryFadeTask?.cancel()
        primaryFadeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, !self.pauseSheetPresented, !self.controlsSheetPresented else { return }
                self.primaryControlsVisible = false
            }
        }
    }

    func bumpTertiaryControls(wakePrimary: Bool) {
        if wakePrimary {
            bumpPrimaryControls()
        }
        tertiaryControlsVisible = true
        guard !pauseSheetPresented, !controlsSheetPresented else { return }
        tertiaryFadeTask?.cancel()
        tertiaryFadeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, !self.pauseSheetPresented, !self.controlsSheetPresented else { return }
                self.tertiaryControlsVisible = false
            }
        }
    }

    private func reloadControlsState() async {
        do {
            controlsState = try await services.playerControlsRepository.controls(platformSlug: presentation.platformSlug)
            availableControllerTypes = (try? await engine.availableControllerTypes(port: 0)) ?? availableControllerTypes
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func mutateTouchLayout(_ update: (inout TouchLayoutProfile) -> Void) async {
        guard var layout = controlsState?.touchLayout else { return }
        update(&layout)
        do {
            try await services.playerControlsRepository.saveTouchLayout(layout)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func queueDeferredSync() {
        syncRetryTask?.cancel()
        syncRetryTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                if self.services.networkMonitor.connectivity == .online {
                    await self.syncSaves()
                    return
                }
            }
        }
    }

    private func startSignalTasksIfNeeded() {
        guard controllerUpdatesTask == nil else { return }

        controllerUpdatesTask = Task { [weak self] in
            guard let self else { return }
            for await _ in self.services.controllerMonitor.updates() {
                await self.reloadControlsState()
            }
        }

        hotkeyTask = Task { [weak self] in
            guard let self else { return }
            for await action in self.engine.hotkeySignals() {
                await self.handleHotkey(action)
            }
        }

        rumbleTask = Task { [weak self] in
            guard let self else { return }
            for await signal in self.engine.rumbleSignals() {
                guard self.controlsState?.preferences.rumbleToDeviceEnabled == true else { continue }
                await self.deliverDeviceRumble(signal)
            }
        }
    }

    private func currentProfile() async -> ServerProfile? {
        try? await services.profileStore.activeProfile()
    }

    private func clearTransientMessages() {
        errorMessage = nil
        noticeMessage = nil
    }

    private func handleHotkey(_ action: PlayerHotkeyAction) async {
        switch action {
        case .pauseMenu:
            if pauseSheetPresented || isPaused {
                await resumeGame()
            } else {
                await openPauseMenu()
            }
        case .quickSave:
            await quickSave()
        case .quickLoad:
            await quickLoadPreferredState()
        case .reset:
            await resetCore()
        }
    }

    private func deliverDeviceRumble(_ signal: PlayerRumbleSignal) async {
        #if canImport(UIKit)
        let intensity = max(signal.weakStrength, signal.strongStrength)
        guard intensity > 0 else { return }
        let generator = UIImpactFeedbackGenerator(style: intensity > 0.66 ? .heavy : (intensity > 0.33 ? .medium : .light))
        generator.prepare()
        generator.impactOccurred(intensity: CGFloat(min(max(intensity, 0.15), 1.0)))
        #else
        _ = signal
        #endif
    }

    private func refreshStateBrowser() async {
        guard let profile = await currentProfile() else {
            do {
                stateBrowser = try services.syncBridge.cachedStateBrowser(
                    profile: nil,
                    installation: installation,
                    connectivity: services.networkMonitor.connectivity
                )
                syncPresentation = try services.syncBridge.cachedPlayerLaunch(
                    profile: nil,
                    installation: installation,
                    connectivity: services.networkMonitor.connectivity
                ).syncPresentation
            } catch {
                errorMessage = error.localizedDescription
            }
            return
        }
        do {
            stateBrowser = try await services.syncBridge.refreshStateBrowser(
                profile: profile,
                installation: installation,
                rom: rom,
                runtimeID: runtimeID,
                connectivity: services.networkMonitor.connectivity
            )
            syncPresentation = try services.syncBridge.cachedPlayerLaunch(
                profile: profile,
                installation: installation,
                connectivity: services.networkMonitor.connectivity
            ).syncPresentation
        } catch {
            do {
                stateBrowser = try services.syncBridge.cachedStateBrowser(
                    profile: profile,
                    installation: installation,
                    connectivity: services.networkMonitor.connectivity
                )
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func loadState(from url: URL, label: String) async {
        guard FileManager.default.fileExists(atPath: url.path) else {
            errorMessage = "\(label) is not available on this device yet."
            return
        }
        do {
            _ = try await engine.loadState(from: url)
            stateBrowserPresented = false
            pauseSheetPresented = false
            noticeMessage = "Loaded \(label)."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func captureContinuity(syncManualSlots: Bool, sessionActive: Bool) async {
        guard presentation.supportsSaveStates else { return }
        do {
            try await captureContinuityLocally()
            if let profile = await currentProfile(), services.networkMonitor.connectivity == .online {
                let summary = try await services.syncBridge.flushContinuity(
                    profile: profile,
                    installation: installation,
                    rom: rom,
                    runtimeID: runtimeID,
                    sessionActive: sessionActive,
                    sessionStartedAtEpochMS: sessionStartedAtEpochMS
                )
                if syncManualSlots {
                    _ = summary
                }
                syncPresentation = try services.syncBridge.cachedPlayerLaunch(
                    profile: profile,
                    installation: installation,
                    connectivity: services.networkMonitor.connectivity
                ).syncPresentation
                await refreshStateBrowser()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func captureContinuityLocally() async throws {
        guard presentation.supportsSaveStates else { return }
        _ = try await engine.persistSaveRAM()
        _ = try await engine.saveState(to: services.libraryStore.continuityResumeStateURL(for: installation))
    }

    private func scheduleContinuitySync() {
        continuitySyncTask?.cancel()
        guard presentation.supportsSaveStates else { return }
        continuitySyncTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(120))
                guard !Task.isCancelled else { return }
                await self.captureContinuity(syncManualSlots: false, sessionActive: true)
            }
        }
    }
}

private extension RommioServices {
    var profileStoreActiveCache: ServerProfile? {
        nil
    }
}
