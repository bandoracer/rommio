import SwiftUI
import UniformTypeIdentifiers
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Settings sub-pages, pushed from the Settings root.
enum SettingsDestination: Hashable {
    case profiles
    case player
    case cores
    case backup
}

/// Settings: account and sync at the top, then Appearance, the sub-pages,
/// storage, and signing out. One layout; the components carry the theme (glass
/// groups, or paper rows between rules).
struct SettingsPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: SettingsFeatureModel

    var body: some View {
        NavigationStack {
            ThemedScrollPage(style: .quiet, maxWidth: 760) {
                AccountCard(feature: feature)
                AppearanceSection()
                ThemedGroup(title: "Rommio", inset: 58) {
                    NavigationRow(value: SettingsDestination.profiles, title: "Profiles and servers",
                                  subtitle: feature.profiles.count == 1 ? "1 server" : "\(feature.profiles.count) servers") {
                        IconTile(systemImage: "server.rack", tone: .live)
                    }
                    .accessibilityIdentifier("settings.profiles")
                    NavigationRow(value: SettingsDestination.player, title: "Player and controls", subtitle: "Touch controls, controllers, display") {
                        IconTile(systemImage: "gamecontroller.fill", tone: .ok)
                    }
                    .accessibilityIdentifier("settings.player")
                    NavigationRow(value: SettingsDestination.cores, title: "Cores and BIOS", subtitle: coresSubtitle) {
                        IconTile(systemImage: "cpu.fill", tone: .wait)
                    }
                    .accessibilityIdentifier("settings.cores")
                    NavigationRow(value: SettingsDestination.backup, title: "Backup and recovery", subtitle: "Game saves, resume points and recovery") {
                        IconTile(systemImage: "externaldrive.fill.badge.timemachine", tone: .bad)
                    }
                    .accessibilityIdentifier("settings.backup")
                }
                ThemedGroup(title: "Storage") {
                    ValueRow(title: "Installed games", value: PlayFormat.count(feature.storageSummary.installedGameCount))
                    ValueRow(title: "Installed files", value: PlayFormat.count(feature.storageSummary.installedFileCount))
                    ValueRow(title: "Library on this device", value: PlayFormat.bytes(feature.storageSummary.totalBytes))
                    ValueRow(title: "Catalog cache", value: PlayFormat.bytes(feature.storageSummary.cacheBytes))
                }
                if let staleMessage = feature.staleMessage,
                   feature.activeProfile != nil || !feature.profiles.isEmpty {
                    ThemedGroup(title: "Showing saved settings") {
                        Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                    }
                }
                ThemedGroup(title: "Account") {
                    Button {
                        Task { await feature.reauthenticate() }
                    } label: {
                        Text("Sign in again").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.readableTint)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(RowButtonStyle())
                    Button(role: .destructive) {
                        Task { await feature.signOut() }
                    } label: {
                        Text("Sign out").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.badText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("settings.signOut")
                }
            }
            .navigationTitle("Settings")
            .accessibilityIdentifier("shell.settingsList")
            .navigationDestination(for: SettingsDestination.self) { destination in
                switch destination {
                case .profiles:
                    ProfilesSettingsPage(feature: feature)
                case .player:
                    PlayerSettingsPage(feature: feature)
                case .cores:
                    CoresSettingsPage(model: model, feature: feature)
                case .backup:
                    ProgressRecoveryView(service: feature.progressService)
                }
            }
            .task { await feature.refresh() }
        }
    }

    private var coresSubtitle: String {
        let ready = feature.runtimeInventory.filter { $0.provisioningStatus == .ready }.count
        let needsBIOS = feature.runtimeInventory.filter { $0.provisioningStatus == .missingBIOS }.count
        return needsBIOS > 0 ? "\(ready) ready · \(needsBIOS) need BIOS" : "\(ready) ready"
    }
}

/// The active server, its connection and when the library last synced. Aurora:
/// a glass card with an avatar in the page's light. Riso: an imprint between
/// heavy rules, the server's name set as a headline.
private struct AccountCard: View {
    @Environment(\.rommioTheme) private var theme
    let feature: SettingsFeatureModel

