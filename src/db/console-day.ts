import type { DatabaseSync } from "node:sqlite";
import { z } from "zod";
import { DISPLAY_TZ, dayRangeUtc } from "../time.js";
import { LIVE_HEALTH_DEVICE_ID } from "./live-health.js";

export const dayQuery = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine(value => {
    const timestamp = Date.parse(value + "T00:00:00Z");
    return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value;
  }),
}).strict();

export function consoleDate(date: string, now = new Date()) {
  const { start, end } = dayRangeUtc(date);
  return { date, from: start, to: end, timeZone: DISPLAY_TZ, asOf: now.toISOString() };
}
type Day = ReturnType<typeof consoleDate>;

interface Activity {
  id: number; deviceId: string; appId: string; customName: string | null; windowTitle: string | null;
  platform: string | null; startedAt: string; endedAt: string | null; endSource: string | null;
}

export function activityDay(db: DatabaseSync, day: Day, appLabel: (id: string) => string) {
  const devices = db.prepare("SELECT device_id AS deviceId,device_name AS deviceName,platform FROM device_states ORDER BY device_name").all();
  const rows = db.prepare(`SELECT a.id,a.device_id AS deviceId,a.app_id AS appId,a.window_title AS windowTitle,
      a.started_at AS startedAt,a.ended_at AS endedAt,d.platform,
      CASE WHEN json_valid(a.extra_json) THEN json_extract(a.extra_json,'$.endSource') END AS endSource,
      CASE WHEN json_valid(a.extra_json) THEN json_extract(a.extra_json,'$.custom_app_name') END AS customName
    FROM device_activities a LEFT JOIN device_states d ON a.device_id=d.device_id
    WHERE a.started_at < ? AND a.started_at<=? AND (a.started_at>=? OR a.ended_at>=?)
    ORDER BY a.started_at,a.id`).all(day.to, day.asOf, day.from, day.from) as unknown as Activity[];

  const activities: Array<{
    id: string; deviceId: string; appId: string; appName: string; windowTitle: string | null;
    kind: "point" | "interval"; startedAt: string; endedAt: string | null; durationSeconds: number | null;
  }> = [];
  for (const row of rows) {
    const common = { deviceId:row.deviceId, appId:row.appId, windowTitle:row.windowTitle,
      appName: typeof row.customName === "string" && row.customName.trim() || appLabel(row.appId) };
    const end = row.endedAt;
    const canMeasure = end && end <= day.asOf && end > row.startedAt &&
      (row.platform === "ios" ? row.endSource === "ios_named_close" : Date.parse(end) - Date.parse(row.startedAt) <= 86400000);
    if (canMeasure) {
      const left = row.startedAt < day.from ? day.from : row.startedAt;
      const right = end < day.to ? end : day.to;
      if (right > left) activities.push({ ...common, id:String(row.id), kind:"interval", startedAt:left,
        endedAt:right, durationSeconds:(Date.parse(right)-Date.parse(left))/1000 });
      continue;
    }
    // An unclosed app or an unmatched iOS close provides observations, not continuous usage.
    for (const [n, stamp] of [row.startedAt, end && end>row.startedAt ? end : null].entries()) {
      if (stamp && day.from <= stamp && stamp < day.to && stamp <= day.asOf)
        activities.push({ ...common, id:row.id + ":" + n, kind:"point", startedAt:stamp, endedAt:null, durationSeconds:null });
    }
  }
  activities.sort((a,b) => a.startedAt.localeCompare(b.startedAt) || a.id.localeCompare(b.id));
  return { ...day, devices, activities };
}

type Session = {startedAt:string; endedAt:string; source:string; sessionAsleepMinutes:number|null};
interface Hour {
  hour:number; from:string; to:string;
  steps:{steps:number|null; observedMinutes:number};
  heartRate:{sampleCount:number; minBpm:number|null; maxBpm:number|null; latestBpm:number|null; latestSampledAt:string|null};
  oxygenSaturation:{percent:number;sampledAt:string}|null;
  distance:{meters:number|null;observedMinutes:number};
  sleep:{recordedSessionOverlapMinutes:number|null; sessions:Session[]};
}

