# Hibi 手环

Android 上的「今日」显示最近睡眠时段、今天每小时已收到的步数，以及最近一次心率。没有记录的小时留空。设备页显示手环电量，可以震动、发文字、查看提醒、设置闹钟及读取手环资料。「设置」管理手机记录、服务器和权限。

Hibi 直接与小米手环 9 Pro 建立经典蓝牙连接。健康记录每五分钟同步一次，两次同步之间也能接收手环主动发来的睡眠文件。手机断网时，已有健康记录留在本机，恢复联网后补传。手机或手环断线时，Hibi 自动重新连接。

## Agent 直接使用

在 Agent 所在的机器设置 `HIBI_CONSOLE_ORIGIN` 为自己的 Hibi 服务器地址。`npm run configure` 会生成 `private/wrist-control-token`。让 `HIBI_WRIST_TOKEN_FILE` 指向这个文件。调用者可以运行仓库内的 Python 3 脚本，也可以直接请求 HTTPS 接口。

```sh
scripts/hibi-wrist status
scripts/hibi-wrist buzz
scripts/hibi-wrist notify '起来走一走' --at '2026-12-01T15:00:00+08:00'
scripts/hibi-wrist alarms
scripts/hibi-wrist receipt <id>
scripts/hibi-wrist cancel <id>
```

`GET /api/agent/wrist` 返回手机和手环的连接状态、手环电量的测量时间，以及当前服务器接受的动作名称。用相同的 Bearer 凭据向该地址 POST，例如 `{"action":"notify","args":{"text":"喝水"},"requestId":"<UUID>"}`。用 `deliverAt` 指定时间，最多提前 30 天。重试同一条指令时复用 UUID。可用 `GET /api/agent/wrist/<id>` 查回执，或用 DELETE 取消排队中的指令。定时指令经过约定时间五分钟后过期，避免设备上线时集中响起。

| 能力 | 小米手环 9 Pro 的实测情况 |
| --- | --- |
| 手环电量、充电状态、型号、固件 | 手机可读。电量同步到 Hibi 身体状态接口。 |
| 定时文字提醒 | 已实测到表。普通文字提醒是单向通知，应用显示已发送。 |
| 寻找手环 | 手机发送短震动和停止指令。 |
| 闹钟、提醒、睡眠计划、世界时钟、通知亮屏 | 读取有设备回包。列表内的编号可供编辑或删除，不要用序号代替。 |
| 心率、血氧、压力监测设置，表上菜单，震动样式 | 已收到设备回包，具体字段随固件而异。 |
| 已有表盘、天气地点、组件、运动项目 | 可以请求列表。没有可读回复时，客户端会显示该结果。 |

`confirmed` 表示收到设备回复，`sent` 表示手机已经发送。即时指令在 90 秒内仍未送达会过期。手环电量、心率、步数、睡眠各自带有测量时间。固件安装、通讯录同步、音乐和导航尚未接入。

协议参考 [Gadgetbridge 2026.11.1 的 Xiaomi 实现](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/tag/2026.11.1/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi)。详细字段来自同版的 wear_system、wear_fitness、wear_notification、wear_clock、wear_watch_face 与 wear_weather。
