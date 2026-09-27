import Foundation
import CoreGraphics
import Observation
import RommioContract
import RommioFoundation
import RommioPlayerKit

#if canImport(UIKit)
import UIKit
#endif

/// A conflicted reference and the head chosen on the Conflict sheet.
struct PendingProgressResolution: Sendable {
    let identity: ProgressIdentity
    let reference: String
    let selected: String
    let expected: [String]
}

@MainActor
@Observable
public final class PlayerFeatureModel: Identifiable {
    public let id: UUID
    let presentation: PlayerPresentationState

    let legacyProgressFiles: [URL]
    let progressOwner: ProgressSessionOwner?
    var cloudMessage: String?
    var progressHistory: [ProgressHistoryEntry] = []
    var progressReferences: [String: [ProgressHead]] = [:]
    /// The session's history as saves (kind, name, device, sync, preview) for the Save states sheet.
    var progressSaves: [SaveEntry] = []
    var progressSyncMessage = "Cloud progress not checked"
    var pendingRestoreID: String?
    var preferColdBoot = false
    private var resumeAfterBackground = false
    private var backgrounded = false
    var controlsState: PlayerControlsState?
    /// This game's touch-control settings: PlayStation's Analog controls and the stick tuning.
    var gameControls = PlayerGameControls()
    var analogControls: Bool { gameControls.analogControls }
    /// Game artwork shown softly behind letterboxing so the player fills the display.
    var backdropURL: URL?
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
    private let onLeave: @MainActor (String?) async -> Void
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
    /// Chosen on the Conflict sheet; applied once, before the session starts.
    @ObservationIgnored
    private var resolution: PendingProgressResolution?

    init(
        presentation: PlayerPresentationState,
        launchPreparation: PlayerLaunchPreparation = PlayerLaunchPreparation(),
        engine: PlayerEngine,
        services: RommioServices,
        rom: RomDTO,
        file: RomFileDTO,
        installation: InstalledROMReference,
        runtimeID: String,
        progressOwner: ProgressSessionOwner? = nil,
        resolution: PendingProgressResolution? = nil,
        cloudMessage: String? = nil,
        legacyProgressFiles: [URL] = [],
        onLeave: @escaping @MainActor (String?) async -> Void
    ) {
        self.legacyProgressFiles = legacyProgressFiles
        self.progressOwner = progressOwner
        self.resolution = resolution
        self.cloudMessage = cloudMessage
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

    /// The app theme in effect where the player is shown; set by the player view.
    var appTheme = RommioTheme(.aurora, dark: true)

    var theme: PlayerVisualTheme {
        resolvePlayerVisualTheme(controls: controlsState, appTheme: appTheme)
    }

    var orientationPolicy: PlayerOrientationPolicy {
        controlsState?.platformProfile.playerOrientationPolicy ?? .auto
    }

    // MARK: Stage (iPhone Duo fold, DS screens, side panes)

    /// Shared with the renderer so video, stylus, and controls agree on layout.
    private(set) var stageContext = PlayerStageContext()
    private var windowFold: CGRect?
    private var windowFoldIsActive = false
    /// Width of a live side pane (controls editor or saved progress) beside the game.
    private(set) var sidePaneWidth: CGFloat = 0

    var isDualScreen: Bool { ["nds", "3ds"].contains(controlsState?.platformProfile.familyID ?? "") }
    var isThreeDS: Bool { controlsState?.platformProfile.familyID == "3ds" }

    func updateWindowFold(_ fold: CGRect?, isActive: Bool) {
        windowFold = fold
        windowFoldIsActive = isActive
        recomputeStage()
    }

    func updateSidePaneWidth(_ width: CGFloat) {
        sidePaneWidth = width
        recomputeStage()
    }

    private func recomputeStage() {
        #if canImport(UIKit)
        let isPhoneFormFactor = UIDevice.current.userInterfaceIdiom == .phone
        #else
        let isPhoneFormFactor = false
        #endif
        let next = PlayerStageContext(
            fold: windowFold,
            foldIsActive: windowFoldIsActive,
            dualScreen: isDualScreen,
            threeDS: isThreeDS,
            isPhoneFormFactor: isPhoneFormFactor,
            prefersPortrait: orientationPolicy == .auto ? nil : orientationPolicy == .portraitOnly,
            trailingPaneWidth: sidePaneWidth,
            foldable: windowFold != nil
        )
        guard next != stageContext else { return }
        stageContext = next
        let deadzone = controlsState?.hardwareBinding.deadzone ?? 0.2
        Task { try? await engine.updateInputConfiguration(PlayerInputConfiguration(deadzone: deadzone, stage: next)) }
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
            controlsState: controlsState,
            stage: stageContext
        )
    }

