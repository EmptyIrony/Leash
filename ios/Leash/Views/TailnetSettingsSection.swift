import SwiftUI

/// Settings › Built-in Tailscale: Leash joins the tailnet itself, for the bridge connection only.
struct TailnetSettingsSection: View {
    @Environment(AppModel.self) private var model
    @State private var key = ""
    @State private var url = ""
    @State private var showAdvanced = false
    @State private var confirmLogout = false

    private var tailnet: TailnetController { model.tailnet }

    var body: some View {
        Section {
            Toggle("Use built-in Tailscale", isOn: Binding(
                get: { tailnet.enabled },
                set: { on in
                    tailnet.enabled = on
                    model.reconfigure()
                }
            ))
            .disabled(!TailnetNode.isAvailable)

            if tailnet.enabled {
                LabeledContent("Auth key") {
                    SecureField("tskey-auth-…", text: $key)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .multilineTextAlignment(.trailing)
                }
                DisclosureGroup("Advanced", isExpanded: $showAdvanced) {
                    LabeledContent("Control URL") {
                        TextField("Tailscale", text: $url)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                            .multilineTextAlignment(.trailing)
                    }
                }
                LabeledContent("Status") { statusLabel }
                Button("Save & connect") {
                    tailnet.authKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
                    tailnet.controlURL = url.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.reconfigure()
                }
                Button("Log out", role: .destructive) { confirmLogout = true }
                    .confirmationDialog("Log out of the tailnet?", isPresented: $confirmLogout, titleVisibility: .visible) {
                        Button("Log out", role: .destructive) {
                            Task {
                                await tailnet.logout()
                                key = ""
                                model.reconfigure()
                            }
                        }
                    } message: {
                        Text("Leash leaves the tailnet and deletes its saved key. Turning this back on needs a new auth key.")
                    }
            }
        } header: {
            Text("Built-in Tailscale")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if TailnetNode.isAvailable {
                    Text("Leash joins your tailnet on its own, without the system VPN, so it works while another VPN is on. Only the connection to the bridge uses it, and only while Leash is running. Create an auth key in the Tailscale admin console, and use the bridge's Tailscale IP or MagicDNS name as its address.")
                } else {
                    Text("This build does not include Tailscale. Build the Tailnet framework with tailnet/build-ios.sh, then build Leash again.")
                }
                if let note = tailnet.logoutNote {
                    Text(verbatim: note).foregroundStyle(Theme.danger)
                }
            }
        }
        .onAppear {
            key = tailnet.authKey
            url = tailnet.controlURL
            showAdvanced = !url.isEmpty
        }
    }

    @ViewBuilder
    private var statusLabel: some View {
        switch tailnet.status {
        case .off, .stopped:
            Text("Not connected").foregroundStyle(.secondary)
        case .connecting:
            Text("Connecting…").foregroundStyle(Theme.running)
        case .connected(let name, let address):
            VStack(alignment: .trailing, spacing: 2) {
                Text("Connected").foregroundStyle(Theme.success)
                Text(verbatim: [name, address].filter { !$0.isEmpty }.joined(separator: " "))
                    .font(Theme.monoSmall).foregroundStyle(Theme.textTertiary)
            }
            .multilineTextAlignment(.trailing)
        case .needsKey(let reason):
            VStack(alignment: .trailing, spacing: 2) {
                Text("Needs a new auth key").foregroundStyle(Theme.attention)
                if let reason { Text(verbatim: reason).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(3) }
            }
            .multilineTextAlignment(.trailing)
        case .failed(let message):
            VStack(alignment: .trailing, spacing: 2) {
                Text("Error").foregroundStyle(Theme.danger)
                Text(verbatim: message).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(3)
            }
            .multilineTextAlignment(.trailing)
        }
    }
}
