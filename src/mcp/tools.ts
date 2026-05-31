import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

interface DeviceCurrent {
  fetchedAt: string;
  devices: Array<{
    deviceName: string;
    platform: string;
    isOnline: boolean;
    lastSeenAt: string;
    live: string;
    extra: Record<string, unknown> | null;
  }>;
}
interface Timeline {
  date: string;
  activities: Array<{ startedAt: string; durationSeconds: number | null; deviceId: string; live: string }>;
}
interface Summary {
  date: string;
  totalSeconds: number;
  perApp: Array<{ appName: string; appId: string; totalSeconds: number; count: number }>;
}

function fmtDuration(s: number | null): string {
  if (!s) return "in progress";
  if (s < 60) return `${s}s`;
  const m = Math.round(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}h${m % 60}m` : `${m}m`;
}
function text(s: string) {
  return { content: [{ type: "text" as const, text: s }] };
}

// Builds a fully-configured MCP server whose tools read from the collector's
// read-only HTTP API at `apiBase`. Used by both the stdio entrypoint and the
// HTTP (streamable) transport mounted on the collector.
export function createMcpServer(apiBase: string): McpServer {
  const base = apiBase.replace(/\/$/, "");
  async function api<T>(path: string): Promise<T> {
    const res = await fetch(base + path, { headers: { accept: "application/json" } });
    if (!res.ok) throw new Error(`${path} → HTTP ${res.status}`);
    return (await res.json()) as T;
  }

  const server = new McpServer({ name: "device-timeline-mcp", version: "0.1.0" });

  server.tool(
    "device_status",
    "Current real-time state of every tracked device: which app is in the foreground, online/offline, battery.",
    {},
    async () => {
      const data = await api<DeviceCurrent>("/api/devices/current");
      if (!data.devices.length) return text("No devices have reported yet.");
      const lines = data.devices.map((d) => {
        const batt = d.extra && typeof d.extra.battery_percent === "number" ? ` · ${d.extra.battery_percent}%` : "";
        return `- ${d.deviceName} [${d.platform}] ${d.isOnline ? "🟢" : "⚪"} ${d.live}${batt}`;
      });
      return text(`Devices (as of ${data.fetchedAt}):\n${lines.join("\n")}`);
    },
  );

  server.tool(
    "device_timeline",
    "Chronological activity timeline for a day (default today). Optionally filter by deviceId.",
    {
      date: z.string().optional().describe("Calendar day YYYY-MM-DD in the server's display timezone"),
      deviceId: z.string().optional(),
      limit: z.number().int().positive().max(500).optional(),
    },
    async ({ date, deviceId, limit }) => {
      const qs = new URLSearchParams();
      if (date) qs.set("date", date);
      if (deviceId) qs.set("deviceId", deviceId);
      qs.set("limit", String(limit ?? 100));
      const data = await api<Timeline>(`/api/devices/timeline-query?${qs}`);
      if (!data.activities.length) return text(`No activity recorded for ${data.date}.`);
      const lines = data.activities.map(
        (a) => `${a.startedAt.slice(11, 16)} ${a.live} · ${fmtDuration(a.durationSeconds)} [${a.deviceId}]`,
      );
      return text(`Timeline ${data.date}:\n${lines.join("\n")}`);
    },
  );

  server.tool(
    "device_activity_summary",
    "Per-app usage totals for a day (default today): screen time and switch counts, sorted by time.",
    {
      date: z.string().optional(),
      deviceId: z.string().optional(),
    },
    async ({ date, deviceId }) => {
      const qs = new URLSearchParams();
      if (date) qs.set("date", date);
      if (deviceId) qs.set("deviceId", deviceId);
      const data = await api<Summary>(`/api/devices/activity-summary?${qs}`);
      if (!data.perApp.length) return text(`No usage recorded for ${data.date}.`);
      const lines = data.perApp.map((p) => `- ${p.appName} (${p.appId}): ${fmtDuration(p.totalSeconds)} ×${p.count}`);
      return text(`Usage ${data.date} (total ${fmtDuration(data.totalSeconds)}):\n${lines.join("\n")}`);
    },
  );

  return server;
}
