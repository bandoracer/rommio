import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

extension CoreInventoryEntry {
    var isShippedInBuild: Bool {
        availabilityStatus != .missingBundledCore && availabilityStatus != .unsupportedByCurrentBuild
            && provisioningStatus != .missingCoreNotShipped && provisioningStatus != .unsupported
    }

    var statusLabel: String {
        switch provisioningStatus {
        case .ready: "Ready"
        case .missingCoreInstallable: "Sets up on first play"
        case .installingCore: "Setting up"
        case .failedCoreInstall: "Setup failed"
        case .missingBIOS: "Needs BIOS"
        case .missingCoreNotShipped: "Not included"
        case .unsupported: "Unsupported"
        }
    }

    var statusTone: StatusTone {
        switch provisioningStatus {
        case .ready: .ok
        case .missingCoreInstallable, .installingCore: .live
        case .failedCoreInstall: .bad
        case .missingBIOS: .wait
        case .missingCoreNotShipped, .unsupported: .neutral
        }
    }

    var interactionProfileLabel: String {
        interactionProfile.rawValue.replacingOccurrences(of: "_", with: " ").capitalized
    }
}

/// One emulator core and whether it's ready.
struct RuntimeInventoryRow: View {
    let entry: CoreInventoryEntry

    var body: some View {
        ListRow(title: entry.familyName, subtitle: "\(entry.runtimeName) · \(entry.interactionProfileLabel)", trailing: {
            StatusChip(title: entry.statusLabel, tone: entry.statusTone)
        })
    }
}
