package com.example.biliusage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

final class UsageCache {
    private static final String PREFS = "bili_usage_cache";

    private UsageCache() {}

    static JSONArray getRecentRows(Context context, SettingsStore settings, String from, String to) {
        return getArray(context, recentKey(settings, from, to));
    }

    static void putRecentRows(Context context, SettingsStore settings, String from, String to, JSONArray rows) {
        putArray(context, recentKey(settings, from, to), rows);
    }

    static JSONArray getDayHours(Context context, SettingsStore settings, String date) {
        return getArray(context, hoursKey(settings, date));
    }

    static void putDayHours(Context context, SettingsStore settings, String date, JSONArray rows) {
        putArray(context, hoursKey(settings, date), rows);
    }

    private static JSONArray getArray(Context context, String key) {
        String text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        if (text == null || text.isEmpty()) return null;
        try {
            return new JSONArray(text);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void putArray(Context context, String key, JSONArray rows) {
        if (rows == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, rows.toString())
                .putLong(key + ":updatedAt", System.currentTimeMillis())
                .apply();
    }

    private static String recentKey(SettingsStore settings, String from, String to) {
        return "recent:" + stableDbKey(settings) + ":" + from + ":" + to;
    }

    private static String hoursKey(SettingsStore settings, String date) {
        return "hours:" + stableDbKey(settings) + ":" + date;
    }

    private static String stableDbKey(SettingsStore settings) {
        String raw = (settings.accountId == null ? "" : settings.accountId)
                + "|"
                + (settings.databaseId == null ? "" : settings.databaseId);
        return Integer.toHexString(raw.hashCode());
    }
}
