const TARGET_HOSTS = new Set([
  "www.bilibili.com",
  "m.bilibili.com",
  "t.bilibili.com",
  "live.bilibili.com",
  "space.bilibili.com",
  "search.bilibili.com",
  "message.bilibili.com"
]);

const DEFAULT_SETTINGS = {
  accountId: "",
  databaseId: "",
  cloudflareApiToken: "",
  deviceId: "",
  deviceAlias: "",
  appVersion: "1.2.0"
};

const DISPLAY_TIME_ZONE = "Asia/Shanghai";
const MAX_DELTA_MS = 30000;
const SCHEMA_VERSION = 2;
const HISTORY_LIMIT_DAYS = 200;

const state = {
  active: false,
  host: "",
  tabId: 0,
  lastDeltaAt: 0,
  debug: {
    lastMessageAt: 0,
    lastAcceptedAt: 0,
    lastIgnoredAt: 0,
    lastReason: "",
    lastCountedMs: 0,
    lastHost: "",
    lastTabId: 0,
    contentVisible: false,
    contentFocused: false,
    contextHost: "",
    contextTabId: 0,
    contextFocused: false,
    idleState: "active"
  }
};

chrome.runtime.onInstalled.addListener(async () => {
  const settings = await getSettings();
  if (!settings.deviceId) {
    await chrome.storage.local.set({
      settings: { ...settings, deviceId: crypto.randomUUID() }
    });
  }
  chrome.idle.setDetectionInterval(60);
  await scheduleDailyUpload();
  await reconcile();
  await uploadPendingDays();
});

chrome.runtime.onStartup.addListener(async () => {
  chrome.idle.setDetectionInterval(60);
  await scheduleDailyUpload();
  await reconcile();
  await uploadPendingDays();
});

chrome.tabs.onActivated.addListener(() => reconcile());
chrome.tabs.onUpdated.addListener((_tabId, changeInfo) => {
  if (changeInfo.url || changeInfo.status === "complete") {
    reconcile();
  }
});
chrome.windows.onFocusChanged.addListener(() => reconcile());
chrome.idle.onStateChanged.addListener(() => reconcile());

chrome.alarms.onAlarm.addListener(async alarm => {
  if (alarm.name === "daily-upload") {
    await uploadPendingDays();
    await scheduleDailyUpload();
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    if (message?.type === "get-status") {
      sendResponse(await getStatus());
      return;
    }
    if (message?.type === "bili-usage-delta") {
      sendResponse(await handleUsageDelta(message, sender));
      return;
    }
    if (message?.type === "bili-heartbeat") {
      sendResponse({ ok: true, ignored: true });
      return;
    }
    if (message?.type === "upload-now") {
      const result = await uploadPendingDays({ includeToday: true, force: true });
      sendResponse({ ok: true, result });
      return;
    }
    if (message?.type === "get-recent-usage") {
      sendResponse(await getRecentUsage(Number(message.days) || 7));
      return;
    }
    if (message?.type === "get-day-detail") {
      sendResponse(await getDayDetail(String(message.date || "")));
      return;
    }
    if (message?.type === "test-d1-connection") {
      sendResponse(await testD1Connection());
      return;
    }
    if (message?.type === "settings-updated") {
      await scheduleDailyUpload();
      await reconcile();
      sendResponse({ ok: true });
      return;
    }
    if (message?.type === "open-options") {
      chrome.runtime.openOptionsPage();
      sendResponse({ ok: true });
      return;
    }
    sendResponse({ ok: false, error: "unknown message" });
  })().catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
  return true;
});

async function reconcile() {
  const context = await getCurrentContext();
  const shouldTrack = TARGET_HOSTS.has(context.host) && context.focused && context.idleState === "active";
  state.active = shouldTrack;
  state.host = shouldTrack ? context.host : "";
  state.tabId = shouldTrack ? context.tabId : 0;
  if (!shouldTrack) {
    state.lastDeltaAt = 0;
  }
  // 同步 debug 面板所需的最新上下文，否则 popup 拉 status 看到的会是 SW 初始值 "unknown"。
  updateDebug({
    idleState: context.idleState || "active",
    contextHost: context.host || "",
    contextTabId: context.tabId || 0,
    contextFocused: Boolean(context.focused),
    reconciledAt: Date.now()
  });
}