export function personalDay(db: DatabaseSync, day: Day, locationAccess: boolean) {
  const first = Date.parse(day.from), last = Date.parse(day.to), hours: Hour[] = [];
  const hourFormat = new Intl.DateTimeFormat("en-GB", {timeZone:day.timeZone,hour:"numeric",hourCycle:"h23"});
  for (let start=first; start<last; start+=3600000) {
    const end=Math.min(last,start+3600000);
    hours.push({
      hour:Number(hourFormat.format(start)),from:new Date(start).toISOString(),to:new Date(end).toISOString(),
      steps:{steps:null,observedMinutes:0},distance:{meters:null,observedMinutes:0},oxygenSaturation:null,
      heartRate:{sampleCount:0,minBpm:null,maxBpm:null,latestBpm:null,latestSampledAt:null},
      sleep:{recordedSessionOverlapMinutes:null,sessions:[]},
    });
  }

  // Bucket the day in a single ordered pass over each indexed metric range.
  const args = [LIVE_HEALTH_DEVICE_ID,day.from,day.to,day.asOf,day.asOf];
  const samples = "WHERE device_id=? AND recorded_at>=? AND recorded_at<? AND recorded_at<=? AND received_at<=?";
  const fields = "type,value,recorded_at AS sampledAt";
  const rows = db.prepare(`SELECT ${fields} FROM band_samples ${samples}
    UNION ALL SELECT ${fields} FROM private_band_metrics ${samples} ORDER BY sampledAt`).all(...args,...args) as unknown as
      Array<{type:string;value:number;sampledAt:string}>;
  let lastStepMinute = "", lastDistanceMinute = "";
  for (const {sampledAt,type,value} of rows) {
    const hour=hours[Math.floor((Date.parse(sampledAt)-first)/3600000)];
    if (!hour) continue;
    const minute=sampledAt.slice(0,16);
    if (type === "steps") {
      hour.steps.steps=(hour.steps.steps??0)+value;
      if (minute !== lastStepMinute) { hour.steps.observedMinutes++; lastStepMinute=minute; }
    } else if (type === "heart_rate") {
      const rate=hour.heartRate;
      rate.sampleCount++;
      rate.minBpm=Math.min(rate.minBpm??value,value);
      rate.maxBpm=Math.max(rate.maxBpm??value,value);
      rate.latestBpm=value; rate.latestSampledAt=sampledAt;
    } else if (type === "oxygen_saturation") {
      hour.oxygenSaturation={percent:value,sampledAt};
    } else if (type === "distance") {
      hour.distance.meters=(hour.distance.meters??0)+value;
      if (minute !== lastDistanceMinute) { hour.distance.observedMinutes++; lastDistanceMinute=minute; }
    }
  }
  const sessions=db.prepare(`SELECT started_at AS startedAt,ended_at AS endedAt, source,
    coalesce(asleep_minutes,CASE WHEN import_received_at<=? THEN import_minutes END) AS sessionAsleepMinutes
    FROM private_sleep_history WHERE device_id=? AND ended_at>? AND started_at<? AND ended_at<=? AND received_at<=?
      AND (superseded_at IS NULL OR superseded_at>?) ORDER BY started_at,ended_at`)
    .all(day.asOf,LIVE_HEALTH_DEVICE_ID,day.from,day.to,day.asOf,day.asOf,day.asOf) as unknown as Session[];
  for (const hour of hours) {
    let previousEnd=Date.parse(hour.from);
    for (const session of sessions) {
      if (session.startedAt>=hour.to || session.endedAt<=hour.from) continue;
      const start=Math.max(Date.parse(session.startedAt),previousEnd);
      const end=Math.min(Date.parse(session.endedAt),Date.parse(hour.to));
      const overlap=Math.max(0,end-start);
      previousEnd=Math.max(previousEnd,end);
      hour.sleep.sessions.push(session);
      hour.sleep.recordedSessionOverlapMinutes=(hour.sleep.recordedSessionOverlapMinutes??0)+overlap/60000;
    }
  }
  const locations=locationAccess ? db.prepare(`SELECT latitude,longitude,accuracy_meters AS accuracyMeters,
    sampled_at AS sampledAt,received_at AS receivedAt FROM private_phone_location
    WHERE device_id=? AND sampled_at>=? AND sampled_at<? AND sampled_at<=? AND received_at<=? ORDER BY sampled_at`)
    .all(...args) : null;
  return { ...day, hours, locations, locationAccess };
}
