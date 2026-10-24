// Veglia · screenshot via AccessibilityService. Copyright (c) 2026 Evelyn & River — MIT License.
package dev.veglia.companion;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ScreenshotService extends AccessibilityService {
    private static volatile ScreenshotService instance;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public static ScreenshotService getInstance() {
        return instance;
    }

    // Watchdog: the accessibility service is kept alive by the system (it comes
    // back after a restart). We borrow its lifespan to guard the poll service.
    // If the system killed CompanionService, revive it — unless the user stopped it.
    private Handler watchdog;
    private final Runnable watchdogTick = new Runnable() {
        @Override
        public void run() {
            try {
                SharedPreferences prefs = getSharedPreferences("veglia_companion", MODE_PRIVATE);
                String url = prefs.getString("server_url", "");
                String tk = prefs.getString("token", "");
                boolean userStopped = prefs.getBoolean("user_stopped", false);
                if (!CompanionService.isRunning() && !userStopped && !url.isEmpty() && !tk.isEmpty()) {
                    Intent i = new Intent(ScreenshotService.this, CompanionService.class);
                    i.putExtra("server_url", url);
                    i.putExtra("token", tk);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                }
            } catch (Exception e) {
            }
            if (watchdog != null) {
                watchdog.postDelayed(this, 60000);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        watchdog = new Handler(Looper.getMainLooper());
        watchdog.postDelayed(watchdogTick, 15000);
    }

    private String lastReportedPackage = "";
    private final Executor appEventExecutor = Executors.newSingleThreadExecutor();

    // Input methods and systemui overlays fire TYPE_WINDOW_STATE_CHANGED too, but
    // popping the keyboard up/down isn't a real "switched app" — if we let those
    // update lastReportedPackage, returning to the same app afterward looks like
    // a fresh switch and we re-report it. So they're skipped entirely here,
    // never touching lastReportedPackage, not just filtered downstream.
    private boolean isNoisePackage(String pkgName) {
        String low = pkgName.toLowerCase();
        return low.contains("inputmethod") || low.contains(".input")
                || low.contains("systemui") || low.contains("swiftkey");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString();
        // Ignore our own app and the system launcher/UI switching in front of itself.
        if (pkgName.equals(getPackageName())) return;
        if (isNoisePackage(pkgName)) return;
        if (pkgName.equals(lastReportedPackage)) return;
        lastReportedPackage = pkgName;
        reportAppSwitch(pkgName);
    }

    private void reportAppSwitch(String pkgName) {
        appEventExecutor.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("veglia_companion", MODE_PRIVATE);
                String serverUrl = prefs.getString("server_url", "");
                String token = prefs.getString("token", "");
                if (serverUrl.isEmpty() || token.isEmpty()) return;

                String urlStr = serverUrl + "/phone/app-event?token=" + token;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                String body = "{\"package\":\"" + pkgName + "\"}";
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
            }
        });
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (watchdog != null) {
            watchdog.removeCallbacksAndMessages(null);
            watchdog = null;
        }
        super.onDestroy();
    }

    public void doScreenshot(String serverUrl, String token) {
        if (Build.VERSION.SDK_INT < 30) return;

        takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                try {
                    Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            result.getHardwareBuffer(), result.getColorSpace());
                    if (hardwareBitmap == null) return;

                    Bitmap bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    hardwareBitmap.recycle();
                    result.getHardwareBuffer().close();

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    bitmap.recycle();

                    byte[] data = out.toByteArray();
                    if (data.length > 100) {
                        uploadScreenshot(data, serverUrl, token);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(int errorCode) {
            }
        });
    }

    private void uploadScreenshot(byte[] data, String serverUrl, String token) {
        try {
            String urlStr = serverUrl + "/phone/screenshot?token=" + token;
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
