// Asashiki Windows Agent
// Based on live-dashboard windows-agent (https://github.com/nmb1337/live-dashboard)

using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace AsashikiWindowsAgent;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new AgentApplicationContext());
    }
}

// ── Win32 ──────────────────────────────────────────────────────────────────

static class NativeMethods
{
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO info);

    [StructLayout(LayoutKind.Sequential)]
    public struct LASTINPUTINFO
    {
        public uint cbSize;
        public uint dwTime;
    }

    public static uint GetIdleSeconds()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        GetLastInputInfo(ref info);
        return (uint)((Environment.TickCount64 - info.dwTime) / 1000);
    }
}

// ── Auto-start on login (Windows Registry) ─────────────────────────────────

static class AutoStart
{
    const string RUN_KEY = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string APP_NAME = "AsashikiWindowsAgent";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RUN_KEY, false);
        return key?.GetValue(APP_NAME) != null;
    }

    public static void Enable()
    {
        var exe = Process.GetCurrentProcess().MainModule?.FileName ?? "";
        if (string.IsNullOrEmpty(exe)) return;
        using var key = Registry.CurrentUser.OpenSubKey(RUN_KEY, true)
            ?? Registry.CurrentUser.CreateSubKey(RUN_KEY)!;
        key.SetValue(APP_NAME, $"\"{exe}\"");
    }

    public static void Disable()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RUN_KEY, true);
        key?.DeleteValue(APP_NAME, false);
    }
}

// ── Config ─────────────────────────────────────────────────────────────────

record CustomAppRule(string AppId, string CustomAppName, string? CustomDescription);

class AgentConfig
{
    public string ServerUrl { get; set; } = "";
    public string Token { get; set; } = "";
    public string DeviceName { get; set; } = "Windows";
    public int ReportIntervalSeconds { get; set; } = 10;
    public int HeartbeatIntervalSeconds { get; set; } = 60;
    public int AfkThresholdSeconds { get; set; } = 300;
    public List<CustomAppRule> CustomApps { get; set; } = [];
}

static class ConfigStore
{
    static readonly string ConfigPath = Path.Combine(
        AppContext.BaseDirectory, "appsettings.json");

    public static AgentConfig Load()
    {
        if (!File.Exists(ConfigPath)) return new AgentConfig();
        try
        {
            var json = File.ReadAllText(ConfigPath);
            return JsonSerializer.Deserialize<AgentConfig>(json,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
                ?? new AgentConfig();
        }
        catch { return new AgentConfig(); }
    }

    public static void Save(AgentConfig config)
    {
        var json = JsonSerializer.Serialize(config,
            new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath, json);
    }
}

// ── Reporter ───────────────────────────────────────────────────────────────

class AgentReporter : IDisposable
{
    readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };
    AgentConfig _config;
    string _lastKey = "";
    DateTime _lastSentAt = DateTime.MinValue;
    readonly Action<string> _log;

    public AgentReporter(AgentConfig config, Action<string> log)
    {
        _config = config;
        _log = log;
    }

    public void UpdateConfig(AgentConfig config) => _config = config;

    public async Task TickAsync()
    {
        if (string.IsNullOrWhiteSpace(_config.ServerUrl) ||
            string.IsNullOrWhiteSpace(_config.Token)) return;

        var idleSec = NativeMethods.GetIdleSeconds();
        string appId, windowTitle;

        if (idleSec >= _config.AfkThresholdSeconds)
        {
            appId = "windows.afk";
            windowTitle = $"AFK ({idleSec}s)";
        }
        else
        {
            var hwnd = NativeMethods.GetForegroundWindow();
            if (hwnd == IntPtr.Zero) { appId = "windows.idle"; windowTitle = ""; }
            else
            {
                NativeMethods.GetWindowThreadProcessId(hwnd, out var pid);
                var titleBuf = new StringBuilder(256);
                NativeMethods.GetWindowText(hwnd, titleBuf, 256);
                windowTitle = titleBuf.ToString().Trim();
                try
                {
                    var proc = Process.GetProcessById((int)pid);
                    appId = proc.ProcessName.ToLowerInvariant();
                }
                catch { appId = "windows.unknown"; }
            }
        }

        var rule = _config.CustomApps.FirstOrDefault(r =>
            appId.Contains(r.AppId, StringComparison.OrdinalIgnoreCase));

        var effectiveApp = rule?.CustomAppName ?? appId;
        var description = rule?.CustomDescription
            ?.Replace("{title}", windowTitle)
            .Replace("{appId}", appId)
            .Replace("{app}", effectiveApp);

        var now = DateTime.UtcNow;
        var key = $"{appId}|{windowTitle}";
        var elapsed = (now - _lastSentAt).TotalSeconds;
        var forceHeartbeat = elapsed >= _config.HeartbeatIntervalSeconds;

        if (key == _lastKey && !forceHeartbeat) return;

        var extra = new Dictionary<string, object?> { ["network_type"] = "ethernet" };
        if (!string.IsNullOrEmpty(description)) extra["custom_description"] = description;
        if (rule != null) extra["custom_app_name"] = effectiveApp;

        var body = new
        {
            appId,
            windowTitle = windowTitle.Length > 256 ? windowTitle[..256] : windowTitle,
            occurredAt = now.ToString("o"),
            extra
        };

        var json = JsonSerializer.Serialize(body);
        using var req = new HttpRequestMessage(HttpMethod.Post,
            $"{_config.ServerUrl.TrimEnd('/')}/api/devices/report");
        req.Headers.Add("Authorization", $"Bearer {_config.Token}");
        req.Headers.Add("User-Agent", "asashiki-windows-agent/1.0.0");
        req.Content = new StringContent(json, Encoding.UTF8, "application/json");

        try
        {
            var resp = await _http.SendAsync(req);
            if (resp.IsSuccessStatusCode)
            {
                _lastKey = key;
                _lastSentAt = now;
                _log($"上报成功: {effectiveApp}");
            }
            else
            {
                var resBody = await resp.Content.ReadAsStringAsync();
                _log($"上报失败: HTTP {(int)resp.StatusCode} {resBody.Substring(0, Math.Min(100, resBody.Length))}");
            }
        }
        catch (Exception ex)
        {
            _log($"上报错误: {ex.Message}");
        }
    }

