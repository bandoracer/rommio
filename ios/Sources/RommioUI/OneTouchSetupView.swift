import SwiftUI
import RommioFoundation
#if canImport(UIKit)
import UIKit
import SafariServices
import CoreImage.CIFilterBuiltins
#endif

struct OneTouchSetupView: View {
    @Environment(\.rommioTheme) private var theme
    let appModel: RommioAppModel
    @State private var setup = OneTouchSetupModel(service: SetupCodeService.current())
    @State private var showsApproval = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    AppMark(size: theme.isRiso ? 16 : 36)
                        .accessibilityHidden(true)
                    DisplayText(text: "Set up this device", role: theme.isRiso ? .display : .title, size: theme.isRiso ? 40 : 30, lineLimit: 2)
                        .multilineTextAlignment(.center)
                    if let pairing = setup.pairing {
                        // Short, wide sheets (iPhone Duo open) put the QR code beside
                        // the code instead of below the visible area.
                        ViewThatFits(in: .vertical) {
                            VStack(spacing: 22) {
                                codeColumn(pairing)
                                qrColumn(pairing)
                            }
                            HStack(alignment: .center, spacing: 28) {
                                codeColumn(pairing)
                                qrColumn(pairing)
                            }
                        }
                    }
                    if let error = setup.errorMessage {
                        Text(error).themeText(.secondary).foregroundStyle(theme.badText).multilineTextAlignment(.center)
                            .accessibilityIdentifier("oneTouch.error")
                        Button("Try Again") { setup.cancel(); start() }
                            .buttonStyle(PrimaryButtonStyle(fullWidth: false)).accessibilityIdentifier("oneTouch.retry")
                    } else {
                        ProgressView(setup.isApplying ? "Connecting to RomM…" : setup.pairing == nil ? "Creating setup code…" : "Waiting for approval…")
                            .themeText(.secondary)
                            .foregroundStyle(theme.inkSecondary)
                    }
                }
                .padding(24).frame(maxWidth: 500).frame(maxWidth: .infinity)
            }
            .themedPage(.welcome)
            .navigationTitle("Setup code")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { setup.cancel(); dismiss() }.disabled(setup.isApplying)
                }
            }
        }
        .interactiveDismissDisabled(setup.isApplying)
        .task { start() }
        .onDisappear { setup.cancel() }
        .onChange(of: setup.isApproved) { _, approved in
            if approved { showsApproval = false; dismiss() }
        }
        #if canImport(UIKit)
        .sheet(isPresented: $showsApproval) {
            if let url = setup.pairing?.approveUrl { SetupApprovalBrowser(url: url).ignoresSafeArea() }
        }
        #endif
    }

    @ViewBuilder
    private func codeColumn(_ pairing: SetupPairing) -> some View {
        VStack(spacing: 18) {
            Text("Approve access to your server. Your connection settings will be added automatically.")
                .themeText(.body)
                .multilineTextAlignment(.center).foregroundStyle(theme.inkSecondary)
            Text(pairing.code).themeText(.numeral, size: 32).foregroundStyle(theme.ink)
                .textSelection(.enabled).accessibilityIdentifier("oneTouch.code")
            #if canImport(UIKit)
            Button {
                showsApproval = true
            } label: {
                Label("Approve on This Device", systemImage: "safari")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(setup.isApplying || setup.errorMessage != nil)
            .accessibilityIdentifier("oneTouch.approve")
            #else
            Link("Approve on This Device", destination: pairing.approveUrl)
            #endif
        }
    }

    @ViewBuilder
    private func qrColumn(_ pairing: SetupPairing) -> some View {
        VStack(spacing: 12) {
            #if canImport(UIKit)
            if let qr = qrImage(pairing.approveUrl.absoluteString) {
                Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                    .frame(width: 170, height: 170).padding(12).background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 12))
                    .overlay { if theme.isRiso { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) } }
                    .accessibilityLabel("Scan on another device to approve setup")
            }
            #endif
            Text("Or scan the code on another device.").themeText(.caption).foregroundStyle(theme.inkSecondary)
            Link(pairing.approveUrl.absoluteString, destination: pairing.approveUrl)
                .themeText(.caption).multilineTextAlignment(.center)
        }
    }

    private func start() {
        #if canImport(UIKit)
        let name = UIDevice.current.name
        #else
        let name = "Rommio"
        #endif
        setup.start(deviceName: name) { configuration in
            try await appModel.applyOneTouchConfiguration(configuration)
        }
    }

    #if canImport(UIKit)
    private func qrImage(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
                                                   from: output.extent.applying(CGAffineTransform(scaleX: 8, y: 8))) else { return nil }
        return UIImage(cgImage: image)
    }
    #endif
}

/// The setup-code service: the real pairing service, or in a Debug build with
/// `ROMMIO_SETUP_STUB=1` a fixed code that stays pending and makes no request
/// (screenshots against the mock server; the production service is never called).
enum SetupCodeService {
    static func current() -> any OneTouchSetupServing {
        #if DEBUG
        if ProcessInfo.processInfo.environment["ROMMIO_SETUP_STUB"] == "1" { return Stub() }
        #endif
        return OneTouchSetupClient()
    }

    #if DEBUG
    private struct Stub: OneTouchSetupServing {
        func start(deviceName: String) async throws -> SetupPairing {
            let json = #"{"code":"RMX4K7","secret":"STUBSTUBSTUBSTUBSTUBSTUBSTUBSTUB","approveUrl":"https://rommio-setup.borchetta.xyz/a/RMX4K7","expiresInSeconds":600,"pollIntervalSeconds":30}"#
            return try JSONDecoder().decode(SetupPairing.self, from: Data(json.utf8))
        }
        func poll(_ pairing: SetupPairing) async throws -> SetupPollResult { .pending }
        func complete(_ pairing: SetupPairing) async throws {}
        func cancel(_ pairing: SetupPairing) async {}
    }
    #endif
}

#if canImport(UIKit)
private struct SetupApprovalBrowser: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
#endif