    func makeHostController() throws -> PlayerHostController {
        try engine.makeHostController()
    }

    func startIfNeeded() async {
        guard !hasStarted, !isStarting, cloudMessage == nil else { return }
        errorMessage = nil
        isStarting = true
        defer { isStarting = false }
        await reloadControlsState()

        do {
            if let deadzone = controlsState?.hardwareBinding.deadzone {
                try await engine.updateInputConfiguration(PlayerInputConfiguration(deadzone: deadzone))
            }
            if let controllerTypeID = controlsState?.hardwareBinding.controllerTypeID ?? defaultConsoleControllerType {
                try await engine.setControllerType(port: 0, controllerTypeID: controllerTypeID)
            }
            availableControllerTypes = (try? await engine.availableControllerTypes(port: 0)) ?? []
            startSignalTasksIfNeeded()
            #if canImport(GameController)
            if gamepadInput == nil { gamepadInput = PlayerGamepadInput(feature: self) }
            gamepadInput?.start()
            #endif
            var initiallyPaused = false
            #if DEBUG && canImport(UIKit)
            initiallyPaused = ProcessInfo.processInfo.environment["ROMMIO_PROGRESS_PROBE_PAUSED"] == "1"
            #endif
            if let progressOwner, let choice = resolution {
                // The Conflict sheet's choice: the same resolution as choosing the branch here.
                resolution = nil
                try await progressOwner.resolve(reference: choice.reference, selected: choice.selected, expected: choice.expected)
            }
            if let progressOwner { noticeMessage = try await progressOwner.launch(coldBoot: preferColdBoot, startPaused: initiallyPaused); isPaused = initiallyPaused }
            else { try await engine.start() }
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
            NSLog("[PlayerFeatureModel] start failed: %@", error.localizedDescription)
            errorMessage = error.localizedDescription
        }
    }

    func leaveGame() async {
        if let progressOwner {
            do {
                let result = try await progressOwner.exit()
                noticeMessage = result?.message
            } catch { errorMessage = error.localizedDescription; isPaused = true; return }
        }
        #if canImport(GameController)
        gamepadInput?.stop()
        #endif
        primaryFadeTask?.cancel()
        tertiaryFadeTask?.cancel()
        controllerUpdatesTask?.cancel()
        hotkeyTask?.cancel()
        rumbleTask?.cancel()
        syncRetryTask?.cancel()
        continuitySyncTask?.cancel()
        if progressOwner == nil {
            await captureContinuity(syncManualSlots: false, sessionActive: false)
            await engine.stop()
            engine.detach()
        }
        await onLeave(noticeMessage)
    }