async function getCurrentContext() {
  // 取「最近一个 normal 浏览器窗口」里的 active tab。
  // 这样用户开 popup（type="popup"）或其他插件窗口时，
  // 不会被误判为「不在 B 站」。
  const normalWindow = await getNormalFocusedWindow();
  let tab;
  if (normalWindow) {
    const tabs = await chrome.tabs.query({ active: true, windowId: normalWindow.id });
    tab = tabs?.[0];
  } else {
    const tabs = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    tab = tabs?.[0];
  }
  const idleState = await getIdleState();
  // 主窗口存在时认为「聚焦」以 popup 可以继续计时，
  // 仅在整个浏览器都被其它应用颁夺火身时认为失焦。
  const focused = normalWindow ? (normalWindow.focused === undefined ? true : normalWindow.focused || (normalWindow.state !== "minimized")) : false;
  return {
    tabId: tab?.id || 0,
    host: getHost(tab?.url),
    focused,
    idleState
  };
}

async function getNormalFocusedWindow() {
  try {
    const windows = await chrome.windows.getAll({ populate: false });
    if (!windows || !windows.length) return null;
    // 优先取被 focused 且 type==normal
    const normals = windows.filter(w => w.type === "normal" && w.state !== "minimized");
    if (!normals.length) return null;
    const focused = normals.find(w => w.focused);
    return focused || normals[0];
  } catch (_error) {
    return null;
  }
}

async function getIdleState() {
  try {
    if (!chrome.idle?.queryState) return "active";
    const value = await chrome.idle.queryState(60);
    return value || "active";
  } catch (_error) {
    return "active";
  }
}

async function handleUsageDelta(message, sender) {
  const now = Date.now();
  const tabId = sender?.tab?.id || 0;
  const host = message.host || getHost(message.url);
  updateDebug({
    lastMessageAt: now,
    lastHost: host,
    lastTabId: tabId,
    contentVisible: Boolean(message.visible),
    contentFocused: Boolean(message.focused)
  });
  if (!tabId || !TARGET_HOSTS.has(host)) {
    updateDebug({ lastIgnoredAt: now, lastReason: "ignored host" });
    return { ok: true, ignored: true, error: "ignored host" };
  }

  const context = await getCurrentContext();
  updateDebug({
    contextHost: context.host,
    contextTabId: context.tabId,
    contextFocused: context.focused,
    idleState: context.idleState
  });
  const shouldTrack =
    message.visible &&
    context.tabId === tabId &&
    context.focused &&
    context.idleState === "active" &&
    TARGET_HOSTS.has(context.host);

  if (!shouldTrack) {
    if (state.tabId === tabId) {
      state.active = false;
      state.lastDeltaAt = 0;
      state.tabId = 0;
    }
    updateDebug({ lastIgnoredAt: now, lastReason: getIgnoreReason(message, context, tabId) });
    return { ok: true, ignored: true, error: "not active" };
  }

  const durationMs = Math.min(Math.max(0, Number(message.durationMs) || 0), MAX_DELTA_MS);
  if (durationMs < 250) {
    updateDebug({ lastIgnoredAt: now, lastReason: "short delta" });
    return { ok: true, countedMs: 0 };
  }
  const endAt = Number.isFinite(message.endAt) ? Math.min(now, Math.max(0, Number(message.endAt))) : now;
  const startAt = Math.max(0, endAt - durationMs);
  await addDuration(host, startAt, endAt);

  state.active = true;
  state.host = host;
  state.tabId = tabId;
  state.lastDeltaAt = endAt;
  updateDebug({
    lastAcceptedAt: now,
    lastReason: "accepted",
    lastCountedMs: durationMs
  });
  return { ok: true, countedMs: durationMs };
}