    var body: some View {
        Group {
            if theme.isRiso { imprint } else { card }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("settings.account")
    }

    private var syncLine: String? {
        feature.offlineState.lastFullSyncAtEpochMS.map {
            "Library synced \(PlayFormat.inline(Date(timeIntervalSince1970: TimeInterval($0) / 1000)))"
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Text(initial)
                    .themeText(.headline)
                    .foregroundStyle(theme.markInk)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(LinearGradient(colors: [theme.mark1, theme.mark2], startPoint: .topLeading, endPoint: .bottomTrailing)))
                    .overlay { Circle().strokeBorder(theme.glassRim.opacity(0.6), lineWidth: 2).padding(-3) }
                    .padding(3)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(feature.activeProfile?.label ?? "No active server")
                        .themeText(.headline)
                        .foregroundStyle(theme.ink)
                    Text(feature.activeProfile?.baseURL.host() ?? "Set up a server to keep using Rommio.")
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                        .lineLimit(1)
                }
            }
            FlowRow(spacing: 8) {
                if feature.offlineState.connectivity == .offline {
                    StatusChip(title: "Offline", tone: .wait, systemImage: "wifi.slash")
                } else {
                    StatusChip(title: "Online", tone: .ok)
                }
                StatusChip(title: feature.offlineState.catalogReady ? "Ready offline" : "Library syncing",
                           tone: feature.offlineState.catalogReady ? .ok : .live)
            }
            if let syncLine {
                Text(syncLine).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .themeSurface()
    }

