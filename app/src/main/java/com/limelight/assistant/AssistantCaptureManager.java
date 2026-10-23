package com.limelight.assistant;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Window;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Захват кадров игрового экрана (PixelCopy с окна активности), сжатие в JPEG
 * и отправка на бэкенд ассистента. Работает в собственном потоке и не влияет
 * на стриминговый пайплайн Moonlight. Сетевые сбои не останавливают цикл:
 * после серии ошибок интервал проб увеличивается. Требует Android 8.0+ (API 26).
 */
public final class AssistantCaptureManager {

    private static final String TAG = "AssistantCapture";
    private static final long COPY_TIMEOUT_MS = 2000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long RETRY_AFTER_FAILURES_MS = 30000;

    private final Activity activity;
    private final HandlerThread workerThread;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String backendUrl;
    private final String authToken;
    private final String gameTitle;
    private int consecutiveFailures = 0;

    public AssistantCaptureManager(Activity activity, String gameTitle) {
        this.activity = activity;
        this.gameTitle = (gameTitle == null || gameTitle.isEmpty()) ? "unknown" : gameTitle;
        this.backendUrl = AssistantConfig.BACKEND_URL;
        this.authToken = AssistantConfig.AUTH_TOKEN;
        workerThread = new HandlerThread("assistant-capture");
        workerThread.setDaemon(true);
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        consecutiveFailures = 0;
        handler.postDelayed(this::cycle, AssistantConfig.PERIOD_MS);
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacksAndMessages(null);
    }

    private void cycle() {
        if (!running.get()) {
            return;
        }
        long nextDelay = AssistantConfig.PERIOD_MS;
        try {
            if (captureAndSend()) {
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    nextDelay = RETRY_AFTER_FAILURES_MS;
                    Log.w(TAG, "backend unreachable, retry interval raised to "
                            + nextDelay + " ms");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cycle error", t);
        }
        if (running.get()) {
            handler.postDelayed(this::cycle, nextDelay);
        }
    }

    /** true — кадр отправлен успешно. */
    private boolean captureAndSend() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return false;
        }
        int w = window.getDecorView().getWidth();
        int h = window.getDecorView().getHeight();
        if (w < 64 || h < 64) {
            return false; // поверхность ещё не готова — это не ошибка сети
        }

        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!pixelCopy(window, full)) {
            full.recycle();
            return false;
        }
        Bitmap small = downscale(full);
        byte[] jpeg = toJpeg(small);
        small.recycle();
        if (jpeg == null || jpeg.length == 0) {
            return false;
        }
        return send(jpeg);
    }

    private boolean pixelCopy(Window window, Bitmap dest) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        try {
            PixelCopy.request(window, dest, status -> {
                ok.set(status == PixelCopy.SUCCESS);
                latch.countDown();
            }, handler);
        } catch (Exception e) {
            return false;
        }
        try {
            if (!latch.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok.get();
    }

    private Bitmap downscale(Bitmap src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= AssistantConfig.TARGET_WIDTH) {
            return src;
        }
        int dstH = Math.round(srcH * ((float) AssistantConfig.TARGET_WIDTH / srcW));
        Bitmap scaled = Bitmap.createScaledBitmap(
                src, AssistantConfig.TARGET_WIDTH, dstH, true);
        src.recycle();
        return scaled;
    }

    private byte[] toJpeg(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, AssistantConfig.JPEG_QUALITY, bos);
        return bos.toByteArray();
    }

    private boolean send(byte[] jpeg) {
        HttpURLConnection conn = null;
        try {
            JSONObject meta = new JSONObject();
            meta.put("game", gameTitle);
            meta.put("ts", System.currentTimeMillis());

            JSONObject body = new JSONObject();
            body.put("meta", meta);
            body.put("jpeg_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP));

            conn = (HttpURLConnection) new URL(backendUrl + "/v1/context/frame")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            Log.w(TAG, "backend returned HTTP " + code);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "send failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