async function getFocusedWindow() {
  try {
    return await chrome.windows.getLastFocused({ populate: false });
  } catch (_error) {
    return null;
  }
}

function getHost(url) {
  try {
    return new URL(url).hostname;
  } catch (_error) {
    return "";
  }
}

function updateDebug(values) {
  state.debug = { ...state.debug, ...values };
}

function getIgnoreReason(message, context, tabId) {
  if (!message.visible) return "content hidden";
  if (context.tabId !== tabId) return "not active tab";
  if (!context.focused) return "window not focused";
  if (context.idleState !== "active") return `idle:${context.idleState}`;
  if (!TARGET_HOSTS.has(context.host)) return "active tab not bilibili";
  return "not active";
}

/**
 * 把一段计时区间 [startMs, endMs) 按本地时区拆分到「日 + 小时」两级桶，写入本地存储。
 *
 * 数据结构（chrome.storage.local.usage）：
 *   {
 *     "2026-05-17": {
 *       byHost: { "www.bilibili.com": 1234 },
 *       byHour: { 0: 0, 1: 0, ... 23: 0 }  // 毫秒
 *     }
 *   }
 *
 * 同时记录 `dirty[date] = true`，下次上传时被纳入。
 */
async function addDuration(host, startMs, endMs) {
  if (!host || endMs <= startMs) return;

  const { usage = {}, dirty = {}, uploaded = {} } = await chrome.storage.local.get(["usage", "dirty", "uploaded"]);
  let cursor = startMs;
  while (cursor < endMs) {
    const hourEnd = nextLocalHour(cursor);
    const dayEnd = nextLocalMidnight(cursor);
    const segmentEnd = Math.min(endMs, hourEnd, dayEnd);
    const segmentMs = segmentEnd - cursor;
    if (segmentMs <= 0) break;

    const cursorDate = new Date(cursor);
    const date = formatLocalDate(cursorDate);
    const hour = cursorDate.getHours();

    const bucket = usage[date] || { byHost: {}, byHour: {} };
    bucket.byHost = bucket.byHost || {};
    bucket.byHour = bucket.byHour || {};
    bucket.byHost[host] = Math.round((bucket.byHost[host] || 0) + segmentMs);
    bucket.byHour[hour] = Math.round((bucket.byHour[hour] || 0) + segmentMs);
    usage[date] = bucket;
    dirty[date] = true;
    if (uploaded[date]) delete uploaded[date]; // 数据有变，重新触发上传

    cursor = segmentEnd;
  }

  // 限制本地存储不无限膨胀
  const dates = Object.keys(usage).sort();
  if (dates.length > HISTORY_LIMIT_DAYS) {
    for (const d of dates.slice(0, dates.length - HISTORY_LIMIT_DAYS)) {
      delete usage[d];
      delete dirty[d];
      delete uploaded[d];
    }
  }

  await chrome.storage.local.set({ usage, dirty, uploaded });
}

function nextLocalMidnight(ms) {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
}

function nextLocalHour(ms) {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours() + 1).getTime();
}

