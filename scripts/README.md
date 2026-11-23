# Hibi reporting helpers

These optional Python 3.11+ programs turn a running collector's data into daily Notion timelines, a private live status page, and a comparison between live sleep observations and the band's later sleep report. They use the standard library and the Asia/Shanghai calendar. Install only the services you want.

## Prepare the host

Run these commands from the repository root on a Linux host with systemd 247+ and timezone data.

```sh
sudo install -d -m 0755 /usr/local/lib/hibi
sudo install -m 0644 scripts/*.py /usr/local/lib/hibi/
sudo install -m 0755 scripts/hibi-now /usr/local/lib/hibi/hibi-now
sudo install -d -m 0700 /etc/hibi
sudo install -m 0600 scripts/helpers.env.example /etc/hibi/helpers.env
sudo install -m 0644 scripts/*.service scripts/*.timer /etc/systemd/system/
sudo systemctl daemon-reload
```

Edit the host's `/etc/hibi/helpers.env` with the real collector and console origins, host path of the SQLite database, and the Notion resource IDs used by the features you enable. The origin and device IDs must agree with the collector's configuration. The daily writer and sleep check need read access to the database, its parent directories, and SQLite WAL files. Keep writable database access with the collector. A service account or group with existing read access can be added through a systemd override. For a containerized collector, publish its API on a loopback host port and set `HIBI_COLLECTOR_ORIGIN` to that address.

Create the credential files needed below as root-owned mode 0600 files in `/etc/hibi/`. The services use systemd's `LoadCredential` to read them. Keep runtime credentials out of version control.

| File | Used by |
| --- | --- |
| `notion-token` | Your Notion integration with read, update, and insert access to the chosen private data source |
| `context-read-token` | Live status and sleep checks. Its SHA-256 digest must match the collector's configured verifier |
| `notion-now-bridge.token` | Shared by the private Unix socket bridge and collector |

For example, when the compose project is at `/srv/hibi`, you can grant a dedicated read group access to just the database directory using Linux POSIX ACLs. The parent path must allow directory traversal.

```sh
getent group hibi-readers >/dev/null || sudo groupadd --system hibi-readers
sudo setfacl -m g:hibi-readers:rx,d:g:hibi-readers:r-X /srv/hibi/data
sudo find /srv/hibi/data -maxdepth 1 -type f -name 'device-timeline.sqlite*' -exec setfacl -m g:hibi-readers:r-- {} +
```

For each database reader you enable, put `SupplementaryGroups=hibi-readers` under `[Service]` in its systemd drop-in (`systemctl edit hibi-notion.service` or `systemctl edit hibi-sleep-check.service`). If the database lives under a user's home directory, choose a service-readable database mount outside `/home`. The sample services use `ProtectHome=yes`.

## Set up the Notion destination

For daily pages, create a private Notion database, share it with the integration, and set `HIBI_NOTION_DATA_SOURCE_ID` to its data source UUID. The writer uses the following property names. It keeps manually added properties and page content.

| Property | Notion type |
| --- | --- |
| 日期 | Title |
| 记录日, 最近核验 | Date |
| 已记录设备, 有记录的分钟 | Number |
| 健康同步 | Select |
| 设备使用, 身体记录 | Rich text |

For private live status and sleep writeback, create one page in the same data source and leave its date property empty. Keep the page unpublished. Add **three separate paragraph blocks** for status, browser confirmation, and sleep results. Set `HIBI_NOTION_DATABASE_ID`, `HIBI_NOTION_PAGE_ID`, and the corresponding status, pair, and sleep block IDs in the environment file. The programs check the page and paragraph ownership before updating private content.

## Run and verify

- **Daily timelines.** Start `sudo systemctl start hibi-notion.service` and open today's date in Notion. It should contain a visible overview followed by hourly toggles. Enable `sudo systemctl enable --now hibi-notion.timer` to repeat at 08:05, 10:05, 12:05, and 23:55 Shanghai time. The writer catches up older records in bounded batches. With the same service credentials and state directory, `hibi_notion.py --backfill-history` processes all outstanding source dates. `--dry-run --db PATH YYYY-MM-DD` previews a date locally without writing to Notion.
- **Private live page.** Start `sudo systemctl enable --now hibi-now-bridge.service`. Make `/run/hibi-now/refresh.sock` reachable from the collector. For a root-run collector container, bind-mount the runtime directory read-only and mount the shared bridge token as a separate read-only file at the collector's configured `config/notion-now-bridge.token`. Set the same `HIBI_NOTION_PAGE_ID` and `HIBI_CONSOLE_ORIGIN` on the collector. Visit `/now` signed into an authorized Notion account. This refreshes the existing page. The console can also request browser confirmation through its dedicated paragraph.
- **Overnight sleep check.** Start `sudo systemctl enable --now hibi-sleep-check.timer` and run `sudo systemctl start hibi-sleep-check.service` for an immediate check. The timer runs every five minutes. By default, samples are collected from 20:00 to 16:00 the following day in separate date directories. The first run after the deadline freezes that night's evidence. Subsequent checks publish meaningful changes to the configured paragraph. Each result compares actual live queries with direct band sleep reports and records missed polls. `HIBI_SLEEP_START_HOUR` and `HIBI_SLEEP_END_HOUR` select a different overnight window. For a finite one-off run, invoke `hibi_sleep_check.py --start ISO_TIME --deadline ISO_TIME --db PATH --state-dir DIR --token-file FILE`. Use `--report` to read saved evidence or `--json` for structured output. Evidence files contain personal health timestamps and remain under the service's private state directory. Apply your own retention policy there.
- **CLI.** Run `/usr/local/lib/hibi/hibi-now` with `HIBI_CONSOLE_ORIGIN` and a protected `HIBI_CONTEXT_READ_TOKEN` or `HIBI_BODY_READ_TOKEN` in the environment. The CLI also accepts a mode 0600 current-user-owned token file in `~/.config/hibi/` named `context-read-token` or `body-read-token`. Use `--from` and `--to` to query a time range.

Check `systemctl list-timers 'hibi-*'` and the journal of each installed service. To run the synthetic regression tests from the checkout, use `python3 -B -m unittest discover -s tests -p 'test_hibi*.py'`.
