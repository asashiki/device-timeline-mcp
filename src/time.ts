// Timezone-aware calendar-day helpers.
// Timestamps are stored as UTC ISO strings; the API exposes activity "by day",
// where a day means a calendar day in the configured display timezone.

export const DISPLAY_TZ = process.env.DISPLAY_TZ ?? "Asia/Shanghai";

function tzOffsetMs(tz: string, atUtcMs: number): number {
  const d = new Date(atUtcMs);
  const local = new Date(d.toLocaleString("en-US", { timeZone: tz }));
  const utc = new Date(d.toLocaleString("en-US", { timeZone: "UTC" }));
  return local.getTime() - utc.getTime();
}

// Returns the [start, end) UTC ISO bounds for a local calendar day.
// Assumes a fixed-offset zone (e.g. Asia/Shanghai has no DST); good enough
// for the zones this tool targets.
export function dayRangeUtc(date: string, tz: string = DISPLAY_TZ): { start: string; end: string } {
  const baseStart = new Date(`${date}T00:00:00Z`).getTime();
  const offset = tzOffsetMs(tz, baseStart);
  const startMs = baseStart - offset;
  const endMs = startMs + 24 * 60 * 60 * 1000;
  return { start: new Date(startMs).toISOString(), end: new Date(endMs).toISOString() };
}

// Today's calendar date (YYYY-MM-DD) in the display timezone.
export function todayInTz(tz: string = DISPLAY_TZ): string {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: tz }).format(new Date());
}

// Normalize any agent-supplied timestamp to a canonical UTC ISO string so
// lexical range comparisons in SQL are reliable.
export function toUtcIso(value: string | undefined | null): string {
  if (!value) return new Date().toISOString();
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? new Date().toISOString() : d.toISOString();
}
