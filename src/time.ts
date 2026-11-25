// Timezone-aware calendar-day helpers.
// Timestamps are stored as UTC ISO strings; the API exposes activity "by day",
// where a day means a calendar day in the configured display timezone.

export const DISPLAY_TZ = process.env.DISPLAY_TZ ?? "Asia/Shanghai";

const calendarFormatters = new Map<string, Intl.DateTimeFormat>();
function localDayKey(time: number, zone: string): string {
  let formatter = calendarFormatters.get(zone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("sv-SE", {timeZone: zone, year:"numeric", month:"2-digit", day:"2-digit"});
    calendarFormatters.set(zone, formatter);
  }
  return formatter.format(time);
}

// Find the first instant of each local date independently: a day spanning a
// time-change can be 23 or 25 hours, and some zones change offset at midnight.
function startOfDate(utcMidnight: number, zone: string) {
  const date = new Date(utcMidnight).toISOString().slice(0, 10);
  let left = utcMidnight - 36 * 3600000;
  let right = utcMidnight + 36 * 3600000;
  while (left < right) {
    const middle = left + Math.floor((right - left) / 2);
    if (localDayKey(middle, zone) < date) left = middle + 1;
    else right = middle;
  }
  return new Date(left).toISOString();
}

const dateBounds = new Map<string, {start:string; end:string}>();
export function dayRangeUtc(date: string, tz: string = DISPLAY_TZ): { start: string; end: string } {
  const key = tz + "/" + date;
  const cached = dateBounds.get(key);
  if (cached) return cached;
  const midnight = Date.parse(date + "T00:00:00Z");
  const bounds = {start:startOfDate(midnight,tz), end:startOfDate(midnight+86400000,tz)};
  if (dateBounds.size >= 128) dateBounds.clear();
  dateBounds.set(key, bounds);
  return bounds;
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
