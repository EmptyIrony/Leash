import Foundation

struct BridgeError: LocalizedError {
    let code: Int
    let message: String
    /// -32020 (conversation open elsewhere): who holds it ("cli", "claude-desktop", …)
    var holder: String? = nil
    var errorDescription: String? { message }
    static let lockedElsewhere = -32020
    static let changedElsewhere = -32021
    /// bridge/unqueue: the queued message already went out
    static let alreadySent = -32024
    static let notConnected = BridgeError(code: -1, message: "Not connected to the Mac")
    static let timeout = BridgeError(code: -2, message: "Request timed out")
}

/// WebSocket link to the bridge running on the Mac. Reconnects with backoff.
@MainActor
final class BridgeConnection: NSObject {
    enum State: Equatable {
        case idle
        case connecting
        case connected
        case waiting(String)    // will retry
        case failed(String)     // will not retry (bad token etc.)
    }

    var onMessage: (@MainActor (JSON) -> Void)?
    var onState: (@MainActor (State) -> Void)?

    private(set) var state: State = .idle { didSet { if state != oldValue { onState?(state) } } }
    private var task: URLSessionWebSocketTask?
    private lazy var urlSession = URLSession(configuration: {
        let c = URLSessionConfiguration.default
        c.waitsForConnectivity = false
        // For WebSocket tasks this acts as an idle timeout; keep it far above the ping interval.
        c.timeoutIntervalForRequest = 300
        c.timeoutIntervalForResource = 7 * 24 * 3600
        return c
    }())
    private var pending: [Int: CheckedContinuation<JSON, Error>] = [:]
    private var nextId = 1
    private var generation = 0
    private var retryDelay: Double = 1
    private var retryTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var endpoint: (url: URL, token: String)?

    func configure(host: String, port: Int, token: String) {
        var comps = URLComponents()
        comps.scheme = "ws"
        comps.host = host.trimmingCharacters(in: .whitespaces)
        comps.port = port
        comps.path = "/ws"
        guard let url = comps.url, !token.isEmpty, !host.isEmpty else {
            endpoint = nil
            disconnect()
            state = .failed("Set the Mac address and token in Settings")
            return
        }
        endpoint = (url, token)
    }

    func connect() {
        guard let endpoint else { state = .failed("Set the Mac address and token in Settings"); return }
        retryTask?.cancel(); retryTask = nil
        teardown(reason: nil)
        generation += 1
        let gen = generation
        var req = URLRequest(url: endpoint.url)
        req.setValue("Bearer \(endpoint.token)", forHTTPHeaderField: "Authorization")
        req.setValue("ios", forHTTPHeaderField: "X-Leash-Client")
        req.timeoutInterval = 300
        let t = urlSession.webSocketTask(with: req)
        t.maximumMessageSize = 64 * 1024 * 1024
        task = t
        state = .connecting
        t.resume()
        receive(t, gen: gen)
        // Watchdog: the bridge sends "ready" right after the upgrade.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            await MainActor.run {
                guard let self, gen == self.generation, self.state == .connecting, self.task === t else { return }
                self.handleFailure(t, error: BridgeError(code: -4, message: "Mac did not answer (is the bridge running and Tailscale up?)"), gen: gen)
            }
        }
    }

    /// Reconnect right away if we are not connected (e.g. app came to foreground).
    func ensureConnected() {
        switch state {
        case .connected, .connecting: return
        case .failed where endpoint == nil: return
        default: retryDelay = 1; connect()
        }
    }

    func disconnect() {
        retryTask?.cancel(); retryTask = nil
        generation += 1
        teardown(reason: nil)
        state = .idle
    }

    private func teardown(reason: String?) {
        pingTask?.cancel(); pingTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        let err = BridgeError(code: -1, message: reason ?? "Connection closed")
        let p = pending; pending.removeAll()
        for (_, c) in p { c.resume(throwing: err) }
    }

    private func receive(_ t: URLSessionWebSocketTask, gen: Int) {
        t.receive { [weak self] result in
            Task { @MainActor in
                guard let self, gen == self.generation else { return }
                switch result {
                case .success(let msg):
                    let data: Data?
                    switch msg {
                    case .data(let d): data = d
                    case .string(let s): data = s.data(using: .utf8)
                    @unknown default: data = nil
                    }
                    if let data, let json = try? JSON.decode(data) { self.handle(json) }
                    self.receive(t, gen: gen)
                case .failure(let error):
                    self.handleFailure(t, error: error, gen: gen)
                }
            }
        }
    }

    private func handleFailure(_ t: URLSessionWebSocketTask, error: Error, gen: Int) {
        guard t === task, gen == generation else { return }   // already handled
        let status = (t.response as? HTTPURLResponse)?.statusCode
        teardown(reason: error.localizedDescription)
        if status == 401 {
            state = .failed("Token rejected by the Mac (401). Re-pair in Settings.")
            return
        }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 15)
        state = .waiting(error.localizedDescription)
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.connect() }
        }
    }

    private func handle(_ m: JSON) {
        let op = m["op"]?.string
        if op == "ready" {
            state = .connected
            retryDelay = 1
            startPings()
        }
        if op == "result", let id = m["id"]?.int, let c = pending.removeValue(forKey: id) {
            if let e = m["error"], !e.isNull {
                c.resume(throwing: BridgeError(code: e["code"]?.int ?? -32603, message: e["message"]?.string ?? "error",
                                               holder: e["data"]?["holders"]?.array?.first?["entrypoint"]?.string))
            } else {
                c.resume(returning: m["result"] ?? .null)
            }
            return
        }
        onMessage?(m)
    }

    private func startPings() {
        pingTask?.cancel()
        let gen = generation
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    guard let self, gen == self.generation, let t = self.task else { return }
                    t.sendPing { err in
                        if let err {
                            Task { @MainActor in
                                guard gen == self.generation else { return }
                                self.handleFailure(t, error: err, gen: gen)
                            }
                        }
                    }
                }
            }
        }
    }

    private func sendRaw(_ obj: JSON) throws {
        guard let task, state == .connected else { throw BridgeError.notConnected }
        let data = try obj.encoded()
        guard let s = String(data: data, encoding: .utf8) else { return }
        task.send(.string(s)) { _ in }
    }

    /// Call a bridge / ACP method. `timeout` nil = wait forever (session/prompt).
    func call(_ method: String, _ params: JSON = [:], timeout: Double? = 30) async throws -> JSON {
        let id = nextId; nextId += 1
        let msg: JSON = ["op": "call", "id": JSON(id), "method": .string(method), "params": params]
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<JSON, Error>) in
            pending[id] = c
            do { try sendRaw(msg) } catch {
                pending.removeValue(forKey: id)
                c.resume(throwing: error)
                return
            }
            if let timeout {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    await MainActor.run {
                        if let c = self?.pending.removeValue(forKey: id) { c.resume(throwing: BridgeError.timeout) }
                    }
                }
            }
        }
    }

    /// Answer an agent -> client request (permission, elicitation).
    func reply(key: String, result: JSON) throws {
        try sendRaw(["op": "reply", "key": .string(key), "result": result])
    }
}
