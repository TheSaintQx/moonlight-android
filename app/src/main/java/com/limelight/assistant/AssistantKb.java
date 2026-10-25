// Путь: app/src/main/java/com/limelight/assistant/AssistantKb.java
// НОВЫЙ файл. База знаний: выбор JSON в настройках, проверка структуры,
// сохранение на устройстве и отправка на сервер (POST /v1/kb).
// Сервер держит базу в памяти процесса и теряет её при рестарте, поэтому
// повторную отправку делает uploadIfSaved() при старте сессии.
package com.limelight.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AssistantKb {
    private static final String TAG = "AssistantKb";
    public static final int REQUEST_CODE = 4711;
    public static final String KEY_KB_STATUS = "assistant_kb_status";
    public static final String KEY_KB_NAME = "assistant_kb_name";
    static final String KEY_KB_JSON = "assistant_kb_json";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String KB_PATH = "/v1/kb";

    private AssistantKb() {
    }

    public static boolean hasSaved(Context context) {
        return !prefs(context).getString(KEY_KB_JSON, "").isEmpty();
    }

    // Вызов из KbLoaderPreference.onClick: запускаем системный выбор файла.
    // SAF (ACTION_OPEN_DOCUMENT) не требует прав на хранилище.
    public static void pick(Context context) {
        if (!(context instanceof Activity)) {
            setStatus(context, "выбор файла недоступен с этого экрана");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        ((Activity) context).startActivityForResult(intent, REQUEST_CODE);
    }

    // Вызов из StreamSettings.onActivityResult().
    public static void handleActivityResult(Context context, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return; // выбор отменён - статус не трогаем
        }
        loadAndApply(context, data.getData());
    }

    private static void loadAndApply(Context context, Uri uri) {
        String text;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                setStatus(context, "файл не читается");
                return;
            }
            byte[] buf = new byte[(int) MAX_BYTES + 1];
            int total = 0;
            int n;
            while ((n = is.read(buf, total, buf.length - total)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    setStatus(context, "файл больше 2 МБ - отклонён");
                    return;
                }
            }
            text = new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            setStatus(context, "ошибка чтения: " + e.getClass().getSimpleName());
            return;
        }

        int articles = validate(text);
        if (articles < 0) {
            setStatus(context, "это не база знаний: нужен JSON с полями game и articles");
            return;
        }

        prefs(context).edit()
                .putString(KEY_KB_JSON, text)
                .putString(KEY_KB_NAME, lastSegment(uri))
                .apply();

        setStatus(context, "сохранено, " + articles + " статей - отправка...");
        new Thread(() -> uploadIfSaved(context), "assistant-kb-upload").start();
    }

    // Повторная отправка сохранённой базы при старте сессии.
    public static void uploadIfSaved(Context context) {
        String text = prefs(context).getString(KEY_KB_JSON, "");
        if (text.isEmpty()) {
            return;
        }
        upload(context, text);
    }

    private static void upload(Context context, String text) {
        AssistantSettings s = new AssistantSettings(context);
        if (!s.isConfigured()) {
            setStatus(context, "не отправлено: укажите адрес и токен помощника");
            return;
        }
        HttpURLConnection conn = null;
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(s.url + KB_PATH).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + s.token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                setStatus(context, "на сервере: " + countArticles(text) + " статей (" + now() + ")");
            } else {
                String hint = (code == 401 || code == 403) ? " - проверьте токен" : "";
                setStatus(context, "сервер ответил HTTP " + code + hint);
            }
        } catch (Exception e) {
            setStatus(context, "нет связи с сервером: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // Проверка структуры: {"game": "...", "articles": [{"id", "title"}, ...]}.
    static int validate(String text) {
        try {
            JSONObject root = new JSONObject(text);
            if (root.optString("game").trim().isEmpty()) {
                return -1;
            }
            JSONArray arts = root.optJSONArray("articles");
            if (arts == null || arts.length() == 0) {
                return -1;
            }
            return arts.length();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int countArticles(String text) {
        try {
            return new JSONObject(text).getJSONArray("articles").length();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String lastSegment(Uri uri) {
        String s = uri.getLastPathSegment();
        return s == null || s.isEmpty() ? "файл базы" : s;
    }

    static void setStatus(Context context, String status) {
        Log.i(TAG, status);
        prefs(context).edit().putString(KEY_KB_STATUS, status).apply();
        KbLoaderPreference.notifyStatusChanged();
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}
