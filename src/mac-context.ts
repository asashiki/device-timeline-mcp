import { z } from "zod";

export const MAC_CONTEXT_DEVICE_ID = process.env.HIBI_MAC_DEVICE_ID?.trim() || "mac-laptop";
export const MAC_CONTEXT_TTL_MS = 75_000;
const sampledAt = z.string().max(40).datetime({ offset: true });
export const macSnapshot = z.union([
  z.object({
    sampledAt, state: z.literal("active"), recentInputAt: sampledAt.optional(),
    appName: z.string().trim().min(1).max(160).optional(),
    appId: z.string().trim().min(1).max(255).optional(),
    windowTitle: z.string().trim().min(1).max(300).optional(),
    desktop: z.number().int().min(1).max(128).optional(),
  }).strict(),
  z.object({ sampledAt, state: z.enum(["idle", "locked", "paused"]) }).strict(),
]);
type Snapshot = z.infer<typeof macSnapshot>;

// Only the most recent observation lives in process memory. The timer evicts
// the entire snapshot even when no reader requests it. Keep timestamps after
// eviction for freshness reporting and to reject delayed/reordered packets.
export class MacContext {
  private observation: Snapshot | null = null;
  private sampled = -Infinity;
  private received: number | null = null;
  private expires = 0;
  private timer?: NodeJS.Timeout;

  record(snapshot: Snapshot, now = Date.now()): boolean {
    const sampled = Date.parse(snapshot.sampledAt);
    if (!Number.isFinite(sampled) || sampled > now + 5000) return false;
    if (snapshot.state === "active" && snapshot.recentInputAt) {
      const input = Date.parse(snapshot.recentInputAt);
      if (!Number.isFinite(input) || input > sampled || sampled - input > 120_000 || input > now) return false;
    }
    if (sampled < this.sampled || now - sampled >= MAC_CONTEXT_TTL_MS) return true;
    if (sampled === this.sampled && snapshot.state === "active") return true;
    this.clear();
    this.sampled = sampled;
    this.received = now;
    this.observation = snapshot;
    this.expires = Math.min(sampled, now) + MAC_CONTEXT_TTL_MS;
    this.timer = setTimeout(() => this.clear(), this.expires - now);
    this.timer.unref();
    return true;
  }

  clear(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.observation = null;
  }

  read(now = Date.now()) {
    if (now >= this.expires) this.clear();
    const row = this.observation;
    const active = row?.state === "active" ? row : null;
    return {
      deviceId: MAC_CONTEXT_DEVICE_ID,
      state: row?.state ?? (this.received === null ? "unknown" : "stale"),
      sampledAt: this.received === null ? null : new Date(this.sampled).toISOString(),
      receivedAt: this.received === null ? null : new Date(this.received).toISOString(),
      ageSeconds: this.received === null ? null : Math.max(0, Math.floor((now - this.sampled) / 1000)),
      appName: active?.appName ?? null,
      appId: active?.appId ?? null,
      windowTitle: active?.windowTitle ?? null,
      desktop: active?.desktop ?? null,
      recentInputAt: active?.recentInputAt && now - Date.parse(active.recentInputAt) <= 120_000
        ? new Date(active.recentInputAt).toISOString() : null,
    };
  }
}
