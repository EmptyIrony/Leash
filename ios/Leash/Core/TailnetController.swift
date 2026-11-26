import Foundation
import Observation

/// Built-in Tailscale: Leash joins the tailnet itself (Tailnet.xcframework, tsnet in userspace, no VPN slot) and
/// only its connection to the bridge goes through it. BridgeConnection asks `proxyPort` on every attempt for the
/// loopback port that reaches the paired host through the tailnet; this class starts the node when that happens
/// and AppModel stops it when Leash has no reason to run (not on screen, no Live Activity keep-alive, no work).
///
/// The auth key and the control URL live in the Keychain and are never logged; the node's identity lives in
/// Application Support/Tailnet (excluded from backups), so the key is only needed until the node is registered.
@Observable
@MainActor
final class TailnetController {
    enum Status: Equatable {
        /// Switched off, or this build has no Tailnet framework.
        case off
        /// Switched on, not running right now (not started yet, or stopped while Leash idles in the background).
        case stopped
        case connecting
        case connected(name: String, address: String)
        /// The key was rejected or expired, or there is none and no saved node; control's reason when it gave one.
        case needsKey(String?)
        case failed(String)
    }

    /// The Settings switch. Always false in a build without the framework.
    var enabled: Bool {
        get { requested && TailnetNode.isAvailable }
        set { requested = newValue; if newValue { logoutNote = nil } }
    }
    private var requested: Bool { didSet { UserDefaults.standard.set(requested, forKey: "tailnetEnabled") } }
    /// Tailscale auth key (tskey-auth-…). Not kept in UserDefaults if the Keychain refuses it.
    var authKey: String { didSet { Keychain.set("tailnetAuthKey", authKey, fallback: false) } }
    /// Empty for Tailscale's own control server; a Headscale URL otherwise.
    var controlURL: String { didSet { Keychain.set("tailnetControlURL", controlURL) } }
    private(set) var status: Status = .off
    /// Control could not be told about a log out (the local state is gone anyway).
    private(set) var logoutNote: String?

    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var desired = false
    @ObservationIgnored private var running = false
    @ObservationIgnored private var runningFingerprint: String?
    @ObservationIgnored private var connecting: (id: Int, task: Task<Void, Error>)?
    @ObservationIgnored private var nextTaskId = 0
    @ObservationIgnored private var monitor: Task<Void, Never>?

    private static let hostname = "leash-ios"

    init() {
        requested = UserDefaults.standard.bool(forKey: "tailnetEnabled")
        authKey = Keychain.get("tailnetAuthKey", fallback: false) ?? ""
        controlURL = Keychain.get("tailnetControlURL") ?? ""
        if enabled { status = .stopped }
    }

