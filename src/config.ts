import { readFileSync } from "node:fs";
import { z } from "zod";

export const PLATFORMS = ["android", "ios", "windows", "macos"] as const;
export type Platform = (typeof PLATFORMS)[number];

const deviceTokenSchema = z.object({
  token: z.string().min(8),
  deviceId: z.string().min(1),
  deviceName: z.string().min(1),
  platform: z.enum(PLATFORMS),
});
export type DeviceToken = z.infer<typeof deviceTokenSchema>;

const deviceTokensSchema = z.array(deviceTokenSchema);

function loadDeviceTokens(): DeviceToken[] {
  const file = process.env.DEVICE_TOKENS_FILE?.trim();
  const raw = file ? readFileSync(file, "utf8") : process.env.DEVICE_TOKENS_JSON;
  if (!raw || !raw.trim()) {
    throw new Error(
      "No device tokens configured. Set DEVICE_TOKENS_JSON or DEVICE_TOKENS_FILE (see .env.example).",
    );
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new Error(`DEVICE_TOKENS is not valid JSON: ${(err as Error).message}`);
  }
  const tokens = deviceTokensSchema.parse(parsed);
  const ids = new Set<string>();
  for (const t of tokens) {
    if (ids.has(t.deviceId)) throw new Error(`Duplicate deviceId in tokens: ${t.deviceId}`);
    ids.add(t.deviceId);
  }
  return tokens;
}

export interface AppConfig {
  port: number;
  host: string;
  dbPath: string;
  labelsPath: string;
  corsOrigin: string;
  activityGraceSeconds: number;
  deviceTokens: DeviceToken[];
}

export function loadConfig(): AppConfig {
  return {
    port: Number(process.env.PORT ?? 4200),
    host: process.env.HOST ?? "0.0.0.0",
    dbPath: process.env.DB_PATH ?? "./data/device-timeline.sqlite",
    labelsPath: process.env.LABELS_PATH ?? "./config/app-labels.json",
    corsOrigin: process.env.CORS_ORIGIN ?? "*",
    activityGraceSeconds: Number(process.env.ACTIVITY_GRACE_SECONDS ?? 120),
    deviceTokens: loadDeviceTokens(),
  };
}

// How long after lastSeen a device is still considered "online".
// iOS only pushes hourly (Time-of-Day shortcut) so it gets a wider window.
export function onlineWindowSeconds(platform: Platform): number {
  return platform === "ios" ? 65 * 60 : 5 * 60;
}
