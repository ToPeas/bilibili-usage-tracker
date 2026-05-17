CREATE TABLE IF NOT EXISTS usage_daily (
  date TEXT NOT NULL,
  source TEXT NOT NULL,
  device_id TEXT NOT NULL,
  device_alias TEXT NOT NULL DEFAULT '',
  timezone TEXT NOT NULL,
  total_ms INTEGER NOT NULL,
  reported_at TEXT NOT NULL,
  app_version TEXT NOT NULL,
  schema_version INTEGER NOT NULL,
  uploaded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (date, source, device_id)
);

CREATE TABLE IF NOT EXISTS usage_items (
  date TEXT NOT NULL,
  source TEXT NOT NULL,
  device_id TEXT NOT NULL,
  bundle TEXT NOT NULL,
  duration_ms INTEGER NOT NULL,
  PRIMARY KEY (date, source, device_id, bundle),
  FOREIGN KEY (date, source, device_id)
    REFERENCES usage_daily(date, source, device_id)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_usage_daily_date ON usage_daily(date);
CREATE INDEX IF NOT EXISTS idx_usage_items_bundle ON usage_items(bundle);