function formatLocalDate(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function formatDateInTimeZone(date, timeZone) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit"
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(p => [p.type, p.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function formatOffsetDate(date) {
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const abs = Math.abs(offset);
  const hh = String(Math.floor(abs / 60)).padStart(2, "0");
  const mm = String(abs % 60).padStart(2, "0");
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}T${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}:${String(date.getSeconds()).padStart(2, "0")}${sign}${hh}:${mm}`;
}

async function scheduleDailyUpload() {
  const now = new Date();
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 5, 0, 0);
  await chrome.alarms.create("daily-upload", { when: next.getTime() });
}

async function uploadPendingDays(options = {}) {
  const settings = await getSettings();
  if (!settings.accountId || !settings.databaseId || !settings.cloudflareApiToken || !settings.deviceId) {
    return { uploaded: 0, skipped: true, reason: "missing settings" };
  }

  const { usage = {}, uploaded = {}, dirty = {}, uploadLog = [] } =
    await chrome.storage.local.get(["usage", "uploaded", "dirty", "uploadLog"]);
  const schema = await ensureD1Schema(settings);
  if (!schema.ok) {
    uploadLog.unshift({ date: "schema", ok: false, status: 0, message: String(schema.error).slice(0, 200), time: Date.now() });
    await chrome.storage.local.set({ uploadLog: uploadLog.slice(0, 50) });
    return { uploaded: 0, attempted: 0, error: schema.error };
  }

  const today = formatLocalDate(new Date());
  // 选哪些日期上传：今天总是允许重传（数据还在变化）；其余日期只在 dirty / 未 uploaded / force 时上传
  const dates = Object.keys(usage)
    .filter(date => {
      if (date === today) return options.includeToday;
      if (options.force) return true;
      if (dirty[date]) return true;
      if (!uploaded[date]) return true;
      return false;
    })
    .sort()
    .slice(-30);

  let uploadedCount = 0;
  for (const date of dates) {
    const bucket = usage[date] || {};
    const byHost = bucket.byHost || {};
    const byHour = bucket.byHour || {};
    const items = Object.entries(byHost)
      .filter(([, ms]) => ms > 0)
      .map(([bundle, durationMs]) => ({ bundle, durationMs }));
    const hours = Object.entries(byHour)
      .map(([h, ms]) => ({ hour: Number(h), durationMs: Number(ms) || 0 }))
      .filter(h => h.durationMs > 0 && h.hour >= 0 && h.hour < 24);

    if (!items.length) {
      uploaded[date] = true;
      delete dirty[date];
      continue;
    }

    const totalMs = items.reduce((sum, item) => sum + item.durationMs, 0);
    const payload = {
      date,
      source: "web",
      deviceId: settings.deviceId,
      deviceAlias: settings.deviceAlias || settings.deviceId,
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "local",
      items,
      hours,
      totalMs,
      reportedAt: formatOffsetDate(new Date()),
      appVersion: settings.appVersion || "1.2.0",
      schemaVersion: SCHEMA_VERSION
    };

    const batch = buildD1UpsertBatch(payload);
    const response = await fetch(getD1QueryUrl(settings), {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${settings.cloudflareApiToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ batch })
    });

    const result = await response.json().catch(() => null);
    const cloudflareOk = response.ok && result?.success && Array.isArray(result.result) && result.result.every(item => item.success);
    if (!cloudflareOk) {
      const message = result?.errors?.map(e => e.message).join("; ") || response.statusText || "D1 query failed";
      uploadLog.unshift({ date, ok: false, status: response.status, message: message.slice(0, 200), time: Date.now() });
      break;
    }

    uploaded[date] = true;
    delete dirty[date];
    uploadedCount += 1;
    uploadLog.unshift({ date, ok: true, status: response.status, time: Date.now() });
  }

  await chrome.storage.local.set({ uploaded, dirty, uploadLog: uploadLog.slice(0, 50) });
  return { uploaded: uploadedCount, attempted: dates.length };
}

async function getRecentUsage(rangeDays) {
  const days = Math.max(1, Math.min(366, Math.floor(rangeDays) || 7));
  const settings = await getSettings();
  if (!settings.accountId || !settings.databaseId || !settings.cloudflareApiToken) {
    return { ok: false, error: "missing settings", database: getDatabaseLabel(settings), rangeDays: days, days: [] };
  }

  const schema = await ensureD1Schema(settings);
  if (!schema.ok) {
    return { ok: false, error: schema.error, database: getDatabaseLabel(settings), rangeDays: days, days: [] };
  }

  const to = formatDateInTimeZone(new Date(), DISPLAY_TIME_ZONE);
  const fromDate = new Date();
  fromDate.setDate(fromDate.getDate() - (days - 1));
  const from = formatDateInTimeZone(fromDate, DISPLAY_TIME_ZONE);

  const result = await queryD1(settings, {
    sql: `
      SELECT
        date,
        source,
        device_id AS deviceId,
        device_alias AS deviceAlias,
        total_ms AS totalMs,
        uploaded_at AS uploadedAt
      FROM usage_daily
      WHERE source = 'web' AND date >= ? AND date <= ?
      ORDER BY date DESC, total_ms DESC, device_id ASC
    `,
    params: [from, to]
  });

  if (!result.ok) {
    return { ok: false, error: result.error, database: getDatabaseLabel(settings), rangeDays: days, days: [] };
  }

  const byDate = new Map();
  for (const row of result.rows) {
    if (!byDate.has(row.date)) {
      byDate.set(row.date, { date: row.date, totalMs: 0, latestUploadedAt: "", devices: [] });
    }
    const day = byDate.get(row.date);
    const totalMs = Number(row.totalMs || 0);
    day.totalMs += totalMs;
    day.latestUploadedAt = maxIsoTime(day.latestUploadedAt, row.uploadedAt || "");
    day.devices.push({
      deviceId: row.deviceId,
      deviceAlias: row.deviceAlias || (row.deviceId === settings.deviceId ? settings.deviceAlias : "") || row.deviceId,
      totalMs,
      uploadedAt: row.uploadedAt || ""
    });
  }

  const output = [];
  for (let index = 0; index < days; index += 1) {
    const date = new Date();
    date.setDate(date.getDate() - index);
    const key = formatDateInTimeZone(date, DISPLAY_TIME_ZONE);
    output.push(byDate.get(key) || { date: key, totalMs: 0, latestUploadedAt: "", devices: [] });
  }

  return { ok: true, database: getDatabaseLabel(settings), rangeDays: days, days: output };
}

/**
 * 拉取某天的「24 小时分布 + 各设备分布」。返回：
 * {
 *   ok, date,
 *   totalMs,
 *   devices: [{deviceId, deviceAlias, totalMs, uploadedAt}],
 *   hours: [{hour, durationMs}] // 长度 24，按 device 求和
 *   hoursByDevice: { deviceId: [{hour, durationMs}] }
 * }
 */
async function getDayDetail(date) {
  const settings = await getSettings();
  if (!date) return { ok: false, error: "missing date" };
  if (!settings.accountId || !settings.databaseId || !settings.cloudflareApiToken) {
    return { ok: false, error: "missing settings" };
  }
  const schema = await ensureD1Schema(settings);
  if (!schema.ok) return { ok: false, error: schema.error };

  const dailyResp = await queryD1(settings, {
    sql: `
      SELECT
        device_id AS deviceId,
        device_alias AS deviceAlias,
        total_ms AS totalMs,
        uploaded_at AS uploadedAt
      FROM usage_daily
      WHERE date = ? AND source = 'web'
      ORDER BY total_ms DESC
    `,
    params: [date]
  });
  if (!dailyResp.ok) return { ok: false, error: dailyResp.error };

  const hoursResp = await queryD1(settings, {
    sql: `
      SELECT device_id AS deviceId, hour, duration_ms AS durationMs
      FROM usage_hours
      WHERE date = ? AND source = 'web'
    `,
    params: [date]
  });
  if (!hoursResp.ok) return { ok: false, error: hoursResp.error };

  const devices = dailyResp.rows.map(row => ({
    deviceId: row.deviceId,
    deviceAlias: row.deviceAlias || (row.deviceId === settings.deviceId ? settings.deviceAlias : "") || row.deviceId,
    totalMs: Number(row.totalMs || 0),
    uploadedAt: row.uploadedAt || ""
  }));

  const hoursTotal = Array.from({ length: 24 }, (_, hour) => ({ hour, durationMs: 0 }));
  const hoursByDevice = {};
  for (const row of hoursResp.rows) {
    const hour = Math.max(0, Math.min(23, Math.floor(Number(row.hour) || 0)));
    const ms = Number(row.durationMs || 0);
    hoursTotal[hour].durationMs += ms;
    const id = String(row.deviceId || "");
    if (!hoursByDevice[id]) hoursByDevice[id] = Array.from({ length: 24 }, (_, h) => ({ hour: h, durationMs: 0 }));
    hoursByDevice[id][hour].durationMs += ms;
  }

  return {
    ok: true,
    date,
    totalMs: devices.reduce((s, d) => s + d.totalMs, 0),
    devices,
    hours: hoursTotal,
    hoursByDevice
  };
}

async function testD1Connection() {
  const settings = await getSettings();
  if (!settings.accountId || !settings.databaseId || !settings.cloudflareApiToken) {
    return { ok: false, database: getDatabaseLabel(settings), error: "请先填写 Account ID、Database ID 和 API Token" };
  }
  const readTest = await queryD1(settings, { sql: "SELECT 1 AS ok", params: [] });
  if (!readTest.ok) {
    return { ok: false, database: getDatabaseLabel(settings), phase: "read", error: readTest.error };
  }
  const schema = await ensureD1Schema(settings);
  if (!schema.ok) {
    return { ok: false, database: getDatabaseLabel(settings), phase: "write", error: schema.error };
  }
  const writeTest = await queryD1(settings, {
    batch: [
      {
        sql: "CREATE TABLE IF NOT EXISTS usage_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
        params: []
      },
      {
        sql: `
          INSERT INTO usage_meta (key, value, updated_at)
          VALUES ('_connection_test', ?, CURRENT_TIMESTAMP)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP
        `,
        params: [JSON.stringify({
          deviceId: settings.deviceId || "",
          checkedAt: new Date().toISOString(),
          appVersion: settings.appVersion || "1.2.0"
        })]
      }
    ]
  });
  if (!writeTest.ok) {
    return { ok: false, database: getDatabaseLabel(settings), phase: "write", error: writeTest.error };
  }
  return { ok: true, database: getDatabaseLabel(settings), message: "D1 可读可写" };
}

async function ensureD1Schema(settings) {
  const createResult = await queryD1(settings, { batch: buildD1SchemaBatch() });
  if (!createResult.ok) return createResult;

  const columns = await queryD1(settings, { sql: "PRAGMA table_info(usage_daily)", params: [] });
  if (!columns.ok) return columns;
  const hasDeviceAlias = columns.rows.some(row => row.name === "device_alias");
  if (!hasDeviceAlias) {
    const alter = await queryD1(settings, {
      sql: "ALTER TABLE usage_daily ADD COLUMN device_alias TEXT NOT NULL DEFAULT ''",
      params: []
    });
    if (!alter.ok) return alter;
  }
  return { ok: true };
}

function buildD1SchemaBatch() {
  return [
    {
      sql: `
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
        )
      `,
      params: []
    },
    {
      sql: `
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
        )
      `,
      params: []
    },
    {
      sql: `
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
        )
      `,
      params: []
    },
    { sql: "CREATE INDEX IF NOT EXISTS idx_usage_daily_date ON usage_daily(date)", params: [] },
    { sql: "CREATE INDEX IF NOT EXISTS idx_usage_items_bundle ON usage_items(bundle)", params: [] },
    { sql: "CREATE INDEX IF NOT EXISTS idx_usage_hours_date ON usage_hours(date)", params: [] }
  ];
}

async function queryD1(settings, body) {
  const response = await fetch(getD1QueryUrl(settings), {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${settings.cloudflareApiToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });
  const data = await response.json().catch(() => null);
  const resultItems = Array.isArray(data?.result) ? data.result : [];
  const allQueriesOk = resultItems.length > 0 && resultItems.every(item => item.success);
  if (!response.ok || !data?.success || !allQueriesOk) {
    const error = data?.errors?.map(item => item.message).join("; ") || response.statusText || "D1 query failed";
    return { ok: false, error };
  }
  return { ok: true, rows: resultItems[0]?.results || [] };
}

function getDatabaseLabel(settings) {
  if (!settings.accountId || !settings.databaseId) return "未配置 D1";
  return `${shortId(settings.accountId)} / ${shortId(settings.databaseId)}`;
}

function shortId(value) {
  if (!value) return "";
  if (value.length <= 12) return value;
  return `${value.slice(0, 6)}...${value.slice(-6)}`;
}

function maxIsoTime(a, b) {
  if (!a) return b;
  if (!b) return a;
  return a > b ? a : b;
}

function getD1QueryUrl(settings) {
  const accountId = encodeURIComponent(settings.accountId);
  const databaseId = encodeURIComponent(settings.databaseId);
  return `https://api.cloudflare.com/client/v4/accounts/${accountId}/d1/database/${databaseId}/query`;
}

