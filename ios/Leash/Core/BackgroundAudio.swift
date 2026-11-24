import AVFoundation

/// Keeps Leash running in the background while its Live Activity follows a turn. There is no push (free team), so
/// the activity changes only while the app runs; a looping silent sound in the `audio` background mode is what keeps
/// a process running for as long as a turn takes. The session mixes with other audio: it doesn't pause or duck
/// music, and never shows as Now Playing.
@MainActor
final class BackgroundAudio {
    private(set) var isOn = false
    private var player: AVAudioPlayer?
    private var observers: [NSObjectProtocol] = []

    init() {
        let nc = NotificationCenter.default
        // a call (or Siri) stops every session; play again once it is over
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw.flatMap({ AVAudioSession.InterruptionType(rawValue: $0) }) == .ended else { return }
            MainActor.assumeIsolated { self?.play() }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.player = nil
                self?.play()
            }
        })
    }

    /// Actually playing (an interruption without an end, or a start iOS refused, leaves `isOn` without sound).
    var isPlaying: Bool { player?.isPlaying ?? false }

    /// `on` also restarts playback that something stopped, so each Live Activity update gives it another try.
    func set(_ on: Bool) {
        if on {
            isOn = true
            if !isPlaying { play() }
        } else if isOn {
            isOn = false
            player?.stop()
            player = nil
            try? AVAudioSession.sharedInstance().setActive(false)
        }
    }

    private func play() {
        guard isOn else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            if player == nil {
                let p = try AVAudioPlayer(data: Self.silence)
                p.numberOfLoops = -1
                player = p
            }
            player?.play()
        } catch {
            // the audio hardware is taken (a call) or iOS refused a start from the background: the activity just
            // goes stale as without this, and the next interruption-ended tries again
        }
    }

    /// One second of 8 kHz, 16-bit mono silence as a WAV file.
    private static let silence: Data = {
        let rate: UInt32 = 8000
        let bytes: UInt32 = rate * 2
        var d = Data()
        func put<T: FixedWidthInteger>(_ v: T) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func tag(_ s: String) { d.append(contentsOf: Array(s.utf8)) }
        tag("RIFF"); put(36 + bytes); tag("WAVE")
        tag("fmt "); put(UInt32(16)); put(UInt16(1)); put(UInt16(1)); put(rate); put(rate * 2); put(UInt16(2)); put(UInt16(16))
        tag("data"); put(bytes)
        d.append(Data(count: Int(bytes)))
        return d
    }()
}