    public void Dispose() => _http.Dispose();
}

// ── Settings Form ──────────────────────────────────────────────────────────

class SettingsForm : Form
{
    readonly TextBox _urlBox = new() { Width = 360 };
    readonly TextBox _tokenBox = new() { Width = 360, UseSystemPasswordChar = true };
    readonly TextBox _deviceNameBox = new() { Width = 360 };
    readonly NumericUpDown _intervalBox = new() { Minimum = 5, Maximum = 300, Width = 100 };
    readonly NumericUpDown _heartbeatBox = new() { Minimum = 30, Maximum = 600, Width = 100 };
    readonly NumericUpDown _afkBox = new() { Minimum = 30, Maximum = 3600, Width = 100 };
    readonly CheckBox _autoStartBox = new() { Text = "开机自动启动 (登录时启动)", AutoSize = true };
    public AgentConfig Config { get; private set; }

    public SettingsForm(AgentConfig config)
    {
        Config = config;
        Text = "Asashiki Windows Agent 设置";
        Size = new Size(540, 420);
        MinimumSize = new Size(540, 420);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.Sizable;
        MaximizeBox = false;
        Padding = new Padding(20);

        _urlBox.Text = config.ServerUrl;
        _tokenBox.Text = config.Token;
        _deviceNameBox.Text = string.IsNullOrEmpty(config.DeviceName) ? "Windows" : config.DeviceName;
        _intervalBox.Value = config.ReportIntervalSeconds;
        _heartbeatBox.Value = config.HeartbeatIntervalSeconds;
        _afkBox.Value = config.AfkThresholdSeconds;
        _autoStartBox.Checked = AutoStart.IsEnabled();

        var layout = new TableLayoutPanel
        {
            AutoSize = false,
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 8,
            Padding = new Padding(10),
            ColumnStyles = {
                new ColumnStyle(SizeType.Absolute, 130),
                new ColumnStyle(SizeType.Percent, 100),
            }
        };

        Label mkLabel(string t) => new()
        {
            Text = t,
            AutoSize = true,
            Anchor = AnchorStyles.Left,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0, 8, 0, 0),
        };

        layout.Controls.Add(mkLabel("Server URL:"), 0, 0);
        layout.Controls.Add(_urlBox, 1, 0);
        layout.Controls.Add(mkLabel("Device Token:"), 0, 1);
        layout.Controls.Add(_tokenBox, 1, 1);
        layout.Controls.Add(mkLabel("设备显示名:"), 0, 2);
        layout.Controls.Add(_deviceNameBox, 1, 2);
        layout.Controls.Add(mkLabel("上报间隔(秒):"), 0, 3);
        layout.Controls.Add(_intervalBox, 1, 3);
        layout.Controls.Add(mkLabel("心跳间隔(秒):"), 0, 4);
        layout.Controls.Add(_heartbeatBox, 1, 4);
        layout.Controls.Add(mkLabel("AFK阈值(秒):"), 0, 5);
        layout.Controls.Add(_afkBox, 1, 5);

        // Auto-start checkbox spans both columns
        layout.Controls.Add(_autoStartBox, 0, 6);
        layout.SetColumnSpan(_autoStartBox, 2);