    private var trimmedKey: String { authKey.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedURL: String { controlURL.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var fingerprint: String { trimmedKey + "\n" + trimmedURL }

    // MARK: routing

    /// The port on 127.0.0.1 that reaches `host:port` through the tailnet, starting the node if needed; nil when
    /// built-in Tailscale is off (connect directly). Throws a TunnelError: `retry` false when only the user can fix it.
    func proxyPort(host: String, port: Int) async throws -> Int? {
        guard enabled else { return nil }
        try await bringUp()
        do { return try await TailnetNode.proxyPort(host: host, port: port) }
        catch { throw TunnelError(message: error.localizedDescription, retry: true) }
    }

    /// Starts the node without a target (Settings, to show the status before a bridge is paired).
    func warmUp() {
        guard enabled, connecting == nil else { return }
        Task { try? await bringUp() }
    }

    /// Settings changed: stop the node when it was switched off or its key or control URL changed; the next
    /// connection attempt starts it again.
    func configChanged() {
        if !enabled || runningFingerprint != fingerprint { stop() }
    }

    /// Leaves the tailnet (the saved node stays). A connection attempt after this starts it again.
    func stop() {
        generation += 1
        monitor?.cancel(); monitor = nil
        connecting = nil
        let wasActive = desired || running
        desired = false; running = false; runningFingerprint = nil
        if wasActive { TailnetNode.stop() }
        status = enabled ? .stopped : .off
    }

    /// Logs the node out of the tailnet, deletes its saved state and key, and switches the feature off.
    func logout() async {
        stop()
        var note: String?
        if TailnetNode.isAvailable {
            do {
                // Logout needs a started node; the saved identity is enough, no key.
                try await TailnetNode.start(stateDir: try Self.stateDirectory().path, authKey: "",
                                            controlURL: trimmedURL, hostname: Self.hostname)
                try await TailnetNode.logout()
            } catch { note = error.localizedDescription }
            TailnetNode.stop()
        }
        authKey = ""
        enabled = false
        status = .off
        logoutNote = note
    }

    // MARK: starting

    private func bringUp() async throws {
        if let c = connecting { return try await c.task.value }
        nextTaskId += 1
        let id = nextTaskId
        desired = true
        let task = Task { try await self.startAndWait() }
        connecting = (id, task)
        defer { if connecting?.id == id { connecting = nil } }
        try await task.value
    }

    private func startAndWait() async throws {
        let gen = generation
        let fp = fingerprint
        if !running || runningFingerprint != fp {
            status = .connecting
            do {
                let dir = try Self.stateDirectory()
                try await TailnetNode.start(stateDir: dir.path, authKey: trimmedKey, controlURL: trimmedURL, hostname: Self.hostname)
            } catch {
                let message = error.localizedDescription
                if gen == generation { status = .failed(message) }
                throw TunnelError(message: message, retry: true)
            }
            guard gen == generation else { throw interrupted() }
            running = true
            runningFingerprint = fp
            startMonitor()
        }
        do {
            try await TailnetNode.waitConnected(seconds: 45)
        } catch {
            guard gen == generation else { throw interrupted() }
            if case .needsKey = await refreshStatus(gen: gen) {
                throw TunnelError(message: String(localized: "Built-in Tailscale needs a new auth key. Enter one in Settings."), retry: false)
            }
            throw TunnelError(message: error.localizedDescription, retry: true)
        }
        guard gen == generation else { throw interrupted() }
        await refreshStatus(gen: gen)
    }

    /// A stop (or a restart with other settings) overtook an attempt. If nothing wants the node any more, it must not
    /// be left running from a Start that finished after the Stop.
    private func interrupted() -> TunnelError {
        if !desired { TailnetNode.stop() }
        return TunnelError(message: String(localized: "Built-in Tailscale was stopped"), retry: true)
    }

    // MARK: status

    private func startMonitor() {
        monitor?.cancel()
        let gen = generation
        monitor = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.generation == gen, self.running else { return }
                let s = await self.refreshStatus(gen: gen)
                var seconds = 1.5
                if case .connected = s { seconds = 15 }
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            }
        }
    }

    @discardableResult
    private func refreshStatus(gen: Int) async -> Status {
        let s = Self.parse(await TailnetNode.status())
        if gen == generation, running { status = s }
        return s
    }

    private struct Report: Decodable {
        let state: String?
        let ips: [String]?
        let name: String?
        let error: String?
    }

    private static func parse(_ json: String) -> Status {
        guard let data = json.data(using: .utf8), let r = try? JSONDecoder().decode(Report.self, from: data) else {
            return .failed(String(localized: "Unreadable status from Tailscale"))
        }
        let detail = r.error.flatMap { $0.isEmpty ? nil : $0 }
        switch r.state {
        case "connected":
            let address = r.ips?.first { $0.hasPrefix("100.") } ?? r.ips?.first ?? ""
            return .connected(name: r.name ?? "", address: address)
        case "starting": return .connecting
        case "needs_key": return .needsKey(detail)
        case "stopped": return .stopped
        default: return .failed(detail ?? String(localized: "Tailscale is in an unexpected state"))
        }
    }

    /// Application Support/Tailnet, excluded from backups (the node's identity must not move to another device).
    private static func stateDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var url = base.appendingPathComponent("Tailnet", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        return url
    }
}
