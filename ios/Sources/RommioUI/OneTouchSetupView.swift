import SwiftUI
import RommioFoundation
#if canImport(UIKit)
import UIKit
import SafariServices
import CoreImage.CIFilterBuiltins
#endif

struct OneTouchSetupView: View {
    let appModel: RommioAppModel
    @State private var setup = OneTouchSetupModel()
    @State private var showsApproval = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    Image(systemName: "gamecontroller.fill")
                        .font(.system(size: 36)).foregroundStyle(.tint).accessibilityHidden(true)
                    Text("Set up this device").font(.title.bold())
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
                        Text(error).foregroundStyle(.red).multilineTextAlignment(.center)
                            .accessibilityIdentifier("oneTouch.error")
                        Button("Try Again") { setup.cancel(); start() }
                            .buttonStyle(.borderedProminent).accessibilityIdentifier("oneTouch.retry")
                    } else {
                        ProgressView(setup.isApplying ? "Connecting to RomM…" : setup.pairing == nil ? "Creating setup code…" : "Waiting for approval…")
                    }
                }
                .padding(24).frame(maxWidth: 500).frame(maxWidth: .infinity)
            }
            .navigationTitle("One-touch setup")
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
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Text(pairing.code).font(.system(size: 32, weight: .bold, design: .monospaced))
                .textSelection(.enabled).accessibilityIdentifier("oneTouch.code")
            #if canImport(UIKit)
            Button {
                showsApproval = true
            } label: {
                Label("Approve on This Device", systemImage: "safari")
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
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
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("Scan on another device to approve setup")
            }
            #endif
            Text("Or scan the code on another device.").font(.footnote).foregroundStyle(.secondary)
            Link(pairing.approveUrl.absoluteString, destination: pairing.approveUrl)
                .font(.caption).multilineTextAlignment(.center)
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

#if canImport(UIKit)
private struct SetupApprovalBrowser: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
#endif
