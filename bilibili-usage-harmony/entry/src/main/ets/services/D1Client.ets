import { http } from '@kit.NetworkKit';
import { Settings } from '../common/SettingsStore';
import { DayPayload } from './UsageCollector';

type D1Param = string | number;

class D1Statement {
  sql: string = '';
  params: D1Param[] = [];

  constructor(sql: string, params: D1Param[] = []) {
    this.sql = sql;
    this.params = params;
  }
}

class D1BatchRequest {
  batch: D1Statement[] = [];

  constructor(batch: D1Statement[]) {
    this.batch = batch;
  }
}

class D1QueryRequest {
  sql: string = '';
  params: D1Param[] = [];

  constructor(sql: string, params: D1Param[]) {
    this.sql = sql;
    this.params = params;
  }
}

class D1Row {
  date: string = '';
  source: string = '';
  deviceAlias: string = '';
  deviceId: string = '';
  totalMs: number = 0;
  uploadedAt: string = '';
  hour?: number;
  durationMs?: number;
}

class D1QueryResult {
  results?: D1Row[];
}

class D1ResponseEnvelope {
  result?: D1QueryResult[];
}

export class D1Result {
  ok: boolean = false;
  status: number = 0;
  body: string = '';
  error: string = '';
}

export class D1Client {
  settings: Settings;

  constructor(s: Settings) {
    this.settings = s;
  }

  private url(): string {
    return `https://api.cloudflare.com/client/v4/accounts/${this.settings.accountId}/d1/database/${this.settings.databaseId}/query`;
  }

