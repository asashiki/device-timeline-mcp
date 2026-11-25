import AppKit
import ApplicationServices
import Darwin

struct Config: Codable { var serverUrl: String; var token: String }
struct Sample: Codable { var appId: String; var occurredAt: String; var extra: ActivityExtras }
@MainActor final class Model {
    var enabled = !UserDefaults.standard.bool(forKey:"paused")
    var current = "正在读取"
    var lastSync = "尚未同步"
    var error = ""
    var pending = 0
    var queue: [Sample] = []
    var sending = false
    var lastKey = ""
    var lastSample = Date.distantPast
    var sleeping = false
    var timer: Timer?
    var privateTimer: Timer?
    let contextReporter = MacContextReporter()
    var canReadWindowTitle = false
    var contextReceipt: Int?
    var contextTitleAvailable = false
    var contextDesktop: Int?
    var contextSampledAt = ""
    var contextState = ""
    var sessionHidden = false
    var displayHidden = false
    let formatter = ISO8601DateFormatter()
    let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Hibi")
    lazy var playbackUploader = PlaybackUploader(folder: root)
    var playerStates: [String:PlayerActivity] = [:]
    var musicObservers: [NSObjectProtocol] = []
    var config: Config?
    init() {
        formatter.formatOptions = [.withInternetDateTime,.withFractionalSeconds]
        try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let path=FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/hibi/mac.json")
        if let data=try? Data(contentsOf:path) { config=try? JSONDecoder().decode(Config.self,from:data) }
        if let data=try? Data(contentsOf:root.appendingPathComponent("queue.json")) { queue=(try? JSONDecoder().decode([Sample].self,from:data)) ?? [] }
        if let date=UserDefaults.standard.object(forKey:"lastSync") as? Date { lastSync=date.formatted(date:.abbreviated,time:.standard) }
        pending=queue.count
        if config == nil { error="尚未连接服务器" }
        let notifications = NSWorkspace.shared.notificationCenter
        notifications.addObserver(forName:NSWorkspace.didActivateApplicationNotification,object:nil,queue:.main){[weak self] _ in
            Task { @MainActor in self?.tick(); self?.refreshContext() }
        }
        notifications.addObserver(forName:NSWorkspace.willSleepNotification,object:nil,queue:.main){[weak self] _ in
            Task { @MainActor in self?.sleeping = true; self?.lastKey = ""; self?.clearPlayback(); self?.refreshContext() }
        }
        notifications.addObserver(forName:NSWorkspace.didWakeNotification,object:nil,queue:.main){[weak self] _ in
            Task { @MainActor in self?.sleeping = false; self?.lastKey = ""; self?.tick(); self?.refreshContext() }
        }
        notifications.addObserver(forName:NSWorkspace.didTerminateApplicationNotification,object:nil,queue:.main){[weak self] note in
            let id = (note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.bundleIdentifier
            guard let id, PlayerActivity.players.values.contains(id) else { return }
            Task { @MainActor in self?.playerStates.removeValue(forKey:id); self?.refreshPlayback() }
        }
        for name in [NSWorkspace.activeSpaceDidChangeNotification,
                     NSWorkspace.screensDidSleepNotification, NSWorkspace.screensDidWakeNotification,
                     NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.sessionDidBecomeActiveNotification] {
            notifications.addObserver(forName:name, object:nil, queue:.main) { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    if name == NSWorkspace.screensDidSleepNotification { self.displayHidden = true }
                    if name == NSWorkspace.screensDidWakeNotification { self.displayHidden = false }
                    if name == NSWorkspace.sessionDidResignActiveNotification { self.sessionHidden = true }
                    if name == NSWorkspace.sessionDidBecomeActiveNotification { self.sessionHidden = false }
                    self.refreshContext()
                }
            }
        }
        for name in ["com.apple.screenIsLocked", "com.apple.screenIsUnlocked"] {
            DistributedNotificationCenter.default().addObserver(forName:Notification.Name(name), object:nil, queue:.main) { [weak self] _ in
                Task { @MainActor in self?.refreshContext(forceLocked: name.hasSuffix("IsLocked")) }
            }
        }
        configurePlayback()
        configureTimer()
        refreshContext()
        tick()
    }
    func save() {
        pending=queue.count
        if let data=try? JSONEncoder().encode(queue) { try? data.write(to:root.appendingPathComponent("queue.json"),options:.atomic);try? FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:root.appendingPathComponent("queue.json").path) }
    }
    func configureTimer() {
        timer?.invalidate()
        privateTimer?.invalidate()
        privateTimer = nil
        guard enabled else { timer = nil; return }
        let heartbeat = Timer(timeInterval:60,repeats:true){[weak self] _ in
            Task { @MainActor in self?.tick(heartbeat:true) }
        }
        heartbeat.tolerance = 5
        RunLoop.main.add(heartbeat,forMode:.common)
        timer = heartbeat
        let contextTimer = Timer(timeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshContext() }
        }
        contextTimer.tolerance = 2
        RunLoop.main.add(contextTimer, forMode: .common)
        privateTimer = contextTimer
    }
    func toggle() { enabled.toggle(); UserDefaults.standard.set(!enabled,forKey:"paused");lastKey="";configureTimer();configurePlayback();tick();refreshContext();writeStatus() }
    func refreshContext(forceLocked: Bool = false) {
        let value = contextReporter.sample(enabled:enabled, screenLocked:sleeping || sessionHidden || displayHidden || forceLocked)
        // A pause is published once. Later app/desktop switches while paused skip collection/upload.
        if !enabled && contextState == "paused" && contextReceipt.map({ (200..<300).contains($0) }) == true { return }
        canReadWindowTitle = MacContextReporter.canReadTitle()
        contextTitleAvailable = value.windowTitle != nil
        contextDesktop = value.desktop
        contextSampledAt = value.sampledAt
        contextState = value.state
        contextReporter.upload(value, config:config) { [weak self] code in
            guard let self else { return }
            self.contextReceipt = code
            self.writeStatus()
        }
    }
    func openTitlePermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String:true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        NSWorkspace.shared.open(URL(string:"x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }
    func clearPlayback() {
        playerStates.removeAll()
    }
    func refreshPlayback() {
        let now = Date()
        playerStates = playerStates.filter { $0.value.expires > now }
    }
    func configurePlayback() {
        let center = DistributedNotificationCenter.default()
        musicObservers.forEach { center.removeObserver($0) }
        musicObservers.removeAll()
        clearPlayback()
        guard enabled else { return }
        for name in PlayerActivity.players.keys {
            let observer = center.addObserver(forName:Notification.Name(name),object:nil,queue:.main) { [weak self] note in
                let info = note.userInfo ?? [:]
                Task { @MainActor in
                    guard let self, self.enabled, !self.sleeping else { return }
                    let date = Date()
                    guard let state = PlayerActivity(notification:name,info:info,at:date,stamp:self.formatter.string(from:date)) else { return }
                    if let old = self.playerStates[state.event.player], state.duplicates(old) { return }
                    self.playerStates[state.event.player] = state
                    self.refreshPlayback()
                    self.playbackUploader.add(state.event)
                    self.flushPlayback()
                }
            }
            musicObservers.append(observer)
        }
    }
    func flushPlayback() {
        guard enabled, !sleeping else { return }
        playbackUploader.flush(config:config) { [weak self] in self?.flushPlayback() }
    }
    func tick(heartbeat: Bool = false) {
        guard enabled else { current="记录已暂停";return }
        guard !sleeping, config != nil else { return }
        if !playerStates.isEmpty { refreshPlayback() }
        flushPlayback()
        let app=NSWorkspace.shared.frontmostApplication
        // Apple's kCGAnyInputEventType covers mouse, keyboard and tablet activity.
        let idle=CGEventSource.secondsSinceLastEventType(.combinedSessionState,eventType:CGEventType(rawValue: UInt32.max)!)
        let afk=idle>=300
        let id=afk ? "macos.afk" : (app?.bundleIdentifier ?? "macos.idle")
        let name=afk ? "暂时离开" : (app?.localizedName ?? "空闲")
        let now = Date()
        if id != lastKey || heartbeat || now.timeIntervalSince(lastSample)>=60 {
            let stamp = formatter.string(from:now)
            let battery = BatteryStatus.current()
            // The server extends a segment on a repeated sample. At an observed
            // switch, close the previous segment before recording the new app.
            // A shutdown, sleep or long sampling gap has no observed end time.
            if !lastKey.isEmpty, lastKey != id, now.timeIntervalSince(lastSample) <= 90 {
                queue.append(Sample(appId:lastKey,occurredAt:stamp,extra:ActivityExtras(name:current, battery:battery)))
            }
            queue.append(Sample(appId:id,occurredAt:stamp,extra:ActivityExtras(name:name, description:afk ? "暂时离开" : "正在使用 \(name)", battery:battery)))
            lastKey=id;lastSample=now;current=name;save()
        }
        flush()
    }
    func flush() {
        guard enabled, !sending, let item=queue.first,let cfg=config, let url=URL(string:cfg.serverUrl+"/api/devices/report") else { return }
        sending=true
        var req=URLRequest(url:url);req.httpMethod="POST";req.timeoutInterval=15
        req.setValue("Bearer "+cfg.token,forHTTPHeaderField:"Authorization");req.setValue("application/json",forHTTPHeaderField:"Content-Type")
        req.httpBody=try? JSONEncoder().encode(item)
        URLSession.shared.dataTask(with:req){[weak self] _,response,err in
            Task { @MainActor in
                guard let self=self else { return };self.sending=false
                if let r=response as? HTTPURLResponse,(200..<300).contains(r.statusCode) {
                    if !self.queue.isEmpty { self.queue.removeFirst() };self.save();self.error=""
                    let date=Date();self.lastSync=date.formatted(date:.omitted,time:.standard);UserDefaults.standard.set(date,forKey:"lastSync")
                    self.writeStatus();self.flush()
                } else {
                    let code=(response as? HTTPURLResponse)?.statusCode
                    self.error=code==401 ? "连接凭据失效，记录已保存在本机" : "连接暂不可用，记录会在恢复后补传"
                    self.writeStatus()
                }
            }
        }.resume()
    }
    func writeStatus() {
        let context: [String:Any] = ["sampledAt":contextSampledAt, "state":contextState,
            "titleAvailable":contextTitleAvailable, "titlePermission":canReadWindowTitle,
            "desktop":contextDesktop as Any? ?? NSNull(), "responseCode":contextReceipt as Any? ?? NSNull()]
        let obj:[String:Any]=["privateContextStatus":context, "enabled":enabled,"pending":queue.count,"lastSync":lastSync,"error":error,"currentApp":current]
        if let d=try? JSONSerialization.data(withJSONObject:obj) { try? d.write(to:root.appendingPathComponent("status.json"),options:.atomic) }
    }
}

// Let AppKit draw the menu, including its accent, typography and appearance.
@MainActor final class Delegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let model = Model()
    private let menu = NSMenu()
    private var item: NSStatusItem!
    static let reopenNotification = Notification.Name("com.asashiki.hibi.mac.showMenu")

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.autosaveName = "Hibi Mac"
        menu.autoenablesItems = false
        menu.delegate = self
        item.menu = menu
        updateIcon()
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(openMenu),
            name: Self.reopenNotification, object: nil)
    }

    @objc private func openMenu() { item.button?.performClick(nil) }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        openMenu()
        return false
    }

    private func updateIcon() {
        let symbol = model.enabled ? "clock.arrow.circlepath" : "pause.circle"
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: "Hibi Mac")
        image?.isTemplate = true
        item.button?.setAccessibilityLabel("Hibi Mac")
        item.button?.image = image
        item.button?.toolTip = model.enabled ? "Hibi · 正在记录" : "Hibi · 已暂停"
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let toggle = menu.addItem(withTitle: "记录这台 Mac", action: #selector(toggleRecording), keyEquivalent: "")
        toggle.target = self
        toggle.state = model.enabled ? .on : .off
        let sync: String
        if !model.error.isEmpty {
            sync = model.error
        } else if model.pending > 0 {
            sync = "待上传 \(model.pending) 条"
        } else if let last = UserDefaults.standard.object(forKey: "lastSync") as? Date {
            let today = Calendar.current.isDateInToday(last)
            sync = "上次同步 " + last.formatted(date: today ? .omitted : .abbreviated, time: .shortened)
        } else {
            sync = "尚未同步"
        }
        if #available(macOS 14.4, *) {
            toggle.subtitle = sync
        } else {
            menu.addItem(withTitle: sync, action: nil, keyEquivalent: "").isEnabled = false
        }
        if !model.canReadWindowTitle {
            let access = menu.addItem(withTitle: "允许读取窗口标题…", action: #selector(openTitlePermission), keyEquivalent: "")
            access.target = self
        }
        menu.addItem(.separator())
        let quit = menu.addItem(withTitle: "退出 Hibi", action: #selector(quitApp), keyEquivalent: "q")
        quit.target = self
    }

    @objc private func toggleRecording() { model.toggle(); updateIcon() }
    @objc private func openTitlePermission() { model.openTitlePermission() }
    @objc private func quitApp() { NSApp.terminate(nil) }
}
@main struct Main { @MainActor static func main() {
let root=FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Hibi")
try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
let lockPath=root.appendingPathComponent("app.lock").path
let lockFD=Darwin.open(lockPath,O_CREAT|O_RDWR,0o600)
guard lockFD >= 0 else { exit(1) }
if flock(lockFD, LOCK_EX | LOCK_NB) != 0 {
    if !CommandLine.arguments.contains("--background") {
        DistributedNotificationCenter.default().postNotificationName(Delegate.reopenNotification,
            object: nil, userInfo: nil, deliverImmediately: true)
    }
    exit(0)
}
let app=NSApplication.shared
let delegate=Delegate();app.delegate=delegate;app.setActivationPolicy(.accessory);app.run()
} }
