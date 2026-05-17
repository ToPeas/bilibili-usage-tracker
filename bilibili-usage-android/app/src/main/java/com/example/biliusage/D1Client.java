package com.example.biliusage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class D1Client {
    private final SettingsStore settings;

    D1Client(SettingsStore settings) {
        this.settings = settings;
    }

    void ensureSchema() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(statement("CREATE TABLE IF NOT EXISTS usage_daily (date TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, device_alias TEXT NOT NULL DEFAULT '', timezone TEXT NOT NULL, total_ms INTEGER NOT NULL, reported_at TEXT NOT NULL, app_version TEXT NOT NULL, schema_version INTEGER NOT NULL, uploaded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (date, source, device_id))"));
        batch.put(statement("CREATE TABLE IF NOT EXISTS usage_items (date TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, bundle TEXT NOT NULL, duration_ms INTEGER NOT NULL, PRIMARY KEY (date, source, device_id, bundle), FOREIGN KEY (date, source, device_id) REFERENCES usage_daily(date, source, device_id) ON DELETE CASCADE)"));
        batch.put(statement("CREATE INDEX IF NOT EXISTS idx_usage_daily_date ON usage_daily(date)"));
        batch.put(statement("CREATE INDEX IF NOT EXISTS idx_usage_items_bundle ON usage_items(bundle)"));
        execute(new JSONObject().put("batch", batch));

        JSONObject columns = execute(new JSONObject()
                .put("sql", "PRAGMA table_info(usage_daily)")
                .put("params", new JSONArray()));
        JSONArray results = columns.getJSONArray("result").getJSONObject(0).optJSONArray("results");
        boolean hasAlias = false;
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                if ("device_alias".equals(results.getJSONObject(i).optString("name"))) {
                    hasAlias = true;
                    break;
                }
            }
        }
        if (!hasAlias) {
            execute(new JSONObject()
                    .put("sql", "ALTER TABLE usage_daily ADD COLUMN device_alias TEXT NOT NULL DEFAULT ''")
                    .put("params", new JSONArray()));
        }
    }

    void upload(JSONObject payload) throws Exception {
        ensureSchema();
        JSONArray batch = new JSONArray();
        JSONArray dailyParams = new JSONArray()
                .put(payload.getString("date"))
                .put(payload.getString("source"))
                .put(payload.getString("deviceId"))
                .put(payload.optString("deviceAlias", payload.getString("deviceId")))
                .put(payload.getString("timezone"))
                .put(payload.getLong("totalMs"))
                .put(payload.getString("reportedAt"))
                .put(payload.getString("appVersion"))
                .put(payload.getInt("schemaVersion"));
        batch.put(new JSONObject()
                .put("sql", "INSERT INTO usage_daily (date, source, device_id, device_alias, timezone, total_ms, reported_at, app_version, schema_version, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT(date, source, device_id) DO UPDATE SET device_alias = excluded.device_alias, timezone = excluded.timezone, total_ms = excluded.total_ms, reported_at = excluded.reported_at, app_version = excluded.app_version, schema_version = excluded.schema_version, uploaded_at = CURRENT_TIMESTAMP")
                .put("params", dailyParams));
        batch.put(new JSONObject()
                .put("sql", "DELETE FROM usage_items WHERE date = ? AND source = ? AND device_id = ?")
                .put("params", new JSONArray()
                        .put(payload.getString("date"))
                        .put(payload.getString("source"))
                        .put(payload.getString("deviceId"))));
        JSONArray items = payload.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            batch.put(new JSONObject()
                    .put("sql", "INSERT INTO usage_items (date, source, device_id, bundle, duration_ms) VALUES (?, ?, ?, ?, ?)")
                    .put("params", new JSONArray()
                            .put(payload.getString("date"))
                            .put(payload.getString("source"))
                            .put(payload.getString("deviceId"))
                            .put(item.getString("bundle"))
                            .put(item.getLong("durationMs"))));
        }
        execute(new JSONObject().put("batch", batch));
    }

    JSONObject test() throws Exception {
        ensureSchema();
        return execute(new JSONObject()
                .put("sql", "SELECT 1 AS ok")
                .put("params", new JSONArray()));
    }

    JSONArray queryRecentDays(String from, String to) throws Exception {
        ensureSchema();
        JSONObject response = execute(new JSONObject()
                .put("sql", "SELECT date, COALESCE(device_alias, device_id) AS deviceAlias, device_id AS deviceId, total_ms AS totalMs, uploaded_at AS uploadedAt FROM usage_daily WHERE date >= ? AND date <= ? ORDER BY date DESC, total_ms DESC")
                .put("params", new JSONArray().put(from).put(to)));
        return response.getJSONArray("result").getJSONObject(0).optJSONArray("results");
    }

    private JSONObject statement(String sql) throws Exception {
        return new JSONObject().put("sql", sql).put("params", new JSONArray());
    }

    private JSONObject execute(JSONObject body) throws Exception {
        URL url = new URL("https://api.cloudflare.com/client/v4/accounts/" + settings.accountId + "/d1/database/" + settings.databaseId + "/query");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + settings.apiToken);
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line);
        }
        JSONObject response = new JSONObject(text.toString());
        if (code < 200 || code >= 300 || !response.optBoolean("success")) {
            throw new IllegalStateException(response.toString());
        }
        JSONArray result = response.optJSONArray("result");
        if (result != null) {
            for (int i = 0; i < result.length(); i++) {
                if (!result.getJSONObject(i).optBoolean("success", true)) {
                    throw new IllegalStateException(response.toString());
                }
            }
        }
        return response;
    }
}
