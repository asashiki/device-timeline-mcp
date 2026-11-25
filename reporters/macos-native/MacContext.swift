import AppKit
import ApplicationServices

struct MacContext: Codable {
    let sampledAt: String
    let state: String
    var appName: String?
    var appId: String?
    var windowTitle: String?
    var desktop: Int?
    var audioOutputApps: [String]?
}

// Match the single active space to the current space on its display. Desktop
// numbers follow Mission Control display/space order and exclude full-screen apps.
// If the WindowServer reports an inconsistent layout (for example, during a
// display transition) omit the ordinal.
private enum DesktopNumber {
    typealias Connection = @convention(c) () -> UInt32
    typealias CopySpaces = @convention(c) (UInt32) -> Unmanaged<CFArray>?
    typealias ActiveSpace = @convention(c) (UInt32) -> UInt64
    static let readers: (Connection, CopySpaces, ActiveSpace)? = {
        let path = "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight"
        guard let handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL),
              let connection = dlsym(handle, "SLSMainConnectionID"),
              let spaces = dlsym(handle, "SLSCopyManagedDisplaySpaces"),
              let active = dlsym(handle, "SLSGetActiveSpace") else { return nil }
        return (unsafeBitCast(connection, to: Connection.self), unsafeBitCast(spaces, to: CopySpaces.self),
                unsafeBitCast(active, to: ActiveSpace.self))
    }()
    static func current() -> Int? {
        guard let (connection, copy, active) = readers else { return nil }
        let server = connection()
        let id = active(server)
        guard id != 0, let displays = copy(server)?.takeRetainedValue() as? [[String: Any]],
              displays.count == NSScreen.screens.count,
              displays.compactMap({ $0["Current Space"] as? [String: Any] })
                .filter({ ($0["ManagedSpaceID"] as? NSNumber)?.uint64Value == id && $0["type"] as? Int == 0 }).count == 1
        else { return nil }
        var normalIDs: [UInt64] = []
        for display in displays {
            guard let spaces = display["Spaces"] as? [[String: Any]],
                  spaces.allSatisfy({ $0["type"] is NSNumber }) else { return nil }
            for space in spaces where space["type"] as? Int == 0 {
                guard let normal = (space["ManagedSpaceID"] as? NSNumber)?.uint64Value else { return nil }
                normalIDs.append(normal)
            }
        }
        guard Set(normalIDs).count == normalIDs.count,
              let index = normalIDs.firstIndex(of: id), index < 128 else { return nil }
        return index + 1
    }
}

private final class ContextSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor final class MacContextReporter {
    let clock = ISO8601DateFormatter()
    private var request: URLSessionDataTask?
    private var sequence = 0
    private var retryAudioAfter = Date.distantPast
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.httpShouldSetCookies = false
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 6
        return URLSession(configuration: config, delegate: ContextSessionDelegate(), delegateQueue: nil)
    }()
    init() { clock.formatOptions = [.withInternetDateTime, .withFractionalSeconds] }

    static func trimmed(_ input: String?, max: Int) -> String? {
        guard let input else { return nil }
        let clean = input.components(separatedBy: .controlCharacters).joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var units = 0
        let result = clean.prefix { ch in
            units += String(ch).utf16.count
            return units <= max
        }
        return result.isEmpty ? nil : String(result)
    }

    static func title(for pid: pid_t) -> String? {
        if AXIsProcessTrusted() {
            let app = AXUIElementCreateApplication(pid)
            AXUIElementSetMessagingTimeout(app, 0.12)
            var rawWindow: CFTypeRef?
            var rawTitle: CFTypeRef?
            if AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute as CFString, &rawWindow) == .success,
               let rawWindow, CFGetTypeID(rawWindow) == AXUIElementGetTypeID() {
                let window = rawWindow as! AXUIElement
                AXUIElementSetMessagingTimeout(window, 0.12)
                if AXUIElementCopyAttributeValue(window, kAXTitleAttribute as CFString, &rawTitle) == .success,
                   let title = trimmed(rawTitle as? String, max: 300) { return title }
            }
        }
        // Use window metadata only if the owner already granted this permission.
        // A frontmost document window can be identified only if it exposes a name.
        guard CGPreflightScreenCaptureAccess(),
              let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]],
              let front = windows.first(where: {
                  $0[kCGWindowOwnerPID as String] as? pid_t == pid && $0[kCGWindowLayer as String] as? Int == 0
              }) else { return nil }
        return trimmed(front[kCGWindowName as String] as? String, max: 300)
    }

    static func canReadTitle() -> Bool { AXIsProcessTrusted() || CGPreflightScreenCaptureAccess() }

    func sample(enabled: Bool, screenLocked: Bool) -> MacContext {
        let now = Date()
        let stamp = clock.string(from: now)
        guard enabled else { return MacContext(sampledAt: stamp, state: "paused") }
        let sessionInfo = CGSessionCopyCurrentDictionary() as? [String: Any]
        let hidden = screenLocked || sessionInfo == nil || sessionInfo?["CGSSessionScreenIsLocked"] as? Bool == true ||
            sessionInfo?[kCGSessionOnConsoleKey as String] as? Bool == false
        guard !hidden else { return MacContext(sampledAt: stamp, state: "locked") }
        let idle = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: UInt32.max)!)
        let output = MacAudioOutput.applicationIDs()
        guard idle < 300, let app = NSWorkspace.shared.frontmostApplication else {
            return MacContext(sampledAt: stamp, state: "idle", audioOutputApps: output)
        }
        if app.bundleIdentifier == "com.apple.loginwindow" { return MacContext(sampledAt: stamp, state: "locked") }
        var value = MacContext(sampledAt: stamp, state: "active")
        value.appName = Self.trimmed(app.localizedName ?? app.bundleIdentifier, max: 160)
        value.appId = Self.trimmed(app.bundleIdentifier, max: 255)
        value.windowTitle = Self.title(for: app.processIdentifier)
        value.desktop = DesktopNumber.current()
        value.audioOutputApps = output
        return value
    }

    func upload(_ value: MacContext, config: Config?, completion: @escaping @MainActor (Int?) -> Void) {
        sequence += 1
        let revision = sequence
        request?.cancel()
        var outbound = value
        if Date() < retryAudioAfter { outbound.audioOutputApps = nil }
        guard let config, let url = URL(string: config.serverUrl + "/api/devices/mac-context"),
              url.scheme == "https", url.user == nil, url.password == nil, !config.token.isEmpty,
              let body = try? JSONEncoder().encode(outbound) else { completion(nil); return }
        var post = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        post.httpMethod = "POST"
        post.setValue("Bearer " + config.token, forHTTPHeaderField: "Authorization")
        post.setValue("application/json", forHTTPHeaderField: "Content-Type")
        post.httpBody = body
        let includesAudio = outbound.audioOutputApps != nil
        request = session.dataTask(with: post) { [weak self] _, response, _ in
            Task { @MainActor in
                guard let self, revision == self.sequence else { return }
                self.request = nil
                let code = (response as? HTTPURLResponse)?.statusCode
                if code == 400, includesAudio {
                    // Older collectors reject unknown fields. Keep reporting with their schema.
                    self.retryAudioAfter = Date().addingTimeInterval(300)
                    self.upload(value, config: config, completion: completion)
                    return
                }
                completion(code)
            }
        }
        request?.resume()
    }
}