        // Buttons (Save / Cancel)
        var buttonPanel = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.RightToLeft,
            Dock = DockStyle.Fill,
            Padding = new Padding(0, 12, 0, 0),
            AutoSize = true,
        };
        var saveBtn = new Button { Text = "保存", Width = 100, Height = 32 };
        var cancelBtn = new Button { Text = "取消", Width = 100, Height = 32, Margin = new Padding(0, 0, 8, 0) };
        saveBtn.Click += (_, _) =>
        {
            Config.ServerUrl = _urlBox.Text.Trim();
            Config.Token = _tokenBox.Text.Trim();
            Config.DeviceName = string.IsNullOrWhiteSpace(_deviceNameBox.Text) ? "Windows" : _deviceNameBox.Text.Trim();
            Config.ReportIntervalSeconds = (int)_intervalBox.Value;
            Config.HeartbeatIntervalSeconds = (int)_heartbeatBox.Value;
            Config.AfkThresholdSeconds = (int)_afkBox.Value;
            // Apply auto-start
            try
            {
                if (_autoStartBox.Checked) AutoStart.Enable();
                else AutoStart.Disable();
            }
            catch (Exception ex)
            {
                MessageBox.Show($"自启动配置失败: {ex.Message}", "Asashiki Agent",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            DialogResult = DialogResult.OK;
            Close();
        };
        cancelBtn.Click += (_, _) => { DialogResult = DialogResult.Cancel; Close(); };
        buttonPanel.Controls.Add(saveBtn);
        buttonPanel.Controls.Add(cancelBtn);

        layout.Controls.Add(buttonPanel, 0, 7);
        layout.SetColumnSpan(buttonPanel, 2);

        Controls.Add(layout);
        AcceptButton = saveBtn;
        CancelButton = cancelBtn;
    }
}

// ── Application Context ────────────────────────────────────────────────────

class AgentApplicationContext : ApplicationContext
{
    readonly NotifyIcon _tray;
    readonly System.Windows.Forms.Timer _timer = new();
    AgentConfig _config;
    AgentReporter _reporter;
    readonly List<string> _logs = [];
    bool _running = true;

    public AgentApplicationContext()
    {
        _config = ConfigStore.Load();
        _reporter = new AgentReporter(_config, Log);

        var menu = new ContextMenuStrip();
        menu.Items.Add("设置", null, OnSettings);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("查看日志", null, OnShowLogs);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => { _running = false; Application.Exit(); });

        _tray = new NotifyIcon
        {
            Icon = LoadAppIcon(),
            Text = "Asashiki Windows Agent",
            ContextMenuStrip = menu,
            Visible = true
        };

        _timer.Interval = Math.Max(1000, _config.ReportIntervalSeconds * 1000);
        _timer.Tick += async (_, _) =>
        {
            if (_running) await _reporter.TickAsync();
        };
        _timer.Start();

        Log("Agent 已启动");
    }

    static Icon LoadAppIcon()
    {
        // Use the same icon that's bundled with the EXE (set via <ApplicationIcon> in csproj)
        try
        {
            var exe = Process.GetCurrentProcess().MainModule?.FileName;
            if (exe != null && File.Exists(exe))
            {
                var ic = Icon.ExtractAssociatedIcon(exe);
                if (ic != null) return ic;
            }
        }
        catch { }
        return SystemIcons.Application;
    }

    void Log(string msg)
    {
        var line = $"{DateTime.Now:MM-dd HH:mm:ss} {msg}";
        _logs.Add(line);
        if (_logs.Count > 200) _logs.RemoveAt(0);
    }

    void OnSettings(object? sender, EventArgs e)
    {
        using var form = new SettingsForm(_config);
        if (form.ShowDialog() == DialogResult.OK)
        {
            _config = form.Config;
            ConfigStore.Save(_config);
            _reporter.UpdateConfig(_config);
            _timer.Interval = Math.Max(1000, _config.ReportIntervalSeconds * 1000);
            Log("配置已更新");
        }
    }

    void OnShowLogs(object? sender, EventArgs e)
    {
        var text = string.Join("\n", _logs.TakeLast(50));
        var form = new Form
        {
            Text = "Asashiki Agent 日志",
            Size = new Size(700, 500),
            StartPosition = FormStartPosition.CenterScreen
        };
        var box = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            Dock = DockStyle.Fill,
            ScrollBars = ScrollBars.Vertical,
            Font = new Font(FontFamily.GenericMonospace, 9),
            Text = text
        };
        form.Controls.Add(box);
        form.ShowDialog();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { _tray.Dispose(); _timer.Dispose(); _reporter.Dispose(); }
        base.Dispose(disposing);
    }
}
