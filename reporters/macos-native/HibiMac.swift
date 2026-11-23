import AppKit
import SwiftUI
import ApplicationServices
import Darwin

struct Config: Codable { var serverUrl: String; var token: String; var toolNames: [String]? }
struct Sample: Codable { var appId: String; var occurredAt: String; var extra: ActivityExtras }
@MainActor final class Model: ObservableObject {
    @Published var enabled = !UserDefaults.standard.bool(forKey:"paused")
    @Published var current = "正在读取"
    @Published var lastSync = "尚未同步"
    @Published var error = ""
    @Published var pending = 0
    @Published var backup = "尚无已核验备份"
    @Published var playbackDescription: String?
    @Published var tools: [(String,URL,String)] = []
    var queue: [Sample] = []
    var sending = false
    var lastKey = ""
    var lastSample = Date.distantPast
    var sleeping = false
    var timer: Timer?
    var privateTimer: Timer?
    let contextReporter = MacContextReporter()
    @Published var contextDelivery = "正在连接"
    @Published var canReadWindowTitle = false
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
        for name in config?.toolNames ?? [] where !name.contains("/") && !name.isEmpty {
            let folders=[FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications"),URL(fileURLWithPath:"/Applications")]
            for folder in folders {
                let url=folder.appendingPathComponent("\(name).app")
                if let bundle=Bundle(url:url),let id=bundle.bundleIdentifier { tools.append((name,url,id));break }
            }
        }
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
        refreshBackup()
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
            if let code, (200..<300).contains(code) { self.contextDelivery = "已同步" }
            else if code == 404 { self.contextDelivery = "等待服务器更新" }
            else if code == 401 || code == 403 { self.contextDelivery = "设备身份未获授权" }
            else { self.contextDelivery = "暂未同步" }
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
        playbackDescription = nil
    }
    func refreshPlayback() {
        let now = Date()
        playerStates = playerStates.filter { $0.value.expires > now }
        let latest = playerStates.values.filter { $0.event.state == "playing" }.max { $0.observed < $1.observed }
        let label = latest?.recentDescription(now:now)
        if playbackDescription != label { playbackDescription = label }
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
    func refreshBackup() {
        guard let data=try? Data(contentsOf:root.appendingPathComponent("Backups/status.json")),
              let status=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any],
              let stamp=status["lastSuccess"] as? String else { backup="尚无已核验备份";return }
        let parser=ISO8601DateFormatter();parser.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
        guard let date=parser.date(from:stamp) ?? ISO8601DateFormatter().date(from:stamp) else { backup="备份时间不可读取";return }
        let recent=Date().timeIntervalSince(date)<30*3600
        backup=(status["quickCheck"] as? String == "ok" && recent ? "副本已核验 · " : "备份需检查 · ")+date.formatted(date:.abbreviated,time:.shortened)
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
    func openCollectorPage(_ path: String) {
        guard let configured = config?.serverUrl, let url = URL(string: configured),
              let scheme = url.scheme, ["https", "http"].contains(scheme), url.host != nil else { return }
        NSWorkspace.shared.open(url.appendingPathComponent(path))
    }
    func openTimeline(){openCollectorPage("console")}
    func openNotion(){openCollectorPage("now")}
    func backupFolder(){NSWorkspace.shared.open(root.appendingPathComponent("Backups"))}
    func running(_ id:String)->Bool{!NSRunningApplication.runningApplications(withBundleIdentifier:id).isEmpty}
    func toggleTool(_ tool:(String,URL,String)) {
        let apps=NSRunningApplication.runningApplications(withBundleIdentifier:tool.2)
        if apps.isEmpty { NSWorkspace.shared.openApplication(at:tool.1,configuration:NSWorkspace.OpenConfiguration()) }
        else { for app in apps { app.terminate() } }
        DispatchQueue.main.asyncAfter(deadline:.now()+1){self.objectWillChange.send()}
    }
}
struct Dashboard: View {
    @ObservedObject var model:Model
    var body: some View {
        VStack(alignment:.leading,spacing:22) {
            HStack { VStack(alignment:.leading,spacing:5){Text("Hibi Mac").font(.system(size:30,weight:.semibold));Text("记录此刻，也把常用工具放在手边。").foregroundStyle(.secondary)};Spacer();Image(systemName:"clock.arrow.circlepath").font(.system(size:34)).foregroundStyle(Color(red:0.73,green:0.37,blue:0.31)) }
            VStack(alignment:.leading,spacing:16){
                HStack{Label(model.enabled ? "正在记录这台 Mac" : "记录已暂停",systemImage:model.enabled ? "record.circle" : "pause.circle").font(.headline);Spacer();Toggle("记录",isOn:Binding(get:{model.enabled},set:{_ in model.toggle()})).labelsHidden().toggleStyle(.switch)}
                Text(model.current).font(.system(size:24,weight:.medium))
                if let media = model.playbackDescription { Label(media,systemImage:"music.note").font(.caption).lineLimit(2) }
                HStack{Text("最近同步  "+model.lastSync);Spacer();Text(model.pending==0 ? "已同步" : "待上传 \(model.pending) 条").foregroundStyle(model.pending==0 ? .green : .orange)}.font(.caption).foregroundStyle(.secondary)
                if !model.error.isEmpty { Text(model.error).font(.caption).foregroundStyle(.orange) }
                Text("记录应用名称与使用时间；离线时保存在本机，联网后自动补传。").font(.caption).foregroundStyle(.secondary)
                HStack {
                    Label("私有实时 · " + model.contextDelivery, systemImage:"lock").font(.caption)
                    Spacer()
                    if !model.canReadWindowTitle {
                        Button("允许读取窗口标题"){model.openTitlePermission()}.font(.caption)
                    }
                }
                HStack{Button("Notion 日常面板"){model.openNotion()};Button("详细时间线"){model.openTimeline()};Button("立即同步"){model.tick()}.disabled(!model.enabled)}
                HStack{Label(model.backup,systemImage:"externaldrive.badge.checkmark").font(.caption).foregroundStyle(.secondary);Spacer();Button("查看备份"){model.backupFolder()}.font(.caption)}
            }.padding(20).background(.white.opacity(0.8)).clipShape(RoundedRectangle(cornerRadius:18))
            if !model.tools.isEmpty { Text("本机工具").font(.headline) }
            LazyVGrid(columns:[GridItem(.flexible()),GridItem(.flexible())],spacing:12) {
                ForEach(model.tools,id:\.2){tool in
                    HStack{Image(nsImage:NSWorkspace.shared.icon(forFile:tool.1.path)).resizable().frame(width:30,height:30);Text(tool.0).font(.system(size:12,weight:.medium));Spacer();Button(model.running(tool.2) ? "退出" : "打开"){model.toggleTool(tool)}.font(.caption)}.padding(12).background(.white.opacity(0.65)).clipShape(RoundedRectangle(cornerRadius:12))
                }
            }
            HStack{Text("窗口可以关闭，菜单栏里随时找得到。").font(.caption).foregroundStyle(.secondary);Spacer();Button("退出 Hibi"){NSApplication.shared.terminate(nil)}.font(.caption)}
        }.padding(28).frame(width:650).background(Color(red:0.96,green:0.95,blue:0.92))
    }
}
@MainActor final class Delegate:NSObject,NSApplicationDelegate,NSWindowDelegate {
    var window:NSWindow!;var item:NSStatusItem!;var model:Model!
    func applicationDidFinishLaunching(_ notification:Notification){
        model=Model();window=NSWindow(contentRect:NSRect(x:0,y:0,width:706,height:510),styleMask:[.titled,.closable,.miniaturizable],backing:.buffered,defer:false)
        window.appearance=NSAppearance(named:.aqua);window.title="Hibi Mac · 本机工具";window.contentView=NSHostingView(rootView:Dashboard(model:model).preferredColorScheme(.light));window.center();window.isReleasedWhenClosed=false;window.delegate=self
        item=NSStatusBar.system.statusItem(withLength:NSStatusItem.variableLength)
        item.button?.image=NSImage(systemSymbolName:"clock.arrow.circlepath",accessibilityDescription:"Hibi Mac")
        item.button?.target=self;item.button?.action=#selector(show)
        if !CommandLine.arguments.contains("--background"){show()}
    }
    @objc func show(){model.refreshBackup();window.makeKeyAndOrderFront(nil);NSApp.activate(ignoringOtherApps:true)}
    func applicationShouldHandleReopen(_ sender:NSApplication,hasVisibleWindows flag:Bool)->Bool{show();return true}
}
@main struct Main { @MainActor static func main() {
let root=FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Hibi")
try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
let lockPath=root.appendingPathComponent("app.lock").path
let lockFD=Darwin.open(lockPath,O_CREAT|O_RDWR,0o600)
if lockFD < 0 || flock(lockFD,LOCK_EX|LOCK_NB) != 0 { exit(0) }
let app=NSApplication.shared
let delegate=Delegate();app.delegate=delegate;app.setActivationPolicy(.accessory);app.run()
} }