    private var imprint: some View {
        VStack(spacing: 8) {
            Text("Server").themeText(.label, size: 10).foregroundStyle(theme.inkSecondary)
            DisplayText(text: feature.activeProfile?.label ?? "No active server", role: .display, size: 44, plateOffset: 1.25, lineLimit: 2)
                .multilineTextAlignment(.center)
            Text(feature.activeProfile?.baseURL.host() ?? "Set up a server to keep using Rommio.")
                .themeText(.body, size: 15)
                .italic()
                .foregroundStyle(theme.ink)
            HStack(spacing: 8) {
                StatusChip(title: feature.offlineState.connectivity == .offline ? "Offline" : "Online",
                           tone: feature.offlineState.connectivity == .offline ? .wait : .ok)
                StatusChip(title: feature.offlineState.catalogReady ? "Ready offline" : "Library syncing",
                           tone: feature.offlineState.catalogReady ? .ok : .live)
            }
            if let syncLine {
                Text(syncLine).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
    }

    private var initial: String {
        String((feature.activeProfile?.label ?? "R").prefix(1)).uppercased()
    }
}

/// Settings › Appearance, on the Settings root: a live preview, Theme and
/// Appearance. Device-local; not part of backups.
private struct AppearanceSection: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(AppearanceStore.self) private var appearance

    var body: some View {
        @Bindable var appearance = appearance
        ThemedGroup(title: "Appearance", footer: "Saved on this device only. Backups don't include it.") {
            AppearancePreview()
            VStack(alignment: .leading, spacing: 8) {
                Text("Theme").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                Picker("Theme", selection: $appearance.theme.animation(.smooth)) {
                    ForEach(AppearanceTheme.allCases) { option in
                        Text(option.title).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("appearance.theme")
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Appearance").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                Picker("Appearance", selection: $appearance.scheme.animation(.smooth)) {
                    ForEach(AppearanceScheme.allCases) { scheme in
                        Text(scheme.title).tag(scheme)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("appearance.scheme")
            }
        }
    }
}

/// A small live preview of the current theme and appearance: the page ground,
/// a cover, a title, a sync badge and the main button.
private struct AppearancePreview: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        ZStack(alignment: .topLeading) {
            PageBackground(style: .welcome)
            HStack(alignment: .center, spacing: 14) {
                ZStack {
                    if theme.isRiso {
                        theme.accent2
                        HalftoneField(dot: theme.accent1, pitch: 6)
                    } else {
                        LinearGradient(colors: [theme.accent1, theme.accent2, theme.accent3], startPoint: .topLeading, endPoint: .bottomTrailing)
                    }
                }
                .frame(width: 70, height: 94)
                .clipShape(RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 12, style: .continuous)
                        .strokeBorder(theme.isRiso ? theme.ink : theme.glassRim.opacity(0.4), lineWidth: 1)
                }
                VStack(alignment: .leading, spacing: 7) {
                    Text("Continue playing").themeText(.section, size: theme.isRiso ? 12 : 10.5).foregroundStyle(theme.inkSecondary)
                    DisplayText(text: "Super Metroid", role: theme.isRiso ? .display : .headline, size: theme.isRiso ? 26 : 20, lineLimit: 1)
                    StatusChip(title: "Synced", tone: .ok)
                    Label("Resume", systemImage: "play.fill")
                        .themeText(.button, size: theme.isRiso ? 14 : 13)
                        .foregroundStyle(theme.buttonInk)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 30)
                        .background(RoundedRectangle(cornerRadius: theme.radius.button, style: .continuous).fill(theme.buttonFill))
                        .background {
                            if theme.isRiso { theme.accent1.offset(x: 3, y: 3) }
                        }
                }
                Spacer(minLength: 0)
            }
            .padding(14)
        }
        .frame(height: 138)
        .clipShape(RoundedRectangle(cornerRadius: theme.isGlass ? 16 : 0, style: .continuous))
        .overlay {
            if theme.isRiso { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Preview of \(theme.tokens.name), \(theme.isDark ? "dark" : "light")")
        .accessibilityIdentifier("appearance.preview")
    }
}

/// Settings › Profiles and servers.
struct ProfilesSettingsPage: View {
    @Environment(\.rommioTheme) private var theme
    let feature: SettingsFeatureModel
    @State private var profilePendingRemoval: ServerProfile?

    var body: some View {
        ThemedScrollPage(maxWidth: 760) {
            ThemedGroup(title: "Servers", detail: "\(feature.profiles.count)", inset: 58) {
                ForEach(feature.profiles) { profile in
                    VStack(alignment: .leading, spacing: 10) {
                        ListRow(title: profile.label, subtitle: profile.baseURL.absoluteString, leading: {
                            IconTile(systemImage: "server.rack", tone: profile.id == feature.activeProfile?.id ? .ok : .neutral)
                        }, trailing: {
                            if profile.id == feature.activeProfile?.id {
                                StatusChip(title: "Active", tone: .ok)
                            }
                        })
                        HStack(spacing: 10) {
                            if profile.id != feature.activeProfile?.id {
                                Button("Switch to this server") {
                                    Task { await feature.activateProfile(profile) }
                                }
                            }
                            Button("Remove…", role: .destructive) {
                                profilePendingRemoval = profile
                            }
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                }
            }
            if let status = feature.activeProfile?.status {
                ThemedGroup(title: "This server") {
                    ValueRow(title: "Status", value: status.rawValue.replacingOccurrences(of: "_", with: " ").capitalized)
                    Button {
                        Task { await feature.reconfigureServerAccess() }
                    } label: {
                        Text("Set up server access again").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.readableTint)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(RowButtonStyle())
                }
            }
        }
        .navigationTitle("Profiles and servers")
        .accessibilityIdentifier("screen.profiles")
        .confirmationDialog(
            "Remove \(profilePendingRemoval?.label ?? "server")?",
            isPresented: Binding(get: { profilePendingRemoval != nil }, set: { if !$0 { profilePendingRemoval = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove server", role: .destructive) {
                if let profile = profilePendingRemoval {
                    Task { await feature.deleteProfile(profile) }
                }
                profilePendingRemoval = nil
            }
        } message: {
            Text(profilePendingRemoval?.id == feature.activeProfile?.id
                ? "This is the active server. You'll need to set up server access again."
                : "Saved connection details for this server will be removed.")
        }
    }
}

/// Settings › Player and controls.
struct PlayerSettingsPage: View {
    let feature: SettingsFeatureModel

    var body: some View {
        ThemedScrollPage(maxWidth: 760) {
            ThemedGroup(title: "Touch controls") {
                ToggleRow(title: "Show touch controls", isOn: Binding(
                    get: { feature.preferences.touchControlsEnabled },
                    set: { value in Task { await feature.setTouchControlsEnabled(value) } }
                ))
                ToggleRow(title: "Hide touch controls when a controller connects", isOn: Binding(
                    get: { feature.preferences.autoHideTouchOnController },
                    set: { value in Task { await feature.setAutoHideTouchOnController(value) } }
                ))
            }
            ThemedGroup(title: "Display",
                        footer: "Console colors give face buttons their original colors. OLED black turns the player's background fully black.") {
                ToggleRow(title: "Console colors", isOn: Binding(
                    get: { feature.preferences.consoleColorsEnabled },
                    set: { value in Task { await feature.setConsoleColorsEnabled(value) } }
                ))
                ToggleRow(title: "OLED black", isOn: Binding(
                    get: { feature.preferences.oledBlackModeEnabled },
                    set: { value in Task { await feature.setOLEDBlackModeEnabled(value) } }
                ))
            }
            ThemedGroup(title: "Controllers") {
                ToggleRow(title: "Vibrate this device for rumble", isOn: Binding(
                    get: { feature.preferences.rumbleToDeviceEnabled },
                    set: { value in Task { await feature.setRumbleToDeviceEnabled(value) } }
                ))
            }
        }
        .navigationTitle("Player and controls")
        .accessibilityIdentifier("screen.playerSettings")
    }
}

/// Settings › Cores and BIOS: firmware import and the emulators in this build.
struct CoresSettingsPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: SettingsFeatureModel
    @State private var selectingBIOS = false
    @State private var biosMessage: String?
    @State private var showsUnavailable = false

    var body: some View {
        ThemedScrollPage(maxWidth: 760) {
            ThemedGroup(title: "BIOS",
                        footer: "PlayStation needs scph5501.bin. Sega CD needs the US, Europe and Japan BIOS files. Import your own copies; Rommio checks and names them.") {
                Button {
                    selectingBIOS = true
                } label: {
                    Label("Import BIOS files", systemImage: "square.and.arrow.down")
                        .themeText(.bodyEmphasis, size: 16)
                        .foregroundStyle(theme.readableTint)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("settings.importBIOS")
                if let biosMessage {
                    Text(biosMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
            }
            let shipped = feature.runtimeInventory.filter(\.isShippedInBuild)
            let unavailable = feature.runtimeInventory.filter { !$0.isShippedInBuild }
            ThemedGroup(title: "Cores", detail: "\(shipped.count)") {
                ForEach(shipped, id: \.id) { entry in
                    RuntimeInventoryRow(entry: entry)
                }
                if !unavailable.isEmpty {
                    DisclosureGroup(isExpanded: $showsUnavailable) {
                        VStack(spacing: 0) {
                            ForEach(unavailable, id: \.id) { entry in
                                RuntimeInventoryRow(entry: entry).padding(.vertical, 6)
                            }
                        }
                    } label: {
                        Text("Not included in this build (\(unavailable.count))")
                            .themeText(.bodyEmphasis, size: 16)
                            .foregroundStyle(theme.ink)
                    }
                    .tint(theme.inkSecondary)
                }
            }
        }
        .navigationTitle("Cores and BIOS")
        .accessibilityIdentifier("screen.cores")
        .fileImporter(isPresented: $selectingBIOS, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
            do {
                let urls = try result.get()
                var messages: [String] = []
                for url in urls {
                    do { messages.append("Imported " + (try model.importBIOS(url))) }
                    catch { messages.append("\(url.lastPathComponent): \(error.localizedDescription)") }
                }
                biosMessage = messages.joined(separator: "\n")
                Task { await feature.refresh() }
            } catch { biosMessage = error.localizedDescription }
        }
    }
}
