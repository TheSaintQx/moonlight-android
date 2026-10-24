// Путь: app/src/main/java/com/limelight/assistant/AssistantSettings.java
// НОВЫЙ файл. Читает настройки помощника из обычных настроек приложения.
package com.limelight.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class AssistantSettings {
    public static final String KEY_ENABLED = "checkbox_assistant_enabled";
    public static final String KEY_URL = "edit_assistant_url";
    public static final String KEY_TOKEN = "edit_assistant_token";
    public static final String KEY_PAIR_CODE = "edit_assistant_pair_code";
    public static final String KEY_STATUS = "assistant_last_status";
    public static final String FRAME_PATH = "/v1/context/frame";

    public final boolean enabled;
    public final String url;
    public final String token;
    public final String pairCode;

    public AssistantSettings(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        url = normalizeUrl(prefs.getString(KEY_URL, ""));
        token = clean(prefs.getString(KEY_TOKEN, ""));
        pairCode = clean(prefs.getString(KEY_PAIR_CODE, ""));
    }

    public boolean isConfigured() {
        return !url.isEmpty() && !token.isEmpty();
    }

    public static void saveStatus(Context context, String status) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(KEY_STATUS, status).apply();
    }

    // Пробелы и переводы строк при вставке из буфера - частая причина ответа 401.
    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeUrl(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) {
            return "";
        }
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        s = stripSlashes(s);
        if (s.endsWith(FRAME_PATH)) {
            s = stripSlashes(s.substring(0, s.length() - FRAME_PATH.length()));
        }
        return s;
    }

    private static String stripSlashes(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