  private async send(body: D1BatchRequest | D1QueryRequest): Promise<D1Result> {
    const r = new D1Result();
    if (!this.settings.accountId || !this.settings.databaseId || !this.settings.apiToken) {
      r.error = '请先填写 Account ID / Database ID / API Token';
      return r;
    }

    const req = http.createHttp();
    try {
      const resp = await req.request(this.url(), {
        method: http.RequestMethod.POST,
        header: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${this.settings.apiToken}`
        },
        readTimeout: 15000,
        connectTimeout: 15000,
        extraData: JSON.stringify(body)
      });
      r.status = resp.responseCode;
      r.body = String(resp.result || '');
      r.ok = r.status >= 200 && r.status < 300;
      if (!r.ok) {
        r.error = `HTTP ${r.status}: ${r.body.slice(0, 200)}`;
      }
    } catch (e) {
      const err = e as Error;
      r.error = err.message || String(e);
    } finally {
      req.destroy();
    }
    return r;
  }

  async ensureSchema(): Promise<D1Result> {
    const stmts: D1Statement[] = [
      new D1Statement("CREATE TABLE IF NOT EXISTS usage_daily (date TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, device_alias TEXT NOT NULL DEFAULT '', timezone TEXT NOT NULL, total_ms INTEGER NOT NULL, reported_at TEXT NOT NULL, app_version TEXT NOT NULL, schema_version INTEGER NOT NULL, uploaded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (date, source, device_id))"),
      new D1Statement("CREATE TABLE IF NOT EXISTS usage_items (date TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, bundle TEXT NOT NULL, duration_ms INTEGER NOT NULL, PRIMARY KEY (date, source, device_id, bundle))"),
      new D1Statement("CREATE TABLE IF NOT EXISTS usage_hours (date TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, hour INTEGER NOT NULL, duration_ms INTEGER NOT NULL, PRIMARY KEY (date, source, device_id, hour))")
    ];
    return await this.send(new D1BatchRequest(stmts));
  }

  async uploadDay(day: DayPayload): Promise<D1Result> {
    const tz = 'Asia/Shanghai';
    const reportedAt = new Date().toISOString();
    const source = 'harmony';
    const batch: D1Statement[] = [];

    batch.push(new D1Statement(
      `INSERT INTO usage_daily (date, source, device_id, device_alias, timezone, total_ms, reported_at, app_version, schema_version, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT(date, source, device_id) DO UPDATE SET device_alias = excluded.device_alias, timezone = excluded.timezone, total_ms = excluded.total_ms, reported_at = excluded.reported_at, app_version = excluded.app_version, schema_version = excluded.schema_version, uploaded_at = CURRENT_TIMESTAMP`,
      [day.date, source, this.settings.deviceId, this.settings.deviceAlias, tz, day.totalMs, reportedAt, this.settings.appVersion, 2]
    ));
    batch.push(new D1Statement(
      `DELETE FROM usage_items WHERE date = ? AND source = ? AND device_id = ?`,
      [day.date, source, this.settings.deviceId]
    ));
    batch.push(new D1Statement(
      `DELETE FROM usage_hours WHERE date = ? AND source = ? AND device_id = ?`,
      [day.date, source, this.settings.deviceId]
    ));

    for (const item of day.byBundle) {
      if (item.durationMs <= 0) {
        continue;
      }
      batch.push(new D1Statement(
        `INSERT INTO usage_items (date, source, device_id, bundle, duration_ms) VALUES (?, ?, ?, ?, ?)`,
        [day.date, source, this.settings.deviceId, item.bundle, item.durationMs]
      ));
    }

    for (let h = 0; h < 24; h++) {
      const durationMs = day.byHour[h] || 0;
      if (durationMs <= 0) {
        continue;
      }
      batch.push(new D1Statement(
        `INSERT INTO usage_hours (date, source, device_id, hour, duration_ms) VALUES (?, ?, ?, ?, ?)`,
        [day.date, source, this.settings.deviceId, h, durationMs]
      ));
    }

    return await this.send(new D1BatchRequest(batch));
  }

  async queryRecent(days: number): Promise<DayRow[]> {
    const today = new Date();
    const from = new Date(today.getTime() - (days - 1) * 24 * 3600 * 1000);
    const request = new D1QueryRequest(
      "SELECT date, source, COALESCE(device_alias, device_id) AS deviceAlias, device_id AS deviceId, total_ms AS totalMs, uploaded_at AS uploadedAt FROM usage_daily WHERE date >= ? AND date <= ? ORDER BY date DESC, total_ms DESC",
      [D1Client.fmtDate(from), D1Client.fmtDate(today)]
    );
    const r = await this.send(request);
    if (!r.ok) {
      return [];
    }

    try {
      const j = JSON.parse(r.body) as D1ResponseEnvelope;
      const rows: DayRow[] = [];
      if (j.result && j.result.length > 0) {
        const resultsField = j.result[0].results;
        if (resultsField) {
          for (const row of resultsField) {
            const dayRow = new DayRow();
            dayRow.date = String(row.date || '');
            dayRow.source = String(row.source || '');
            dayRow.deviceAlias = String(row.deviceAlias || row.deviceId || '');
            dayRow.deviceId = String(row.deviceId || '');
            dayRow.totalMs = Number(row.totalMs || 0);
            dayRow.uploadedAt = String(row.uploadedAt || '');
            rows.push(dayRow);
          }
        }
      }
      return rows;
    } catch (_e) {
      return [];
    }
  }

  async queryRecentWithHours(days: number): Promise<DayRow[]> {
    const rows = await this.queryRecent(days);
    if (rows.length === 0) {
      return rows;
    }

    const today = new Date();
    const from = new Date(today.getTime() - (days - 1) * 24 * 3600 * 1000);
    const request = new D1QueryRequest(
      "SELECT date, source, device_id AS deviceId, hour, duration_ms AS durationMs FROM usage_hours WHERE date >= ? AND date <= ? ORDER BY date DESC, source, device_id, hour",
      [D1Client.fmtDate(from), D1Client.fmtDate(today)]
    );
    const r = await this.send(request);
    if (!r.ok) {
      return rows;
    }

    const byKey = new Map<string, DayRow>();
    for (const row of rows) {
      row.byHour = new Array<number>(24).fill(0);
      byKey.set(D1Client.rowKey(row.date, row.source, row.deviceId), row);
    }

    try {
      const j = JSON.parse(r.body) as D1ResponseEnvelope;
      if (j.result && j.result.length > 0) {
        const resultsField = j.result[0].results;
        if (resultsField) {
          for (const row of resultsField) {
            const found = byKey.get(D1Client.rowKey(String(row.date || ''), String(row.source || ''), String(row.deviceId || '')));
            const hour = Number(row.hour);
            if (found && hour >= 0 && hour < 24) {
              found.byHour[hour] = Number(row.durationMs || 0);
            }
          }
        }
      }
    } catch (_e) {
      return rows;
    }
    return rows;
  }

  private static fmtDate(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dd}`;
  }

  private static rowKey(date: string, source: string, deviceId: string): string {
    return `${date}|${source}|${deviceId}`;
  }
}

export class DayRow {
  date: string = '';
  source: string = '';
  deviceAlias: string = '';
  deviceId: string = '';
  totalMs: number = 0;
  uploadedAt: string = '';
  byHour: number[] = new Array<number>(24).fill(0);
}
