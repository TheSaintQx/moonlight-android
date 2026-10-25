// Путь: app/src/main/java/com/limelight/assistant/AssistantCaptureManager.java
// ЗАМЕНИТЬ файл целиком. Публичный интерфейс прежний (конструктор, start, stop),
// поэтому Game.java менять не нужно.
package com.limelight.assistant;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("NewApi")
public class AssistantCaptureManager {
    private static final String TAG = "AssistantCapture";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long COPY_TIMEOUT_MS = 2000;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long RETRY_AFTER_FAILURES_MS = 30000;
    private static final long FIRST_FRAME_DELAY_MS = 1500;

    private final SurfaceView surfaceView;
    private final Context appContext;
    private final String gameTitle;
    private final HandlerThread workerThread;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile AssistantSettings settings;
    private int consecutiveFailures = 0;
    private String lastStatus = "";

    public AssistantCaptureManager(SurfaceView surfaceView, String gameTitle) {
        this.surfaceView = surfaceView;
        this.appContext = surfaceView.getContext().getApplicationContext();
        this.gameTitle = (gameTitle == null || gameTitle.isEmpty()) ? "unknown" : gameTitle;
        this.settings = new AssistantSettings(appContext);
        this.workerThread = new HandlerThread("assistant-capture");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        this.handler = new Handler(workerThread.getLooper());
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        // Настройки перечитываются при каждом запуске/возврате в игру.
        settings = new AssistantSettings(appContext);
        if (!settings.enabled) {
            running.set(false);
            report("выключено в настройках");
            return;
        }
        if (!settings.isConfigured()) {
            running.set(false);
            report("не настроено: укажите адрес и токен");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            running.set(false);
            report("нужен Android 8 или новее");
            return;
        }
        consecutiveFailures = 0;
        // База знаний: сервер хранит её в памяти процесса и теряет при рестарте,
        // поэтому при каждом старте сессии отправляем сохранённую базу заново.
        if (AssistantKb.hasSaved(appContext)) {
            Thread kbThread = new Thread(() -> AssistantKb.uploadIfSaved(appContext));
            kbThread.setDaemon(true);
            kbThread.setName("assistant-kb-upload");
            kbThread.start();
        }
        handler.postDelayed(this::cycle, FIRST_FRAME_DELAY_MS);
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacksAndMessages(null);
    }

    private void cycle() {
        if (!running.get()) {
            return;
        }
        boolean ok;
        try {
            ok = captureAndSend();
        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            report("ошибка: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " (" + t.getMessage() + ")" : ""));
            ok = false;
        }
        if (ok) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
        }
        if (running.get()) {
            long delay = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                    ? RETRY_AFTER_FAILURES_MS : AssistantConfig.PERIOD_MS;
            handler.postDelayed(this::cycle, delay);
        }
    }

    private boolean captureAndSend() {
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w <= 0 || h <= 0) {
            report("кадр не снят: экран игры ещё не готов");
            return false;
        }
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!pixelCopy(surfaceView, full)) {
            return false; // bitmap не освобождаем вручную: PixelCopy мог ещё писать в него
        }
        Bitmap small = downscale(full);
        byte[] jpeg = toJpeg(small);
        small.recycle();
        return send(jpeg);
    }

    private boolean pixelCopy(SurfaceView view, Bitmap target) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(-1);
        PixelCopy.request(view, target, code -> {
            result.set(code);
            latch.countDown();
        }, new Handler(Looper.getMainLooper()));
        try {
            if (!latch.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                report("кадр не снят: PixelCopy не ответил за 2 с");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (result.get() != PixelCopy.SUCCESS) {
            report("кадр не снят: PixelCopy, код " + result.get());
            return false;
        }
        return true;
    }

    private Bitmap downscale(Bitmap src) {
        int width = src.getWidth();
        if (width <= AssistantConfig.TARGET_WIDTH) {
            return src;
        }
        float scale = (float) AssistantConfig.TARGET_WIDTH / width;
        int newHeight = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, AssistantConfig.TARGET_WIDTH, newHeight, true);
        if (scaled != src) {
            src.recycle();
        }
        return scaled;
    }

    private byte[] toJpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, AssistantConfig.JPEG_QUALITY, out);
        return out.toByteArray();
    }

    private boolean send(byte[] jpeg) {
        AssistantSettings s = settings;
        HttpURLConnection conn = null;
        try {
            JSONObject meta = new JSONObject();
            if (!s.pairCode.isEmpty()) {
                meta.put("pair_code", s.pairCode);
            }
            JSONObject body = new JSONObject();
            body.put("game", gameTitle);
            body.put("ts", System.currentTimeMillis());
            body.put("meta", meta);
            body.put("jpeg_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(s.url + AssistantSettings.FRAME_PATH).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + s.token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                report("кадры уходят: HTTP " + code);
                return true;
            }
            String hint = (code == 401 || code == 403) ? " - проверьте токен" : "";
            report("сервер ответил HTTP " + code + hint + errorSnippet(conn));
            return false;
        } catch (Exception e) {
            report("нет связи с сервером: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String errorSnippet(HttpURLConnection conn) {
        try (InputStream is = conn.getErrorStream()) {
            if (is == null) {
                return "";
            }
            byte[] buf = new byte[160];
            int n = is.read(buf);
            if (n <= 0) {
                return "";
            }
            return ": " + new String(buf, 0, n, StandardCharsets.UTF_8).replace('\n', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    // Статус пишется только при смене, чтобы не трогать настройки каждые 4 секунды.
    private synchronized void report(String status) {
        if (status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        Log.i(TAG, status);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        AssistantSettings.saveStatus(appContext, time + " - " + status);
    }
}
