# device-timeline-mcp 0.2：安全远程 MCP 迁移

这个项目保存窗口标题、应用活动与健康数据。0.2 的目标不是把一个静态 Token 换成另一个，而是建立三个不能互相冒充的权限域。

| 权限域 | 凭据 | 能做什么 | 不能做什么 |
|---|---|---|---|
| 设备写入 | `DEVICE_TOKENS_JSON` 每设备 Token | 上报这台设备的状态/健康样本 | 读取时间线、连接 MCP |
| 控制台/HTTP 读取 | `READ_API_TOKEN` | 调用 `GET /api/*` | 写入设备数据、连接 MCP |
| AI 远程读取 | OAuth Token 或 `MCP_HTTP_TOKEN` | 调用只读 MCP 工具 | 调 REST API、伪装设备 |

## 协议与授权升级

- MCP SDK 2.0；同一 `/mcp` 服务 2026-07-28 与旧版 2025。
- OAuth 2.1 Authorization Code + S256 PKCE。
- 动态客户端注册只接受精确 HTTPS redirect URI，HTTP 仅允许 loopback。
- 授权码绑定 client、redirect、scope 与 RFC 8707 `resource`，只能兑换一次。
- 访问 Token 的 `aud` 固定为 `PUBLIC_BASE_URL + MCP_HTTP_PATH`。
- Host/Origin 在认证前检查。
- 所有工具标注只读、非破坏、幂等、非开放世界。
- 容器以非 root 用户运行，并使用只读根文件系统、cap drop 与资源上限。

## 从 0.1 迁移

旧实例继续运行，不要直接覆盖。复制旧 `.env` 到测试目录并增加：

```dotenv
PUBLIC_BASE_URL=https://timeline.example.com
ALLOWED_HOSTS=timeline.example.com
ALLOWED_ORIGINS=https://timeline.example.com

READ_API_TOKEN=<openssl rand -hex 32>

MCP_AUTH_PASSWORD=<long authorization password>
MCP_AUTH_TOKEN_SECRET=<openssl rand -hex 32>
MCP_OAUTH_SCOPE=timeline:read health:read
MCP_OAUTH_ALLOW_LEGACY_RESOURCE_OMISSION=true
```

不要复用任何设备 Token。若本地 stdio 访问远程收集器，额外传入：

```json
{
  "MCP_API_BASE": "https://timeline.example.com",
  "MCP_API_TOKEN": "<READ_API_TOKEN>"
}
```

## 蓝绿验证

1. 复制 SQLite 数据库与配置目录到测试实例。
2. 在另一个本机端口/临时域名启动 0.2，不改旧反代。
3. 检查 `/healthz` 与 `/diagnostics/security`。
4. 验证无 Token 的 `GET /api/devices/current` 返回 401，设备 Token 也返回 401，只有 `READ_API_TOKEN` 成功。
5. 用测试设备 Token 上报一条数据，确认 202。
6. 分别用 MCP 2026 与旧客户端列出 5 个只读工具。
7. 完成一次 OAuth 授权，并确认连接器能读取状态/时间线。
8. 切换反代；异常时切回旧端口。0.2 不改变 SQLite schema，回滚无需降级数据库。

## 自动验证状态

已在恢复后的功能分支重新完成：

- `npm run typecheck` 通过
- `npm test` 4/4 通过
- MCP 2026 与 legacy 客户端协商通过
- 设备写入、REST 读取、MCP 三类凭据隔离测试通过
- OAuth redirect / PKCE / resource / audience / code replay 测试通过
- Host header 拒绝测试通过

实现与测试都已提交到远端 `agent/secure-remote-mcp-v2`，不再依赖临时工作区。

## 显式兼容开关

`ALLOW_UNAUTHENTICATED_READ_API=true` 与 `MCP_ALLOW_UNAUTHENTICATED=true` 只用于受信私网迁移。变量名刻意很长，因为开启它们等于主动放弃默认保护，不应成为随手配置。
