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

-- 按小时分桶（0..23），用于「在哪个时段看 B 站多少时间」的统计
CREATE TABLE IF NOT EXISTS usage_hours (
  date TEXT NOT NULL,
  source TEXT NOT NULL,
  device_id TEXT NOT NULL,
  hour INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  PRIMARY KEY (date, source, device_id, hour),
  FOREIGN KEY (date, source, device_id)
    REFERENCES usage_daily(date, source, device_id)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_usage_daily_date ON usage_daily(date);
CREATE INDEX IF NOT EXISTS idx_usage_items_bundle ON usage_items(bundle);
CREATE INDEX IF NOT EXISTS idx_usage_hours_date ON usage_hours(date);
