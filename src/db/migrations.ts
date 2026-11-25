import type { DatabaseSync } from "node:sqlite";

// Ordered list of migrations. Index 0 → schema version 1, and so on.
// The runner applies every migration whose version is greater than the
// database's current PRAGMA user_version, inside a transaction.
//
// To evolve the schema: append a new function. NEVER edit or reorder existing
// entries — a deployed database has already run them.
const MIGRATIONS: Array<(db: DatabaseSync) => void> = [
  // v1 — initial device-timeline schema
  (db) => {
    db.exec(`
      CREATE TABLE device_states (
        device_id    TEXT PRIMARY KEY,
        device_name  TEXT NOT NULL,
        platform     TEXT NOT NULL,
        app_id       TEXT,
        window_title TEXT,
        last_seen_at TEXT NOT NULL,
        extra_json   TEXT,
        updated_at   TEXT NOT NULL
      );

      CREATE TABLE device_activities (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id    TEXT NOT NULL,
        app_id       TEXT NOT NULL,
        window_title TEXT,
        started_at   TEXT NOT NULL,
        ended_at     TEXT,
        extra_json   TEXT,
        created_at   TEXT NOT NULL
      );

      CREATE INDEX idx_device_activities_device_started
        ON device_activities(device_id, started_at DESC);
      CREATE INDEX idx_device_activities_started
        ON device_activities(started_at DESC);
    `);
  },

  // v2 — Health Connect sync (optional extension). One row per health sample,
  // pushed by the Android agent's periodic HealthConnect sync. Deduped on
  // (device_id, type, recorded_at) so re-syncing an overlapping window is idempotent.
  (db) => {
    db.exec(`
      CREATE TABLE device_health (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id   TEXT NOT NULL,
        type        TEXT NOT NULL,
        value       REAL,
        value_json  TEXT,
        unit        TEXT,
        source      TEXT,
        recorded_at TEXT NOT NULL,
        created_at  TEXT NOT NULL,
        UNIQUE(device_id, type, recorded_at)
      );

      CREATE INDEX idx_device_health_dev_type_time
        ON device_health(device_id, type, recorded_at DESC);
      CREATE INDEX idx_device_health_recorded
        ON device_health(recorded_at DESC);
    `);
  },

  // v3 — player state changes, kept separate from public device activity.
  (db) => {
    db.exec(`
      CREATE TABLE device_playback (
        device_id TEXT NOT NULL,
        player TEXT NOT NULL CHECK(player IN ('Apple Music','Spotify')),
        state TEXT NOT NULL CHECK(state IN ('playing','paused','stopped')),
        track TEXT,
        artist TEXT,
        occurred_at TEXT NOT NULL,
        PRIMARY KEY (device_id, player, occurred_at)
      ) WITHOUT ROWID;
      CREATE INDEX idx_device_playback_device_time ON device_playback(device_id, occurred_at DESC);
    `);
  },
  // v4 — measurements received directly from a wearable. Public Health Connect
  // read routes select a separate table and never expose these measurements.
  (db) => {
    db.exec(`
      CREATE TABLE band_samples (
        device_id TEXT NOT NULL,
        type TEXT NOT NULL CHECK (type IN ('heart_rate','steps')),
        value INTEGER NOT NULL,
        recorded_at TEXT NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, type, recorded_at)
      ) WITHOUT ROWID;
      CREATE INDEX idx_band_samples_time ON band_samples(recorded_at);
    `);
  },
  (db) => {
    db.exec(`CREATE TABLE band_sleep (
      device_id TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT NOT NULL,
      asleep_minutes INTEGER, received_at TEXT NOT NULL,
      PRIMARY KEY(device_id,started_at)
    ) WITHOUT ROWID;
    CREATE INDEX idx_band_sleep_end ON band_sleep(ended_at);`);
  },
  // Direct status replies use the time at which the phone observed the response.
  // Session progress uses the band's own progress timestamp instead.
  (db) => {
    db.exec(`CREATE TABLE band_sleep_observations (
      device_id TEXT NOT NULL,
      session_start TEXT NOT NULL,
      observed_at TEXT NOT NULL,
      state TEXT NOT NULL CHECK(state IN ('asleep','awake','ongoing')),
      sleep_minutes INTEGER,
      origin TEXT NOT NULL,
      time_basis TEXT NOT NULL,
      received_at TEXT NOT NULL,
      PRIMARY KEY(device_id,session_start,observed_at,state)
    ) WITHOUT ROWID;
    CREATE INDEX idx_band_sleep_observations_time ON band_sleep_observations(device_id,observed_at DESC);`);
  },
  (db) => {
    db.exec(`ALTER TABLE band_sleep_observations ADD COLUMN previous_observed_at TEXT;
             ALTER TABLE band_sleep_observations ADD COLUMN previous_minutes INTEGER;`);
  },
  // One-time, locally verified history. Device uploads and public Health Connect
  // endpoints continue to use their own tables.
  (db) => {
    db.exec(`
      CREATE TABLE historical_sleep_imports (
        device_id TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT NOT NULL,
        asleep_minutes INTEGER NOT NULL CHECK (asleep_minutes BETWEEN 1 AND 1440),
        received_at TEXT NOT NULL,
        source TEXT NOT NULL CHECK (source = 'mi_fitness_local_log'),
        CHECK (ended_at > started_at AND received_at >= ended_at),
        PRIMARY KEY (device_id, started_at)
      ) WITHOUT ROWID;
      CREATE INDEX historical_sleep_by_end ON historical_sleep_imports(device_id, ended_at);

      CREATE VIEW private_completed_sleep AS
        SELECT b.device_id, b.started_at, b.ended_at, b.asleep_minutes, b.received_at,
               'hibi_band_direct' AS source, NULL AS superseded_at,
               i.asleep_minutes AS import_minutes, i.received_at AS import_received_at
        FROM band_sleep b LEFT JOIN historical_sleep_imports i
          ON i.device_id=b.device_id AND i.started_at=b.started_at AND i.ended_at=b.ended_at
        UNION ALL
        SELECT i.device_id, i.started_at, i.ended_at, i.asleep_minutes, i.received_at, i.source,
          (SELECT MIN(b.received_at) FROM band_sleep b
           WHERE b.device_id = i.device_id AND b.started_at < i.ended_at AND b.ended_at > i.started_at
           AND (b.started_at = i.started_at OR b.ended_at = i.ended_at OR
             MIN(unixepoch(b.ended_at), unixepoch(i.ended_at)) -
             MAX(unixepoch(b.started_at), unixepoch(i.started_at)) >=
             0.9 * MAX(unixepoch(b.ended_at) - unixepoch(b.started_at),
                       unixepoch(i.ended_at) - unixepoch(i.started_at))))
          AS superseded_at, NULL AS import_minutes, NULL AS import_received_at
        FROM historical_sleep_imports i;
    `);
  },
  // The daily Notion writer also picks up newly received samples for older dates.
  (db) => {
    db.exec(`
      CREATE INDEX idx_band_sample_receipts ON band_samples(received_at, recorded_at);
      CREATE INDEX idx_band_sleep_receipts ON band_sleep(received_at, started_at, ended_at);
      CREATE INDEX idx_import_sleep_receipts ON historical_sleep_imports(received_at, started_at, ended_at);
    `);
  },
  // One row per actual five-minute check; callers can see which sleep record was present.
  (db) => db.exec(`CREATE TABLE private_sleep_checks (
    device_id TEXT NOT NULL, slot INTEGER NOT NULL, checked_at TEXT NOT NULL,
    record_json TEXT, change TEXT NOT NULL, last_changed_at TEXT, latest_sample_at TEXT,
    PRIMARY KEY(device_id,slot)) WITHOUT ROWID;`),
  (db) => db.exec(`CREATE TABLE private_sleep_estimates(
    device_id TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT NOT NULL,received_at TEXT NOT NULL,
    PRIMARY KEY(device_id,started_at),CHECK(ended_at>started_at)) WITHOUT ROWID;
    CREATE VIEW private_sleep_history AS SELECT * FROM private_completed_sleep
    UNION ALL SELECT e.device_id,e.started_at,e.ended_at,NULL AS asleep_minutes,e.received_at,
      'band_minute_estimate' AS source,
      (SELECT MIN(r.received_at) FROM private_completed_sleep r WHERE r.device_id=e.device_id
       AND r.started_at<e.ended_at AND r.ended_at>e.started_at) AS superseded_at,
      NULL AS import_minutes,NULL AS import_received_at FROM private_sleep_estimates e;`),
  // Preserve the two valid native endpoints even when the band hasn't finalized the report.
  (db) => db.exec(`CREATE TABLE band_sleep_intervals (
      device_id TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT NOT NULL,received_at TEXT NOT NULL,
      PRIMARY KEY(device_id,started_at),CHECK(ended_at>started_at)) WITHOUT ROWID;
    CREATE INDEX idx_band_interval_end ON band_sleep_intervals(ended_at);
    DROP VIEW private_sleep_history;
    CREATE VIEW private_native_sleep_history AS SELECT * FROM private_completed_sleep
      UNION ALL SELECT n.device_id,n.started_at,n.ended_at,NULL,n.received_at,'hibi_band_interval',
        (SELECT MIN(r.received_at) FROM private_completed_sleep r WHERE r.device_id=n.device_id
         AND r.started_at<n.ended_at AND r.ended_at>n.started_at), NULL,NULL FROM band_sleep_intervals n;
    CREATE VIEW private_sleep_history AS SELECT * FROM private_native_sleep_history
      UNION ALL SELECT e.device_id,e.started_at,e.ended_at,NULL,e.received_at,'band_minute_estimate',
        (SELECT MIN(r.received_at) FROM private_native_sleep_history r WHERE r.device_id=e.device_id
         AND r.started_at<e.ended_at AND r.ended_at>e.started_at),NULL,NULL FROM private_sleep_estimates e;`),
  (db) => db.exec(`CREATE TABLE wrist_power_samples (
    device_id TEXT NOT NULL, measured_at INTEGER NOT NULL, pct INTEGER NOT NULL CHECK(pct BETWEEN 0 AND 100),
    mode TEXT NOT NULL CHECK(mode IN ('unknown','charging','discharging','full')), received_at INTEGER NOT NULL,
    PRIMARY KEY(device_id, measured_at)) WITHOUT ROWID;`),
  (db) => db.exec(`CREATE TABLE private_phone_location (
    device_id TEXT NOT NULL, sampled_at TEXT NOT NULL, latitude REAL NOT NULL,
    longitude REAL NOT NULL, accuracy_meters REAL NOT NULL, received_at TEXT NOT NULL,
    PRIMARY KEY(device_id, sampled_at)) WITHOUT ROWID;
    CREATE TABLE private_band_metrics (
      device_id TEXT NOT NULL, type TEXT NOT NULL CHECK(type IN ('oxygen_saturation','distance')),
      recorded_at TEXT NOT NULL, value INTEGER NOT NULL, received_at TEXT NOT NULL,
      PRIMARY KEY(device_id,type,recorded_at)) WITHOUT ROWID;`),
];

export const LATEST_VERSION = MIGRATIONS.length;

export function runMigrations(db: DatabaseSync): { from: number; to: number } {
  const row = db.prepare("PRAGMA user_version").get() as { user_version: number };
  const current = row.user_version ?? 0;

  for (let version = current + 1; version <= MIGRATIONS.length; version++) {
    const migrate = MIGRATIONS[version - 1]!;
    db.exec("BEGIN");
    try {
      migrate(db);
      db.exec(`PRAGMA user_version = ${version}`);
      db.exec("COMMIT");
    } catch (err) {
      db.exec("ROLLBACK");
      throw new Error(`Migration to v${version} failed: ${(err as Error).message}`);
    }
  }
  return { from: current, to: MIGRATIONS.length };
}
