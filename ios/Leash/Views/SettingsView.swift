import SwiftUI
import VisionKit

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var host = ""
    @State private var port = ""
    @State private var token = ""
    @State private var scanning = false
    @State private var pasteError: String?
    @AppStorage("haptics") private var haptics = true
    @AppStorage("liveActivities") private var liveActivities = true

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if DataScannerViewController.isSupported {
                        Button { scanning = true } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "qrcode.viewfinder")
                                Text("Scan pairing QR code")
                            }
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Color.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                        }
                        .buttonStyle(.glassProminent)
                        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                    }
                    Button { pasteLink() } label: { Label("Paste pairing link", systemImage: "doc.on.clipboard") }
                    if let pasteError { Text(pasteError).font(.caption).foregroundStyle(.red) }
                } header: { Text("Pair") } footer: {
                    Text("On the Mac run  node ~/workspace/Leash/bridge/src/index.mjs pair")
                }

                Section("Mac") {
                    LabeledContent("Address") {
                        TextField("Tailscale name or IP", text: $host)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Port") {
                        TextField("8787", text: $port).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Token") {
                        SecureField("Token", text: $token)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .multilineTextAlignment(.trailing)
                    }
                    Button("Save & connect") {
                        model.host = host.trimmingCharacters(in: .whitespaces)
                        model.port = Int(port) ?? 8787
                        model.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
                        if model.macName.isEmpty { model.macName = model.host }
                        model.reconfigure()
                    }
                    .disabled(host.isEmpty || token.isEmpty)
                }

                Section {
                    Toggle("Haptics", isOn: $haptics)
                } header: { Text("Feedback") } footer: {
                    Text("Subtle taps for sending, stopping, approvals and finished tasks. Also follows Settings › Sounds & Haptics.")
                }

                Section {
                    Toggle("Live Activity", isOn: $liveActivities)
                    LabeledContent("Apple Watch") {
                        Text(WatchRelay.shared.watchAppInstalled ? "Leash installed" : "Not installed")
                    }
                } header: { Text("Lock Screen & Watch") } footer: {
                    Text("A conversation you start here shows on the Lock Screen, in the Dynamic Island and on Apple Watch while the agent works. It updates while Leash is running and says when it may be out of date. The watch app reaches your Mac through this iPhone.")
                }
                .onChange(of: liveActivities) { _, on in if !on { model.liveActivity.stopAll() } }

                Section("Status") {
                    LabeledContent("Connection") { ConnectionPill() }
                    ForEach(model.agents) { a in
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 8) {
                                AgentIcon(agent: a.id, size: 16)
                                Text(verbatim: a.label)
                                Spacer()
                                Text(verbatim: a.state).foregroundStyle(a.isReady ? Theme.success : Theme.attention)
                            }
                            if let n = a.name { Text(verbatim: n).font(.caption).foregroundStyle(Theme.textTertiary) }
                            if let e = a.error { Text(verbatim: e).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(3) }
                        }
                        .swipeActions { Button("Restart") { model.restartAgent(a.id) }.tint(Theme.attention) }
                    }
                    if model.agents.isEmpty {
                        LabeledContent("Agent", value: model.agentState)
                        if let e = model.agentError { Text(e).font(.caption).foregroundStyle(.secondary) }
                    }
                    if case .waiting(let w) = model.connection { Text(w).font(.caption).foregroundStyle(.secondary) }
                    if case .failed(let f) = model.connection { Text(f).font(.caption).foregroundStyle(.red) }
                    Button("Reconnect") { model.reconfigure() }.disabled(!model.isConfigured)
                    ForEach(model.agents) { a in
                        Button("Restart \(a.label)") { model.restartAgent(a.id) }.disabled(!model.isConnected)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { host = model.host; port = String(model.port); token = model.token }
            .sheet(isPresented: $scanning) {
                QRScanner { code in
                    scanning = false
                    if let url = URL(string: code), model.pair(with: url) {
                        host = model.host; port = String(model.port); token = model.token
                        Haptics.success()
                    } else { pasteError = String(localized: "That QR code is not a Leash pairing code"); Haptics.error() }
                }
                .ignoresSafeArea()
            }
        }
    }

    private func pasteLink() {
        guard let s = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines), let url = URL(string: s), model.pair(with: url) else {
            pasteError = String(localized: "Clipboard does not contain a leash:// link"); Haptics.error(); return
        }
        pasteError = nil
        host = model.host; port = String(model.port); token = model.token
        Haptics.success()
    }
}

struct QRScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
                                           isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {
        if !vc.isScanning && !context.coordinator.done { try? vc.startScanning() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }
        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let b) = item, let s = b.payloadStringValue {
                    done = true
                    dataScanner.stopScanning()
                    onCode(s)
                    return
                }
            }
        }
    }
}
