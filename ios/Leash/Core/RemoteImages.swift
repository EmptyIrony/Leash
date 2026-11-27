import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

// The bridge sends conversation history without image bytes: an image block carries a reference, and the bytes are
// fetched (bridge/media, in small chunks) only once the image's view is on screen. One huge frame full of screenshots
// would hold up the connection's pings for as long as it takes to download.

/// An image the bridge holds for a conversation.
struct RemoteImage: Hashable, Sendable {
    /// content-addressed (a hash of the bytes): the same image keeps it across reattaches and bridge restarts
    let ref: String
    var mimeType = "image/png"
    /// pixel size as displayed, when the bridge could read it from the image's header
    var pixelSize: CGSize? = nil

    var fileExtension: String { UTType(mimeType: mimeType)?.preferredFilenameExtension ?? "png" }

    // CGSize became Hashable only recently: hash the ref alone (equality still compares every field)
    func hash(into hasher: inout Hasher) { hasher.combine(ref) }
}

/// An image block: its bytes (what the user just sent, drawn before the bridge echoes it) or the bridge's reference.
enum ImageSource: Hashable, Sendable {
    case local(Data)
    case remote(RemoteImage)

    init?(block b: JSON) {
        if let d = b["data"]?.string {
            guard let data = Data(base64Encoded: d) else { return nil }
            self = .local(data)
            return
        }
        guard let ref = b["ref"]?.string, !ref.isEmpty else { return nil }
        var image = RemoteImage(ref: ref)
        if let m = b["mimeType"]?.string { image.mimeType = m }
        if let w = b["width"]?.int, let h = b["height"]?.int, w > 0, h > 0 { image.pixelSize = CGSize(width: w, height: h) }
        self = .remote(image)
    }
}

/// A fetched image: the original bytes (QuickLook, Copy, Share, Save) and a downscaled bitmap for the transcript.
struct LoadedImage: Sendable {
    let data: Data
    let thumbnail: UIImage
    /// the original's size as displayed: what a tile is sized from, not the thumbnail's
    let pixelSize: CGSize

    /// The longest side of a transcript bitmap: the largest tile (480 pt wide) at 3x.
    private static let thumbnailSide = 1440

    var cost: Int { data.count + (thumbnail.cgImage.map { $0.bytesPerRow * $0.height } ?? 0) }

    /// nil when the bytes are not an image the system can show. Slow: call off the main actor.
    static func decode(base64: String) -> LoadedImage? {
        guard let data = Data(base64Encoded: base64),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: thumbnailSide,
        ]
        guard let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        // EXIF orientations 5...8 are quarter turns
        let turned = (properties[kCGImagePropertyOrientation] as? Int ?? 1) >= 5
        return LoadedImage(data: data, thumbnail: UIImage(cgImage: bitmap),
                           pixelSize: turned ? CGSize(width: height, height: width) : CGSize(width: width, height: height))
    }
}

/// Fetches referenced images, a few at a time app-wide, and keeps them by ref in bounded memory. A download lives only
/// as long as some view waits for it: once the last one is gone it leaves the line, or stops before its next chunk, so
/// what is on screen goes first. Failures are not kept.
@MainActor
final class RemoteImageLoader {
    static let shared = RemoteImageLoader()
    private static let maxDownloads = 2
    private static let chunkTimeout: Double = 60

    private final class Entry {
        let image: LoadedImage
        init(_ image: LoadedImage) { self.image = image }
    }

    private final class Download {
        let ref: String
        let sessionId: String
        let agent: String
        let link: BridgeConnection
        var waiters: [UUID: CheckedContinuation<LoadedImage, any Error>] = [:]
        var started = false
        init(ref: String, sessionId: String, agent: String, link: BridgeConnection) {
            self.ref = ref; self.sessionId = sessionId; self.agent = agent; self.link = link
        }
    }

    private let cache: NSCache<NSString, Entry> = {
        let c = NSCache<NSString, Entry>()
        c.totalCostLimit = 96 * 1024 * 1024
        return c
    }()
    /// Sizes of images seen so far, kept past eviction: a placeholder for an image whose block had no size is still
    /// the right one when it comes back.
    private var sizes: [String: CGSize] = [:]
    private var downloads: [String: Download] = [:]
    private var line: [Download] = []
    private var active = 0

    func cached(_ ref: String) -> LoadedImage? { cache.object(forKey: ref as NSString)?.image }
    func knownSize(_ ref: String) -> CGSize? { sizes[ref] }

    /// The image, from the cache or downloaded; `sessionId` is the root conversation. Cancelling the calling task
    /// gives up this caller's interest only.
    func image(_ ref: String, sessionId: String, agent: String, link: BridgeConnection) async throws -> LoadedImage {
        if let c = cached(ref) { return c }
        let waiter = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<LoadedImage, any Error>) in
                if Task.isCancelled { c.resume(throwing: CancellationError()); return }
                let download: Download
                if let running = downloads[ref] {
                    download = running
                } else {
                    download = Download(ref: ref, sessionId: sessionId, agent: agent, link: link)
                    downloads[ref] = download
                    line.append(download)
                }
                download.waiters[waiter] = c
                startNext()
            }
        } onCancel: {
            Task { @MainActor in RemoteImageLoader.shared.leave(ref, waiter) }
        }
    }

    private func leave(_ ref: String, _ waiter: UUID) {
        guard let download = downloads[ref], let c = download.waiters.removeValue(forKey: waiter) else { return }
        c.resume(throwing: CancellationError())
        if download.waiters.isEmpty && !download.started {
            downloads[ref] = nil
            line.removeAll { $0 === download }
        }
    }

    private func startNext() {
        while active < Self.maxDownloads, !line.isEmpty {
            let download = line.removeFirst()
            download.started = true
            active += 1
            Task {
                let result: Result<LoadedImage, any Error>
                do { result = .success(try await fetch(download)) }
                catch { result = .failure(error) }
                finish(download, result)
            }
        }
    }

    private func finish(_ download: Download, _ result: Result<LoadedImage, any Error>) {
        active -= 1
        downloads[download.ref] = nil
        if case .success(let image) = result {
            sizes[download.ref] = image.pixelSize
            cache.setObject(Entry(image), forKey: download.ref as NSString, cost: image.cost)
        }
        let waiters = download.waiters
        download.waiters = [:]
        for c in waiters.values { c.resume(with: result) }
        startNext()
    }

    /// bridge/media hands out the image's base64 text one slice at a time, one request in flight.
    private func fetch(_ download: Download) async throws -> LoadedImage {
        var base64 = ""
        var offset = 0
        var total = 0
        repeat {
            // nobody is waiting any more: stop between chunks
            guard !download.waiters.isEmpty else { throw CancellationError() }
            let r = try await download.link.call("bridge/media", [
                "sessionId": .string(download.sessionId), "ref": .string(download.ref),
                "offset": JSON(offset), "agent": .string(download.agent),
            ], timeout: Self.chunkTimeout)
            guard let slice = r["data"]?.string, !slice.isEmpty, let length = r["total"]?.int else {
                throw BridgeError(code: -3, message: String(localized: "The bridge sent no image data"))
            }
            base64 += slice
            offset += slice.utf8.count   // base64 is ASCII
            total = length
        } while offset < total
        let text = base64
        guard let image = await Task.detached(priority: .userInitiated, operation: { LoadedImage.decode(base64: text) }).value else {
            throw BridgeError(code: -3, message: String(localized: "This image format can't be shown inline"))
        }
        return image
    }
}
