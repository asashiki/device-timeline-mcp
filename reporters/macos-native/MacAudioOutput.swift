import AppKit
import CoreAudio

/// Which app processes currently have an active audio output stream.
enum MacAudioOutput {
    private static func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                   mElement: kAudioObjectPropertyElementMain)
    }

    private static func integer(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> UInt32? {
        var key = address(selector)
        var result: UInt32 = 0
        var size = UInt32(MemoryLayout.size(ofValue: result))
        return AudioObjectGetPropertyData(object, &key, 0, nil, &size, &result) == noErr ? result : nil
    }

    static func applicationIDs() -> [String]? {
        guard #available(macOS 14.2, *) else { return nil }
        let hardware = AudioObjectID(kAudioObjectSystemObject)
        var list = address(kAudioHardwarePropertyProcessObjectList)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(hardware, &list, 0, nil, &size) == noErr else { return nil }
        if size == 0 { return [] }
        let stride = MemoryLayout<AudioObjectID>.stride
        var processes = [AudioObjectID](repeating: 0, count: Int(size) / stride)
        let status = processes.withUnsafeMutableBytes { buffer in
            AudioObjectGetPropertyData(hardware, &list, 0, nil, &size, buffer.baseAddress!)
        }
        guard status == noErr else { return nil }
        var apps: Set<String> = []
        for object in processes.prefix(Int(size) / stride) {
            guard integer(object, kAudioProcessPropertyIsRunningOutput) == 1,
                  let pid = integer(object, kAudioProcessPropertyPID), pid <= Int32.max,
                  let app = NSRunningApplication(processIdentifier: pid_t(pid)),
                  let url = app.bundleURL else { continue }
            // Chromium, Electron, etc. often send audio from a nested helper. Use the
            // outer application bundle so readers can recognize the player.
            var package = url
            for parent in sequence(first: url.deletingLastPathComponent(),
                                   next: { $0.path == "/" ? nil : $0.deletingLastPathComponent() }) {
                if parent.pathExtension == "app" { package = parent }
            }
            guard let id = Bundle(url: package)?.bundleIdentifier ?? app.bundleIdentifier,
                  (1...255).contains(id.utf8.count),
                  id.range(of: #"^[A-Za-z0-9][A-Za-z0-9._-]*$"#, options: .regularExpression) != nil else { continue }
            apps.insert(id)
        }
        return Array(apps.sorted().prefix(16))
    }
}