function buildD1UpsertBatch(payload) {
  const common = [payload.date, payload.source, payload.deviceId];
  const batch = [
    {
      sql: `
        INSERT INTO usage_daily (
          date, source, device_id, device_alias, timezone, total_ms, reported_at, app_version, schema_version, uploaded_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT(date, source, device_id) DO UPDATE SET
          device_alias = excluded.device_alias,
          timezone = excluded.timezone,
          total_ms = excluded.total_ms,
          reported_at = excluded.reported_at,
          app_version = excluded.app_version,
          schema_version = excluded.schema_version,
          uploaded_at = CURRENT_TIMESTAMP
      `,
      params: [
        payload.date,
        payload.source,
        payload.deviceId,
        payload.deviceAlias || payload.deviceId,
        payload.timezone,
        payload.totalMs,
        payload.reportedAt,
        payload.appVersion,
        payload.schemaVersion
      ]
    },
    {
      sql: "DELETE FROM usage_items WHERE date = ? AND source = ? AND device_id = ?",
      params: common
    },
    {
      sql: "DELETE FROM usage_hours WHERE date = ? AND source = ? AND device_id = ?",
      params: common
    },
    ...payload.items.map(item => ({
      sql: `INSERT INTO usage_items (date, source, device_id, bundle, duration_ms) VALUES (?, ?, ?, ?, ?)`,
      params: [...common, item.bundle, item.durationMs]
    })),
    ...(payload.hours || []).map(hour => ({
      sql: `INSERT INTO usage_hours (date, source, device_id, hour, duration_ms) VALUES (?, ?, ?, ?, ?)`,
      params: [...common, hour.hour, hour.durationMs]
    }))
  ];
  return batch;
}

