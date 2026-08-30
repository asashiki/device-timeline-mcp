import { readFileSync } from "node:fs";
import { z } from "zod/v3";

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
  } catch (error) {
    throw new Error(`DEVICE_TOKENS is not valid JSON: ${(error as Error).message}`);
  }
  const tokens = deviceTokensSchema.parse(parsed);
  const ids = new Set<string>();
  const credentials = new Set<string>();
  for (const token of tokens) {
    if (ids.has(token.deviceId)) throw new Error(`Duplicate deviceId in tokens: ${token.deviceId}`);
    if (credentials.has(token.token)) throw new Error("Every device must use a unique token.");
    ids.add(token.deviceId);
    credentials.add(token.token);
  }
  return tokens;
}

export interface AppConfig {
  port: number;
  host: string;
  dbPath: string;
  labelsPath: string;
  publicBaseUrl: string;
  allowedOrigins: string[];
  allowedHosts: string[];
  activityGraceSeconds: number;
  retentionDays: number;
  mcpHttpEnabled: boolean;
  mcpHttpPath: string;
  mcpHttpToken: string | null;
  readApiToken: string | null;
  allowUnauthenticatedReadApi: boolean;
  mcpAllowUnauthenticated: boolean;
  mcpAuthPassword: string;
  mcpAuthTokenSecret: string;
  mcpOAuthScope: string;
  mcpOAuthAllowLegacyResourceOmission: boolean;
  deviceTokens: DeviceToken[];
}

function parseList(value: string | undefined, fallback: string[]): string[] {
  const items = (value ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  return items.length > 0 ? items : fallback;
}

function parseBoolean(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value.trim() === "") return fallback;
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`Expected true or false, received: ${value}`);
}

function normalizePath(value: string | undefined, fallback: string): string {
  const path = value?.trim() || fallback;
  return path.startsWith("/") ? path : `/${path}`;
}

export function loadConfig(options: { requirePublicBaseUrl?: boolean } = {}): AppConfig {
  const port = Number.parseInt(process.env.PORT ?? "4823", 10);
  if (!Number.isFinite(port) || port < 1 || port > 65535) throw new Error("PORT must be 1-65535");

  let publicBaseUrl = process.env.PUBLIC_BASE_URL?.trim().replace(/\/$/, "") || "";
  if (!publicBaseUrl) {
    if (options.requirePublicBaseUrl) {
      throw new Error("PUBLIC_BASE_URL is required in production for OAuth audience binding.");
    }
    publicBaseUrl = `http://127.0.0.1:${port}`;
  }
  const publicUrl = new URL(publicBaseUrl);
  if (publicUrl.username || publicUrl.password || publicUrl.search || publicUrl.hash || publicUrl.pathname !== "/") {
    throw new Error("PUBLIC_BASE_URL must be an origin without credentials, path, query, or fragment.");
  }
  if (
    options.requirePublicBaseUrl &&
    publicUrl.protocol !== "https:" &&
    !["localhost", "127.0.0.1", "[::1]"].includes(publicUrl.hostname)
  ) {
    throw new Error("PUBLIC_BASE_URL must use HTTPS outside localhost in production.");
  }

  const readApiToken = process.env.READ_API_TOKEN?.trim() || null;
  const allowUnauthenticatedReadApi = parseBoolean(process.env.ALLOW_UNAUTHENTICATED_READ_API, false);
  if (!readApiToken && !allowUnauthenticatedReadApi) {
    throw new Error(
      "READ_API_TOKEN is required. Set ALLOW_UNAUTHENTICATED_READ_API=true only for an isolated trusted network.",
    );
  }

  const mcpHttpEnabled = parseBoolean(process.env.MCP_HTTP_ENABLED, true);
  const mcpHttpToken = process.env.MCP_HTTP_TOKEN?.trim() || null;
  const mcpAuthPassword = process.env.MCP_AUTH_PASSWORD ?? "";
  const mcpAllowUnauthenticated = parseBoolean(process.env.MCP_ALLOW_UNAUTHENTICATED, false);
  if (mcpHttpEnabled && !mcpHttpToken && !mcpAuthPassword && !mcpAllowUnauthenticated) {
    throw new Error(
      "Remote MCP needs MCP_AUTH_PASSWORD or MCP_HTTP_TOKEN. Set MCP_ALLOW_UNAUTHENTICATED=true only for an isolated trusted network.",
    );
  }

  const mcpAuthTokenSecret = process.env.MCP_AUTH_TOKEN_SECRET?.trim() || mcpAuthPassword;
  if (mcpAuthPassword && !mcpAuthTokenSecret) {
    throw new Error("MCP_AUTH_TOKEN_SECRET is required when OAuth is enabled.");
  }

  return {
    port,
    host: process.env.HOST ?? "0.0.0.0",
    dbPath: process.env.DB_PATH ?? "./data/device-timeline.sqlite",
    labelsPath: process.env.LABELS_PATH ?? "./config/app-labels.json",
    publicBaseUrl,
    allowedOrigins: parseList(process.env.ALLOWED_ORIGINS ?? process.env.CORS_ORIGIN, [publicUrl.origin]),
    allowedHosts: parseList(process.env.ALLOWED_HOSTS, [publicUrl.hostname, "localhost", "127.0.0.1", "[::1]"]),
    activityGraceSeconds: Number(process.env.ACTIVITY_GRACE_SECONDS ?? 120),
    retentionDays: Number(process.env.RETENTION_DAYS ?? 60),
    mcpHttpEnabled,
    mcpHttpPath: normalizePath(process.env.MCP_HTTP_PATH, "/mcp"),
    mcpHttpToken,
    readApiToken,
    allowUnauthenticatedReadApi,
    mcpAllowUnauthenticated,
    mcpAuthPassword,
    mcpAuthTokenSecret,
    mcpOAuthScope: process.env.MCP_OAUTH_SCOPE?.trim() || "timeline:read health:read",
    mcpOAuthAllowLegacyResourceOmission: parseBoolean(
      process.env.MCP_OAUTH_ALLOW_LEGACY_RESOURCE_OMISSION,
      true,
    ),
    deviceTokens: loadDeviceTokens(),
  };
}

// How long after lastSeen a device is still considered "online".
// iOS only pushes hourly (Time-of-Day shortcut) so it gets a wider window.
export function onlineWindowSeconds(platform: Platform): number {
  return platform === "ios" ? 65 * 60 : 5 * 60;
}
