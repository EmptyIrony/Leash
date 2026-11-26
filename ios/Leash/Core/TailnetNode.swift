import Foundation
#if canImport(Tailnet)
import Tailnet
#endif

/// Error from the embedded Tailscale node (the Go side's own message; it never contains the auth key).
struct TailnetNodeError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    static var unavailable: TailnetNodeError {
        TailnetNodeError(message: String(localized: "This build does not include built-in Tailscale"))
    }
}

/// The one place that touches the gomobile framework (`Tailnet.xcframework`, built by tailnet/build-ios.sh). Without
/// the framework every call fails with `unavailable` and the rest of the app compiles unchanged.
///
/// The Go calls block (Start and Logout hold the node's lock for seconds, WaitConnected waits for the tailnet), so
/// they never run on the main actor or the Swift concurrency pool. Calls that change the node go through one serial
/// queue, in the order they were made (a Stop issued before a Start also runs before it); the ones that only read
/// run on a concurrent queue, so a pending Start never delays the status.
enum TailnetNode {
    static let isAvailable: Bool = {
        #if canImport(Tailnet)
        return true
        #else
        return false
        #endif
    }()

    private static let control = DispatchQueue(label: "dev.leash.tailnet.control", qos: .userInitiated)
    private static let probe = DispatchQueue(label: "dev.leash.tailnet.probe", qos: .userInitiated, attributes: .concurrent)

    private static func run<T>(on queue: DispatchQueue, _ body: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
            queue.async { c.resume(with: Result { try body() }) }
        }
    }

    /// Brings the node up (returns once it is initialised, not once it is on the tailnet).
    static func start(stateDir: String, authKey: String, controlURL: String, hostname: String) async throws {
        #if canImport(Tailnet)
        try await run(on: control) { () throws -> Void in
            var err: NSError?
            guard TailnetStart(stateDir, authKey, controlURL, hostname, &err) else {
                throw TailnetNodeError(message: err?.localizedDescription ?? "Tailscale did not start")
            }
        }
        #else
        throw TailnetNodeError.unavailable
        #endif
    }

    /// Leaves the tailnet (the node's state is kept). Fire and forget, queued behind earlier calls.
    static func stop() {
        #if canImport(Tailnet)
        control.async { TailnetStop() }
        #endif
    }

    /// Logs the node out at the control server and deletes its state. The node has to be started.
    static func logout() async throws {
        #if canImport(Tailnet)
        try await run(on: control) { () throws -> Void in
            var err: NSError?
            guard TailnetLogout(&err) else {
                throw TailnetNodeError(message: err?.localizedDescription ?? "Tailscale did not log out")
            }
        }
        #else
        throw TailnetNodeError.unavailable
        #endif
    }

    /// A port on 127.0.0.1 that reaches `host:port` through the tailnet (the same one for the same target until stop).
    static func proxyPort(host: String, port: Int) async throws -> Int {
        #if canImport(Tailnet)
        return try await run(on: control) { () throws -> Int in
            var err: NSError?
            var out = 0
            guard TailnetProxyPort(host, port, &out, &err) else {
                throw TailnetNodeError(message: err?.localizedDescription ?? "Tailscale has no route to the bridge")
            }
            return out
        }
        #else
        throw TailnetNodeError.unavailable
        #endif
    }

    /// Blocks (off the main thread) until the node is on the tailnet; throws with the reason otherwise.
    static func waitConnected(seconds: Int) async throws {
        #if canImport(Tailnet)
        try await run(on: probe) { () throws -> Void in
            var err: NSError?
            guard TailnetWaitConnected(seconds, &err) else {
                throw TailnetNodeError(message: err?.localizedDescription ?? "Tailscale did not connect")
            }
        }
        #else
        throw TailnetNodeError.unavailable
        #endif
    }

    /// The node's status as the Go side's JSON (state, ips, name, error); empty without the framework.
    static func status() async -> String {
        #if canImport(Tailnet)
        return (try? await run(on: probe) { () throws -> String in TailnetStatus() }) ?? ""
        #else
        return ""
        #endif
    }
}
