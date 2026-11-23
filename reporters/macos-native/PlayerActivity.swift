import Foundation

struct PlaybackEvent: Codable, Equatable {
    let player: String
    let state: String
    let track: String?
    let artist: String?
    let occurredAt: String
}

struct PlayerActivity {
    // Both names are emitted by the desktop players through DistributedNotificationCenter.
    static let players = ["com.apple.Music.playerInfo": "com.apple.Music",
                          "com.spotify.client.PlaybackStateChanged": "com.spotify.client"]
    let event: PlaybackEvent
    let observed: Date
    let expires: Date

    init?(notification: String, info: [AnyHashable: Any], at date: Date, stamp: String) {
        guard let player = Self.players[notification],
              let raw = info["Player State"] as? String else { return nil }
        let state = raw.lowercased()
        guard ["playing", "paused", "stopped"].contains(state) else { return nil }
        func field(_ key: String) -> String? {
            guard let raw = info[key] as? String else { return nil }
            let clean = raw.split(whereSeparator: { $0.isWhitespace || $0.isNewline }).joined(separator: " ")
            return clean.isEmpty ? nil : String(clean.prefix(180))
        }
        event = PlaybackEvent(player: player, state: state,
                              track: state == "playing" ? field("Name") : nil,
                              artist: state == "playing" ? field("Artist") : nil,
                              occurredAt: stamp)
        observed = date
        let duration = (info[player == "com.apple.Music" ? "Total Time" : "Duration"] as? NSNumber)?.doubleValue
        // These fields normally use milliseconds. For older Spotify payloads the
        // unit is ambiguous for small values, so show only the recent event.
        let seconds = duration.map { $0.isFinite && $0 >= 1000 ? $0 / 1000 : 60 } ?? 60
        expires = date.addingTimeInterval(max(10, min(seconds + 5, 15 * 60)))
    }

    func duplicates(_ old: PlayerActivity) -> Bool {
        observed.timeIntervalSince(old.observed) < 2 && event.player == old.event.player &&
        event.state == old.event.state && event.track == old.event.track && event.artist == old.event.artist
    }

    func recentDescription(now: Date) -> String? {
        guard now < expires, event.state == "playing" else { return nil }
        let name = event.player == "com.apple.Music" ? "音乐" : "Spotify"
        let metadata = [event.track, event.artist].compactMap { $0 }.joined(separator: " · ")
        return name + " 最近播放" + (metadata.isEmpty ? "（未提供曲名）" : "：" + metadata)
    }
}

private final class PlaybackSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor final class PlaybackUploader {
    private let file: URL
    private var queue: [PlaybackEvent]
    private var sending = false
    private lazy var session = URLSession(configuration: .ephemeral, delegate: PlaybackSessionDelegate(),
                                         delegateQueue: nil)

    init(folder: URL) {
        file = folder.appendingPathComponent("playback-queue.json")
        if let data = try? Data(contentsOf: file),
           let recovered = try? JSONDecoder().decode([PlaybackEvent].self, from: data) {
            queue = recovered
        } else {
            queue = []
        }
    }

    func add(_ event: PlaybackEvent) {
        queue.append(event)
        persist()
    }

    private func persist() {
        do {
            if queue.isEmpty {
                if FileManager.default.fileExists(atPath: file.path) {
                    try FileManager.default.removeItem(at: file)
                }
            } else {
                try JSONEncoder().encode(queue).write(to: file, options: .atomic)
                try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            }
        } catch {
            // Do not include song information or paths in logs.
            NSLog("Hibi could not persist the pending playback events")
        }
    }

    func flush(config: Config?, onComplete: @escaping @MainActor () -> Void) {
        guard !sending, let event = queue.first, let config,
              let url = URL(string: config.serverUrl + "/api/devices/playback"),
              url.scheme == "https" else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("Bearer " + config.token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(event)
        sending = true
        session.dataTask(with: request) { [weak self] _, response, _ in
            Task { @MainActor in
                guard let self else { return }
                self.sending = false
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return }
                self.queue.removeFirst()
                self.persist()
                onComplete()
            }
        }.resume()
    }
}
