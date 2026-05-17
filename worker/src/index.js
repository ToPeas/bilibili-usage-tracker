const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type"
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    const url = new URL(request.url);
    if (url.pathname === "/api/health") {
      return json({ ok: true });
    }

    if (url.pathname !== "/api/usage") {
      return json({ ok: false, error: "not found" }, 404);
    }

    const auth = request.headers.get("Authorization") || "";
    if (!env.API_TOKEN || auth !== `Bearer ${env.API_TOKEN}`) {
      return json({ ok: false, error: "unauthorized" }, 401);
    }

    if (request.method === "PUT") {
      return handlePutUsage(request, env);
    }

    if (request.method === "GET") {
      return handleGetUsage(url, env);
    }

    return json({ ok: false, error: "method not allowed" }, 405);
  }
};

async function handlePutUsage(request, env) {
  const payload = await request.json().catch(() => null);
  const validationError = validatePayload(payload);
  if (validationError) {
    return json({ ok: false, error: validationError }, 400);
  }

  const daily = env.DB.prepare(`
    INSERT INTO usage_daily (
      date, source, device_id, timezone, total_ms, reported_at, app_version, schema_version, uploaded_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(date, source, device_id) DO UPDATE SET
      timezone = excluded.timezone,
      total_ms = excluded.total_ms,
      reported_at = excluded.reported_at,
      app_version = excluded.app_version,
      schema_version = excluded.schema_version,
      uploaded_at = CURRENT_TIMESTAMP
  `).bind(
    payload.date,
    payload.source,
    payload.deviceId,
    payload.timezone,
    payload.totalMs,
    payload.reportedAt,
    payload.appVersion,
    payload.schemaVersion
  );

  const deleteItems = env.DB.prepare(`
    DELETE FROM usage_items WHERE date = ? AND source = ? AND device_id = ?
  `).bind(payload.date, payload.source, payload.deviceId);

  const insertItems = payload.items.map(item => env.DB.prepare(`
    INSERT INTO usage_items (date, source, device_id, bundle, duration_ms)
    VALUES (?, ?, ?, ?, ?)
  `).bind(payload.date, payload.source, payload.deviceId, item.bundle, item.durationMs));

  await env.DB.batch([daily, deleteItems, ...insertItems]);
  return json({ ok: true });
}

async function handleGetUsage(url, env) {
  const from = url.searchParams.get("from") || "1970-01-01";
  const to = url.searchParams.get("to") || "9999-12-31";
  const rows = await env.DB.prepare(`
    SELECT
      d.date,
      d.source,
      d.device_id AS deviceId,
      d.timezone,
      d.total_ms AS totalMs,
      d.reported_at AS reportedAt,
      d.app_version AS appVersion,
      d.schema_version AS schemaVersion,
      d.uploaded_at AS uploadedAt,
      COALESCE(json_group_array(json_object('bundle', i.bundle, 'durationMs', i.duration_ms)), '[]') AS items
    FROM usage_daily d
    LEFT JOIN usage_items i
      ON i.date = d.date AND i.source = d.source AND i.device_id = d.device_id
    WHERE d.date >= ? AND d.date <= ?
    GROUP BY d.date, d.source, d.device_id
    ORDER BY d.date DESC, d.source, d.device_id
  `).bind(from, to).all();

  return json({
    ok: true,
    data: rows.results.map(row => ({
      ...row,
      items: JSON.parse(row.items).filter(item => item.bundle)
    }))
  });
}

function validatePayload(payload) {
  if (!payload || typeof payload !== "object") return "invalid json";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(payload.date || "")) return "invalid date";
  if (!["web", "android", "harmony"].includes(payload.source)) return "invalid source";
  if (!payload.deviceId || typeof payload.deviceId !== "string") return "invalid deviceId";
  if (!payload.timezone || typeof payload.timezone !== "string") return "invalid timezone";
  if (!Number.isSafeInteger(payload.totalMs) || payload.totalMs < 0) return "invalid totalMs";
  if (!payload.reportedAt || typeof payload.reportedAt !== "string") return "invalid reportedAt";
  if (!payload.appVersion || typeof payload.appVersion !== "string") return "invalid appVersion";
  if (payload.schemaVersion !== 1) return "invalid schemaVersion";
  if (!Array.isArray(payload.items)) return "invalid items";
  const sum = payload.items.reduce((total, item) => total + (Number.isSafeInteger(item.durationMs) ? item.durationMs : NaN), 0);
  if (sum !== payload.totalMs) return "totalMs mismatch";
  for (const item of payload.items) {
    if (!item.bundle || typeof item.bundle !== "string") return "invalid bundle";
    if (!Number.isSafeInteger(item.durationMs) || item.durationMs < 0) return "invalid durationMs";
  }
  return "";
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
