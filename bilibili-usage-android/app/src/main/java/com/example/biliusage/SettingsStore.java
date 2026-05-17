package com.example.biliusage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class SettingsStore {
    private static final String PREFS = "bili_usage_settings";

    final String accountId;
    final String databaseId;
    final String apiToken;
    final String deviceId;
    final String deviceAlias;

    private SettingsStore(String accountId, String databaseId, String apiToken, String deviceId, String deviceAlias) {
        this.accountId = accountId;
        this.databaseId = databaseId;
        this.apiToken = apiToken;
        this.deviceId = deviceId;
        this.deviceAlias = deviceAlias;
    }

    static SettingsStore load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String deviceId = prefs.getString("deviceId", "");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        return new SettingsStore(
                prefs.getString("accountId", ""),
                prefs.getString("databaseId", ""),
                prefs.getString("apiToken", ""),
                deviceId,
                prefs.getString("deviceAlias", "")
        );
    }

    static void save(Context context, String accountId, String databaseId, String apiToken, String deviceId, String deviceAlias) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("accountId", accountId)
                .putString("databaseId", databaseId)
                .putString("apiToken", apiToken)
                .putString("deviceId", deviceId)
                .putString("deviceAlias", deviceAlias)
                .apply();
    }

    boolean isCloudConfigured() {
        return !accountId.isEmpty() && !databaseId.isEmpty() && !apiToken.isEmpty() && !deviceId.isEmpty();
    }
}