    func openPauseMenu() async {
        bumpTertiaryControls(wakePrimary: true)
        do {
            if let progressOwner {
                noticeMessage = try await progressOwner.capture(reason: "pause", remainPaused: true).message
            } else { try await engine.setPaused(true) }
            isPaused = true
            pauseSheetPresented = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resumeGame() async {
        do {
            if let progressOwner { try await progressOwner.resume() }
            else { try await engine.setPaused(false) }
            isPaused = false
            pauseSheetPresented = false
            bumpPrimaryControls()
            bumpTertiaryControls(wakePrimary: false)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func quickSave() async {
        if let progressOwner {
            do {
                noticeMessage = try await progressOwner.capture(reason: "manual", slotID: UUID().uuidString.lowercased(), remainPaused: isPaused).message
                await refreshProgress()
            } catch { errorMessage = error.localizedDescription; isPaused = true }
            return
        }
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
            noticeMessage = "Save state saved."
            await refreshStateBrowser()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func quickLoadPreferredState() async {
        if progressOwner != nil {
            await refreshProgress()
            if let state = progressHistory.first(where: { progressOwner?.qualification.restoresState($0.checkpoint) == true }) { await restoreProgress(state.id) }
            else { errorMessage = "No compatible snapshot is available. Game-save data remains separate." }
            return
        }
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
        if progressOwner != nil { await quickLoadPreferredState(); return }
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
        if let progressOwner {
            do {
                if hasStarted { noticeMessage = try await progressOwner.capture(reason: "manual", remainPaused: isPaused).message }
                let result = try await services.progress.synchronize(progressOwner.identity)
                progressSyncMessage = result.message
                await refreshProgress()
            } catch { errorMessage = error.localizedDescription }
            return
        }
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
            if let progressOwner { try await progressOwner.reset(); isPaused = true }
            else { try await engine.reset() }
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

    func setHapticsEnabled(_ enabled: Bool) async {
        do {
            try await services.playerControlsRepository.setHapticsEnabled(enabled)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// The pause menu's sound toggle. Takes effect at once; saved for every game.
    func toggleAudioMuted() async {
        let muted = !audioMuted
        engine.setAudioMuted(muted)
        do {
            try await services.playerControlsRepository.setAudioMuted(muted)
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

    /// The family has sticks that the Analog controls setting shows or hides.
    var offersAnalogControls: Bool { controlsState?.platformProfile.familyID == "psx" }

    func setAnalogControls(_ enabled: Bool) async {
        await updateGameControls { $0.analogControls = enabled }
    }

    /// The Stick group shows while the game has touch sticks on screen.
    var offersStickTuning: Bool { controlPlan?.hasSticks ?? false }

    func updateStickTuning(_ update: (inout PlayerStickTuning) -> Void) async {
        await updateGameControls { update(&$0.stick) }
    }

    func resetStickTuning() async {
        await updateGameControls { $0.stick = .standard }
    }

    private func updateGameControls(_ update: (inout PlayerGameControls) -> Void) async {
        var value = gameControls
        update(&value)
        do {
            try await services.playerControlsRepository.saveGameControls(value, romID: presentation.romID)
            await reloadControlsState()
        } catch {
            errorMessage = error.localizedDescription
        }
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
            if let controllerTypeID = controllerTypeID ?? defaultConsoleControllerType {
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

    private var defaultConsoleControllerType: Int? {
        switch controlsState?.platformProfile.familyID {
        case "sega16": return 513 // Genesis Plus GX six-button pad
        case "psx": return 517 // PCSX-ReARMed DualShock
        default: return nil
        }
    }

    // MARK: Touch controls (docs/player-controls.md)

    /// The family's touch controls, kept while the family and the game's settings stay the same.
    @ObservationIgnored private var cachedPlan: (family: String, game: PlayerGameControls, plan: PlayerControlPlan?)?

    var controlPlan: PlayerControlPlan? {
        guard let family = controlsState?.platformProfile.familyID else { return nil }
        if let cachedPlan, cachedPlan.family == family, cachedPlan.game == gameControls { return cachedPlan.plan }
        let plan = PlayerControlPlan(family: family, analogControls: gameControls.analogControls, stickTuning: gameControls.stick)
        cachedPlan = (family, gameControls, plan)
        return plan
    }

    /// Touch controls are on and this family plays by touch.
    var reservesTouchSpace: Bool {
        guard let state = controlsState else { return false }
        return state.showTouchControls && state.platformProfile.supportTier == .touchSupported
    }

    /// Every touch control and the Menu button for this window: the
    /// same stage the renderer draws the game in.
    func touchLayout(containerSize: CGSize, safeAreaInsets: PlayerViewportSafeAreaInsets) -> PlayerTouchLayout? {
        guard let state = controlsState else { return nil }
        let plan = controlPlan
        let stage = resolvePlayerStageLayout(
            containerSize: containerSize, safeAreaInsets: safeAreaInsets,
            aspectRatio: state.platformProfile.preferredViewportAspectRatio,
            topAlignedInPortrait: state.platformProfile.supportTier == .touchSupported,
            controlDeckHeight: ExpansionControls.deckHeight(ExpansionControls.controls(for: state.platformProfile.familyID) ?? []),
            controlScale: state.touchLayout?.globalScale ?? 1,
            reserveTouchSpace: reservesTouchSpace && plan != nil,
            utilityCount: 1,
            stage: stageContext,
            controls: plan,
            mirrored: state.touchLayout?.leftHanded ?? false)
        return stage.controls ?? PlayerTouchLayout(
            controls: [], faceZone: nil, chord: nil,
            menuCenter: CGPoint(x: stage.deck.utilityX(index: 0, count: 1, width: containerSize.width), y: stage.deck.utilityY),
            menuDiameter: PlayerControlMetrics.menuDiameter, metrics: PlayerControlMetrics(scale: 1))
    }

    var hapticsEnabled: Bool { controlsState?.preferences.hapticsEnabled ?? true }
    var audioMuted: Bool { controlsState?.preferences.audioMuted ?? false }

    /// Touch input, applied now and in order (no task hop): the touch surface calls this
    /// for every press, release and stick movement.
    func touchInput(_ output: PlayerTouchOutput) {
        switch output {
        case let .digital(key, pressed):
            sendDigital(key, pressed: pressed)
        case let .motion(stick, x, y):
            sendMotion(stick, x: x, y: y)
        case .pressHaptic, .directionHaptic, .stickHaptic, .stickNotch:
            break
        }
    }

    private func sendDigital(_ key: Int, pressed: Bool) {
        if pressed { dismissTransientMessages() }
        if let immediate = engine as? ImmediatePlayerInput {
            do { try immediate.sendDigital(keyCode: key, pressed: pressed, port: 0) } catch { reportInputError(error) }
        } else {
            let previous = consoleInputTask
            consoleInputTask = Task { @MainActor in
                await previous?.value
                do { try await engine.dispatchDigital(keyCode: key, pressed: pressed, port: 0) } catch { reportInputError(error) }
            }
        }
    }

    private func sendMotion(_ stick: Int, x: Double, y: Double) {
        if abs(x) + abs(y) > 0.01 { dismissTransientMessages() }
        let source: PlayerMotionSource = stick == 0 ? .analogLeft : .analogRight
        if let immediate = engine as? ImmediatePlayerInput {
            do { try immediate.sendMotion(source: source, x: x, y: y, port: 0) } catch { reportInputError(error) }
        } else {
            let previous = consoleInputTask
            consoleInputTask = Task { @MainActor in
                await previous?.value
                do { try await engine.dispatchMotion(source: source, x: x, y: y, port: 0) } catch { reportInputError(error) }
            }
        }
    }

    /// A session that isn't running yet drops input quietly (the core latches nothing).
    private func reportInputError(_ error: Error) {
        if case PlayerEngineError.sessionNotPrepared = error { return }
        if errorMessage != error.localizedDescription { errorMessage = error.localizedDescription }
    }

    /// Clears a notice or error on the first press, without touching observed state otherwise.
    private func dismissTransientMessages() {
        if errorMessage != nil { errorMessage = nil }
        if noticeMessage != nil { noticeMessage = nil }
        if !primaryControlsVisible { bumpPrimaryControls() }
    }

    // Stylus input shares one ordered queue (a press is held long enough to be sampled).
    private var consoleInputTask: Task<Void, Never>?
    /// Controller buttons and sticks: immediate and ordered, like touch.
    func enqueueConsoleDigital(_ key: Int, pressed: Bool) { sendDigital(key, pressed: pressed) }
    func enqueueConsoleMotion(_ stick: Int, x: Double, y: Double) { sendMotion(stick, x: x, y: y) }

    #if canImport(GameController)
    @ObservationIgnored private var gamepadInput: PlayerGamepadInput?
    #endif

    private var pointerPressedAt: ContinuousClock.Instant?
    /// Stylus events share the ordered input queue, so a quick tap's release can
    /// never overtake its press; each press is held long enough for the core to
    /// sample it (about two frames).
    func enqueuePointer(normalizedX: Double, normalizedY: Double, pressed: Bool) {
        if pressed, pointerPressedAt == nil { pointerPressedAt = .now; dismissTransientMessages() }
        let pressStart = pressed ? nil : pointerPressedAt
        if !pressed { pointerPressedAt = nil }
        let previous = consoleInputTask
        consoleInputTask = Task { @MainActor in
            await previous?.value
            if let pressStart {
                let held = ContinuousClock.now - pressStart
                if held < .milliseconds(34) { try? await Task.sleep(for: .milliseconds(34) - held) }
            }
            do { try await engine.dispatchPointer(x: normalizedX * 2 - 1, y: normalizedY * 2 - 1, pressed: pressed, port: 0) }
            catch { errorMessage = error.localizedDescription }
        }
    }

    func dispatchPointer(normalizedX: Double, normalizedY: Double, pressed: Bool = true) async {
        bumpPrimaryControls()
        do {
            try await engine.dispatchPointer(x: normalizedX * 2 - 1, y: normalizedY * 2 - 1, pressed: pressed, port: 0)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func bumpPrimaryControls() {
        if !primaryControlsVisible { primaryControlsVisible = true }
        primaryFadeTask?.cancel()
        guard !pauseSheetPresented, !controlsSheetPresented,
              controlsState?.connectedControllers.isEmpty == false else { return }
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
        if !tertiaryControlsVisible { tertiaryControlsVisible = true }
        tertiaryFadeTask?.cancel()
        guard !pauseSheetPresented, !controlsSheetPresented,
              controlsState?.connectedControllers.isEmpty == false else { return }
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
            gameControls = (try? await services.playerControlsRepository.gameControls(romID: presentation.romID)) ?? PlayerGameControls()
            if let state = controlsState {
                engine.setAudioMuted(state.preferences.audioMuted)
                recomputeStage()
                try await engine.updateInputConfiguration(PlayerInputConfiguration(
                    deadzone: state.hardwareBinding.deadzone,
                    reserveTouchSpace: state.showTouchControls && state.platformProfile.supportTier == .touchSupported,
                    controlDeckHeight: ExpansionControls.deckHeight(ExpansionControls.controls(for: state.platformProfile.familyID) ?? []),
                    controlScale: state.touchLayout?.globalScale ?? 1,
                    controlUtilityCount: (ExpansionControls.controls(for: state.platformProfile.familyID) ?? []).filter(\.center).count + 1,
                    stage: stageContext,
                    controlFamily: state.platformProfile.familyID,
                    controlAnalog: analogControls))
            }
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
                self.bumpPrimaryControls()
                self.bumpTertiaryControls(wakePrimary: false)
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

    #if DEBUG && canImport(UIKit)
    func probeCapture() async throws -> NativeProgressCapture {
        guard let core = engine as? any ProgressPlayerEngine else { throw ProgressStoreError.invalid("Missing native progress engine") }
        return try await core.captureProgress(includeState: true)
    }
    #endif
    func deleteProgressSnapshot(_ id: String) async {
        do { try await progressOwner?.deleteSnapshot(checkpointID: id); await refreshProgress() }
        catch { errorMessage = error.localizedDescription }
    }
    func importProgress(_ file: URL, snapshot: Bool) async {
        let access = file.startAccessingSecurityScopedResource()
        defer { if access { file.stopAccessingSecurityScopedResource() } }
        do { noticeMessage = try await progressOwner?.importLegacy(file: file, snapshot: snapshot).message; isPaused = true; await refreshProgress() }
        catch { errorMessage = error.localizedDescription; isPaused = true }
    }
    func refreshProgress() async {
        guard let progressOwner else { return }
        do {
            progressHistory = try await progressOwner.history()
            progressReferences = try await progressOwner.references()
            progressSyncMessage = await services.progress.status(progressOwner.identity)
        } catch { errorMessage = error.localizedDescription }
        await describeProgress()
    }

    /// Decorates the history with names, previews, devices and sync (never fails the sheet).
    private func describeProgress() async {
        guard let progressOwner else { return }
        await services.playData.flush()
        progressSaves = (try? await services.savedProgress.describe(progressOwner.identity, history: progressHistory)) ?? []
    }

    /// Names a save state on this device; blank removes the name.
    func renameSave(_ entry: SaveEntry, to name: String) async {
        do {
            let label = try await services.savedProgress.rename(entry.key, to: name)
            progressSaves = progressSaves.map { $0.key == entry.key ? relabelled($0, label) : $0 }
        } catch { errorMessage = error.localizedDescription }
    }

    /// The game's cover, standing in for saves without a preview.
    var coverURL: URL? { backdropURL }
    func continueLocal(coldBoot: Bool = false) async {
        preferColdBoot = coldBoot; cloudMessage = nil
        await startIfNeeded()
    }
    func retryCloudCheck() async {
        guard let progressOwner else { return }
        // Explicit retry joins the application-owned drain. Repeating the bounded
        // launch check can time out forever once retained history grows.
        do {
            let result = try await services.progress.synchronize(progressOwner.identity)
            cloudMessage = result.errors.isEmpty ? nil : result.message
        } catch { cloudMessage = error.localizedDescription }
        await refreshProgress()
        if cloudMessage == nil { await startIfNeeded() }
    }
    func retryProgress() async {
        guard let progressOwner else { return }
        do { try await progressOwner.retry(); errorMessage = nil; noticeMessage = "Retained captures recovered."; await refreshProgress() }
        catch { errorMessage = error.localizedDescription }
    }
    func restoreProgress(_ id: String, confirmed: Bool = false) async {
        guard let progressOwner else { return }
        do {
            try await progressOwner.restore(checkpointID: id, confirmed: confirmed)
            pendingRestoreID = nil; isPaused = true; noticeMessage = "Restored progress. The safety checkpoint is retained."
            await refreshProgress()
        } catch let confirmation as ProgressRestoreConfirmation { pendingRestoreID = confirmation.checkpointID; isPaused = true }
        catch { errorMessage = error.localizedDescription; isPaused = true }
    }
    func resolveProgress(reference: String, selected: String, expected: [String]) async {
        guard let progressOwner else { return }
        do { try await progressOwner.resolve(reference: reference, selected: selected, expected: expected); await refreshProgress(); noticeMessage = "Choice saved. Running gameplay has not been replaced." }
        catch { errorMessage = error.localizedDescription }
    }
    func sceneBecameInactive() async {
        guard let progressOwner, hasStarted, !backgrounded else { return }
        backgrounded = true
        resumeAfterBackground = !isPaused
        do { noticeMessage = try await progressOwner.capture(reason: "background", remainPaused: true).message; if backgrounded { isPaused = true } }
        catch { errorMessage = error.localizedDescription; isPaused = true; resumeAfterBackground = false }
    }
    func sceneBecameActive() async {
        backgrounded = false
        if resumeAfterBackground { resumeAfterBackground = false; await resumeGame() }
        await refreshProgress()
    }

    private func currentProfile() async -> ServerProfile? {
        try? await services.profileStore.activeProfile()
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
        if progressOwner != nil { await refreshProgress(); return }
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
            await resumeGame()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func captureContinuity(syncManualSlots: Bool, sessionActive: Bool) async {
        if let progressOwner {
            guard hasStarted else { return }
            do { noticeMessage = try await progressOwner.capture(reason: "periodic", remainPaused: isPaused).message; await refreshProgress() }
            catch { errorMessage = error.localizedDescription; isPaused = true }
            return
        }
        guard presentation.supportsSaveStates else { return }
        do {
            try await captureContinuityLocally()
            if let profile = await currentProfile() {
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
        } catch is LegacySyncUnavailableError {
            syncPresentation = GameSyncPresentation(kind: .localOnly, message: legacySyncPausedMessage)
            await refreshStateBrowser()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func captureContinuityLocally() async throws {
        guard presentation.supportsSaveStates else { return }
        _ = try await engine.persistSaveRAM()
        _ = try await engine.saveState(to: services.libraryStore.continuityResumeStateURL(for: installation))
    }

    func capturePeriodicProgressIfActive() async {
        // A paused device must not create a new branch after another device takes over.
        guard hasStarted, !isPaused, !backgrounded else { return }
        await captureContinuity(syncManualSlots: false, sessionActive: true)
    }

    private func scheduleContinuitySync() {
        continuitySyncTask?.cancel()
        guard presentation.supportsSaveStates else { return }
        continuitySyncTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(120))
                guard !Task.isCancelled else { return }
                await self.capturePeriodicProgressIfActive()
            }
        }
    }
}

private extension RommioServices {
    var profileStoreActiveCache: ServerProfile? {
        nil
    }
}
