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