async function getSettings() {
  const { settings = {} } = await chrome.storage.local.get("settings");
  return { ...DEFAULT_SETTINGS, ...settings };
}

async function getStatus() {
  // 调 reconcile 以保证 state.active / state.host 是实时的（popup 打开时尤其重要）
  await reconcile();
  const { usage = {}, uploaded = {}, uploadLog = [] } = await chrome.storage.local.get(["usage", "uploaded", "uploadLog"]);
  const today = formatLocalDate(new Date());
  const todayBucket = usage[today] || { byHost: {}, byHour: {} };
  const todayItems = { ...(todayBucket.byHost || {}) };

  if (state.active && state.host && state.lastDeltaAt) {
    const now = Date.now();
    const activeDate = formatLocalDate(new Date(now));
    if (activeDate === today) {
      const liveMs = Math.max(0, Math.min(now - state.lastDeltaAt, 5000));
      todayItems[state.host] = Math.max(0, Math.round((todayItems[state.host] || 0) + liveMs));
    }
  }
  return {
    active: state.active,
    host: state.host,
    today,
    todayTotalMs: Object.values(todayItems).reduce((s, v) => s + v, 0),
    todayItems,
    todayHours: Array.from({ length: 24 }, (_, h) => ({ hour: h, durationMs: Number(todayBucket.byHour?.[h]) || 0 })),
    uploaded,
    uploadLog: uploadLog.slice(0, 10),
    debug: state.debug
  };
}
