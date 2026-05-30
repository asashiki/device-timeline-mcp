import { DatabaseSync } from "node:sqlite";
import { dirname } from "node:path";
import { mkdirSync } from "node:fs";
import { runMigrations } from "./migrations.js";

export function openDatabase(dbPath: string): DatabaseSync {
  if (dbPath !== ":memory:") {
    mkdirSync(dirname(dbPath), { recursive: true });
  }
  const db = new DatabaseSync(dbPath);
  db.exec("PRAGMA journal_mode = WAL");
  db.exec("PRAGMA foreign_keys = ON");
  db.exec("PRAGMA busy_timeout = 5000");
  const { from, to } = runMigrations(db);
  if (from !== to) {
    console.log(`[db] migrated schema v${from} → v${to}`);
  }
  return db;
}

// Backup hook point (reserved, intentionally not implemented in v1):
// a future scheduled job can call `VACUUM INTO <path>` here to produce a
// consistent snapshot of the WAL-mode database without stopping the server.
