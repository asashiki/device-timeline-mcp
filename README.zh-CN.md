<div align="center">

<img src="https://cdn.jsdelivr.net/gh/asashiki/asashiki-design@main/assets/brand/asashiki-mark-color-t.png" alt="Asashiki" width="84" />

![MCP](https://img.shields.io/badge/MCP-server-6b6570?style=flat-square&labelColor=221f26)
![Self-hosted](https://img.shields.io/badge/Self--hosted-single_user-6b6570?style=flat-square&labelColor=221f26)
![Storage](https://img.shields.io/badge/Storage-SQLite-6b6570?style=flat-square&labelColor=221f26)
![License](https://img.shields.io/badge/License-MIT-6b6570?style=flat-square&labelColor=221f26)
[![Part of Asashiki](https://img.shields.io/badge/Part_of-Asashiki-e85d97?style=flat-square&labelColor=221f26)](https://github.com/asashiki)

</div>

# device-timeline-mcp

> [English](README.md) | **中文**

自托管、单用户的**多设备活动时间线收集器**，内置 **MCP 服务器**。

它记录*你此刻在哪台设备上用什么应用*，覆盖 **Android、iOS、Windows、macOS**，统一存进一个 SQLite 数据库，并通过三种方式暴露出来：

- 一个**只读 HTTP API**（给你自己的状态页 / 前端用）；
- 一个**网页控制台**，用来直接看数据；
- 一个 **MCP 服务器**，让 AI 助手（Claude Desktop、Claude Code…）回答"他现在在干嘛？" / "今天在 B站 花了多久？"。

单用户、自托管、没有账号系统。你跑一个收集器，每台设备用各自的 token 上报。

---

## 架构

```
┌─────────────┐   HTTPS POST /api/devices/report（Bearer token）
│  各端采集端  │ ────────────────────────────────────┐
│ android/ios │                                                 ▼
│ windows/mac │                                         ┌─────────────────┐
└─────────────┘                                         │  收集器           │
                                                        │ （本服务）        │
┌─────────────┐   GET /api/devices/*（只读）             │  Fastify+SQLite  │
│  你的网页前端 │ ◀───────────────────────────────▶│  + /console      │
└─────────────┘                                         └─────────────────┘
                                                                 ▲
┌─────────────┐   stdio（跑在你的电脑上）                         │ HTTP
│ Claude /    │ ──▶  src/mcp/server.ts  ──────────────────┘
│ MCP 客户端   │      (device_status / device_timeline / device_activity_summary)
└─────────────┘
```

**收集器**跑在服务器上（Docker）。**MCP 服务器**是个很薄的 stdio 进程，跑在你 AI 客户端所在的机器上，它只是去调收集器的只读 API。

---

## 快速开始（Docker）

```bash
git clone <this-repo> device-timeline-mcp
cd device-timeline-mcp
cp .env.example .env
# 编辑 .env → 配 PUBLIC_BASE_URL、READ_API_TOKEN、OAuth 密钥和每台设备的 token
mkdir -p data && chown 1000:1000 data
docker compose up -d --build
```

打开 `http://<host>:4823/console` —— 设备开始上报后就会一台台出现。健康检查：`curl http://<host>:4823/health`。

### 不用 Docker（Node ≥ 22.5）

```bash
npm install
cp .env.example .env   # 编辑它
npm run build && npm start      # 或：npm run dev
```

> **为什么要 Node 22.5+？** 收集器用的是 Node 自带的 `node:sqlite`，不需要编译任何原生模块。

---

## 三步上手

1. **起收集器**：照上面 Docker 那段跑起来，确认 `/console` 能打开。
2. **配 token**：在 `.env` 的 `DEVICE_TOKENS_JSON` 里给每台设备一行（见下一节），每个 token 用 `openssl rand -hex 32` 生成。
3. **装采集端**：去 [`reporters/`](reporters/) 对应平台的目录，填上**服务器地址**和这台设备的 **token**，剩下的它自己上报。

---

## 设备 token 与多设备命名

每台设备用自己的 token 认证。在 `.env` 的 `DEVICE_TOKENS_JSON` 里定义（也可以用 `DEVICE_TOKENS_FILE` 指向一个 JSON 文件）。

```jsonc
[
  {"token":"<openssl rand -hex 32>","deviceId":"android-phone","deviceName":"我的手机","platform":"android"},
  {"token":"<openssl rand -hex 32>","deviceId":"android-tablet","deviceName":"平板 Y700","platform":"android"},
  {"token":"<openssl rand -hex 32>","deviceId":"windows-pc","deviceName":"台式机","platform":"windows"},
  {"token":"<openssl rand -hex 32>","deviceId":"mac-laptop","deviceName":"MacBook","platform":"macos"},
  {"token":"<openssl rand -hex 32>","deviceId":"ios-phone","deviceName":"iPhone","platform":"ios"}
]
```

**"同平台两台设备"的关键点**：设备靠 **`deviceId`** 区分，而*不是*靠平台。如果你有一台 Android **手机**和一台 Android **平板**，给它们**不同的 `deviceId`**（比如 `android-phone` 和 `android-tablet`），各配**各自的 token**。两台都是 `platform: "android"`，都落进同一批表；查询时都能按 `deviceId` 过滤，控制台里也分成两张卡片。

- `deviceId`：kebab-case、唯一、稳定。它是连接键，**之后别改**。
- `deviceName`：随便写的显示名（控制台 / MCP 输出里展示）。
- 每个 token 用 `openssl rand -hex 32` 生成。

---

## 各平台采集端安装

采集端都在 [`reporters/`](reporters/)，每个都有自己的 README：

| 平台 | 源码 | 说明 |
|---|---|---|
| Android | [`reporters/android`](reporters/android) | 纯时间线 Kotlin 应用（前台服务） |
| Windows | [`reporters/windows`](reporters/windows) | .NET 托盘程序，单文件 exe |
| macOS | [`reporters/macos`](reporters/macos) | Python + launchd 守护进程 |
| iOS | [`reporters/ios`](reporters/ios) | 快捷指令自动化（没有可安装的 app） |

桌面端 / Android 采集端做的是同一件事：每 ~10 秒采样一次前台应用 + 窗口标题，带着自己的 Bearer token `POST /api/devices/report`。你要配的只有**服务器地址**和设备 **token**。

### Android（手机 / 平板）

从 [`reporters/android`](reporters/android) 编译 APK（Android Studio 或 `./gradlew assembleRelease`），然后：

1. 把 APK 装到设备上。
2. 打开一次 → 授予**使用情况访问**权限（设置 → 应用 → 特殊权限 → 使用情况访问），并为它**关闭电池优化**（让它后台一直上报）。
3. 在 app 设置里填：
   - **服务器地址**：`https://<your-host>`（或局域网 `http://host:4823`）
   - **Token**：这台设备的 token
4. **手机 + 平板**的话，两台都装，手机填**手机的 token**、平板填**平板的 token**。区别就这一点。

> **后台留存**：Android 采集端用了前台服务（Android 14+ 用 `specialUse` 类型，绕开 dataSync 在 Android 15 上的 6 小时/24 小时运行上限）、`WAKE_LOCK`、`START_STICKY`、`onTaskRemoved` 重新拉起、开机自启（BootReceiver），外加**两层看门狗**：AlarmManager `setAlarmClock`（绕过 Doze 和 MIUI/HyperOS 限制）作为主恢复路径，WorkManager 周期任务（每 ~15 分钟）作为兜底。

### iOS —— 快捷指令自动化

iOS 没有后台采集端，靠 **快捷指令** app 里两个**个人自动化**来驱动。

**A. "打开 App" 自动化**（打开任意被追踪的 app 时触发）：

- 新建自动化 → **App** → 选要追踪的 app → **已打开**。
- 动作：**获取 URL 内容** → `POST https://<host>/api/devices/ios/app-event`，请求头 `Authorization: Bearer <ios token>`，JSON body `{"app":"<App 名>","action":"open"}`。
- **关掉**"运行前询问"。

![iOS 打开应用自动化](https://picture-img.leqazwsxedc.workers.dev/image_2026-05-31_06-17-49.png)

**B. "关闭 App" 自动化** —— 同上，把 `action` 设成 `close`。

![iOS 关闭应用自动化](https://picture-img.leqazwsxedc.workers.dev/image_2026-05-31_06-17-56.png)

完整快捷指令走法和 body 格式见 [`reporters/ios`](reporters/ios)。

### Windows

1. 把采集端 `.exe` 放到机器上（单文件、自包含）。
2. 第一次运行 → 托盘图标 → **设置**：
   - **服务器地址**：`https://<host>`
   - **Token**：`windows-pc` 的 token
3. 勾上**开机启动**（写一条 `HKCU\…\Run` 注册表）。

### macOS

1. `pip3 install -r requirements.txt`。
2. 给运行它的终端/程序授予**辅助功能**权限（系统设置 → 隐私与安全性 → 辅助功能）—— 读窗口标题需要。
3. 编辑 `config.json` → 填 `serverUrl` + `token`（`mac-laptop` 的 token）。
4. 装成 launchd 守护进程自启（见 `reporters/macos`）。

---

## 接入 MCP 客户端

有两种接法，取决于你的 AI 客户端是本地跑 MCP（stdio）还是连一个远程 URL（HTTP）。

### A. 本地客户端 —— stdio（Claude Desktop、Claude Code）

在你客户端所在的机器上跑自带的 stdio MCP 服务器，指向收集器：

```jsonc
// Claude Desktop: claude_desktop_config.json
{
  "mcpServers": {
    "device-timeline": {
      "command": "node",
      "args": ["/abs/path/device-timeline-mcp/dist/mcp/server.js"],
      "env": {
        "MCP_API_BASE": "https://<your-host>",
        "MCP_API_TOKEN": "<READ_API_TOKEN>"
      }
    }
  }
}
```

### B. 远程客户端 —— HTTP / `/mcp`（claude.ai 网页版）

收集器还内置了一个 **streamable-HTTP 的 MCP 端点**，挂在 `/mcp`（同一个服务、同一个端口，不用额外进程）。**claude.ai** 这类网页客户端没法用 stdio，只能连 URL。把客户端的「自定义 / 远程 MCP 连接器」指向：

```
https://<你的域名>/mcp
```

- **域名你自己准备**：用任意反向代理 / 隐道把收集器套上 HTTPS，再在客户端里加这个远程连接器。
- claude.ai **只认 HTTPS** —— 纯 `http://IP:端口` 不行，所以必须走反代上 HTTPS。
- 远程 MCP 现在**默认拒绝无认证访问**。优先使用 `MCP_AUTH_PASSWORD` 提供的 OAuth 2.1 + S256 PKCE；`MCP_HTTP_TOKEN` 只作为可选静态 Bearer 兼容。OAuth Token 通过 RFC 8707 `resource` 绑定到规范 MCP URL。想彻底关掉就设 `MCP_HTTP_ENABLED=false`。
- 同一个端点同时服务 MCP 2026-07-28 和旧版 2025 客户端；Host / Origin 检查发生在 Bearer 处理之前。

暴露的工具（两种传输方式一致）：

| 工具 | 回答什么 |
|---|---|
| `device_status` | 每台设备此刻在干嘛（在线、前台应用、电量） |
| `device_timeline` | 某一天的时间顺序活动（可按 `deviceId` 过滤） |
| `device_activity_summary` | 某一天每个应用的使用时长汇总 |
| `health_summary` | 最近一段时间的健康指标合计 / 最新值 |
| `health_records` | 某一健康指标的有界原始样本 |

---

## 只读 API（给你自己的前端）

所有 `GET /api/*` 都要求 `Authorization: Bearer <READ_API_TOKEN>`；它与设备上报 Token、MCP 凭据刻意分离。浏览器来源通过 `ALLOWED_ORIGINS` 限制。所有时间戳是 **UTC ISO**；`date=` 指 `DISPLAY_TZ`（默认 `Asia/Shanghai`）下的某个日历日。

| 端点 | 用途 |
|---|---|
| `GET /health` | 存活检查 + schema 版本 |
| `GET /api/devices/current` | 每台设备最新状态（含 `appName`、`live` 文案） |
| `GET /api/devices/timeline-query?date=&deviceId=&limit=` | 活动列表 |
| `GET /api/devices/activity-summary?date=&deviceId=` | 每个应用的时长汇总 |
| `GET /api/app-labels` | 原始的 appId → {name, desc} 映射 |
| `GET /api/devices/health/summary?hours=&deviceId=` | 健康数据汇总（每类指标 sum/min/max/avg + 最新值） |
| `GET /api/devices/health/records?type=&hours=&deviceId=&limit=` | 某类健康指标的原始样本（倒序） |
| `POST /api/devices/report` | **上报入口**（Android/桌面采集端，Bearer token） |
| `POST /api/devices/ios/app-event` | **上报** iOS 开/关（Bearer token） |
| `POST /api/devices/ios/snapshot` | **上报** iOS 电量/专注快照（Bearer token） |
| `POST /api/devices/health` | **上报** Health Connect 健康样本（Bearer token，`{records:[…]}`） |

`current` / `timeline` 的响应里带了服务端算好的 `appName` 和 `live`（一句自然语言），前端不用自己重写标签逻辑。`/console` 页面就是个现成示例。

0.1 → 0.2 的蓝绿部署、安全模型、诊断与回滚步骤见 [`docs/SECURE-REMOTE-MCP-0.2.zh-CN.md`](docs/SECURE-REMOTE-MCP-0.2.zh-CN.md)。

---

## 自定义应用名

`config/app-labels.json` 把 `appId`（Android 包名、Windows 进程名、macOS bundle id、或 iOS 应用名）映射到友好名字 + 状态短语：

```json
{ "tv.danmaku.bili": { "name": "哔哩哔哩", "desc": "正在刷 B站~" } }
```

改完**保存**即可 —— 收集器会**热重载**（不用重启、不用重新构建），因为它从挂载卷（`./config`）读。未知应用回退成路径最后一段首字母大写。

---

## 数据模型

两张表（见 `src/db/migrations.ts`），用 `PRAGMA user_version` 做版本化迁移。

- **`device_states`** —— 每台设备一行：当前前台应用、最后上报时间、`extra` JSON。
- **`device_activities`** —— 追加式的"同一应用、时间连续"的活动段（相邻采样若在 `ACTIVITY_GRACE_SECONDS` 内会合并成一行）。

SQLite 文件落在 `./data` 卷上。

## 数据保留（自动清理）

活动历史会自动清理，DB 不会无限膨胀。收集器在启动时、以及之后每 24 小时，会删除 `device_activities` 里超过 `RETENTION_DAYS` 天的记录（默认 **60** 天，约两个月）。`device_states`（每台设备一行）不清理。设 `RETENTION_DAYS=0` 可关闭清理、全部保留。

## 备份

备份**故意交给你自己**——每个人需求不一样（备到另一台 VPS、推到远程数据库、对象存储等），所以收集器不内置任何备份方案。DB 就是 `./data` 卷上的单个文件，最简单就是复制它；运行中想要一致快照，用 `sqlite3 <db> '.backup <目标>'` 或 `VACUUM INTO <目标>`。

## 许可证

MIT。

---

<div align="center">
<sub>Asashiki 项目集的一员 · 视觉语言见 <a href="https://github.com/asashiki/asashiki-design">Asashiki Design</a> · 墨と桜。</sub>
</div>
