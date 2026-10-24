// Veglia · foreground poll service. Copyright (c) 2026 Evelyn & River — MIT License.
package dev.veglia.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;

public class CompanionService extends Service {
    private static final String CHANNEL_ID = "veglia_companion";
    private static final int NOTIFICATION_ID = 1;
    private static volatile boolean running = false;

    private String serverUrl;
    private String token;
    private Handler pollHandler;
    private HandlerThread pollThread;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);

        if (intent != null) {
            serverUrl = intent.getStringExtra("server_url");
            token = intent.getStringExtra("token");
        }
        // On STICKY restart the intent is null: heal from saved config instead of dying.
        if (serverUrl == null || token == null) {
            SharedPreferences prefs = getSharedPreferences("veglia_companion", MODE_PRIVATE);
            serverUrl = prefs.getString("server_url", null);
            token = prefs.getString("token", null);
        }
        if (serverUrl == null || token == null || serverUrl.isEmpty() || token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            startPolling();
        }
        ensureShizukuListenerRegistered();

        return START_STICKY;
    }

    private static volatile boolean shizukuListenerRegistered = false;

    // Sticky: fires immediately with the current state even if the binder arrived
    // before this listener was added (e.g. Shizuku was already running at boot).
    private void ensureShizukuListenerRegistered() {
        if (shizukuListenerRegistered) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
            });
            shizukuListenerRegistered = true;
        } catch (Throwable t) {
            // Never let a Shizuku hiccup take the whole foreground service down
            // with it — screenshots must keep working even if control can't.
        }
    }

    private void startPolling() {
        pollThread = new HandlerThread("PollThread");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.post(this::pollLoop);
    }

    private void pollLoop() {
        if (!running) return;
        try {
            String body = pollServer();
            if (body != null && body.contains("\"peek\"")) {
                ScreenshotService ss = ScreenshotService.getInstance();
                if (ss != null) {
                    ss.doScreenshot(serverUrl, token);
                }
            } else if (body != null && body.contains("\"control\"")) {
                String cmdB64 = extractJsonField(body, "cmd_b64");
                if (cmdB64 != null) runControl(cmdB64);
            } else if (body != null && body.contains("\"alarm\"")) {
                String alarmB64 = extractJsonField(body, "alarm_b64");
                if (alarmB64 != null) runAlarm(alarmB64);
            } else if (body != null && body.contains("\"ble\"")) {
                String bleB64 = extractJsonField(body, "ble_b64");
                if (bleB64 != null) runBle(bleB64);
            } else if (body != null && body.contains("\"audio\"")) {
                String audioB64 = extractJsonField(body, "audio_b64");
                if (audioB64 != null) runAudio(audioB64);
            }
        } catch (Exception e) {
        }
        if (running) {
            pollHandler.postDelayed(this::pollLoop, 3000);
        }
    }

    private String pollServer() throws Exception {
        String urlStr = serverUrl + "/phone/poll?token=" + token;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    // Base64 payloads never contain a quote, so a naive "between the next two
    // quotes after the key" scan is safe without a real JSON parser.
    private static String extractJsonField(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = body.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    private void runControl(String cmdB64) {
        try {
            String cmd = new String(android.util.Base64.decode(cmdB64, android.util.Base64.DEFAULT), "UTF-8");
            int exitCode = -1;
            String output = "";
            if (ShizukuBridge.isPermissionGranted()) {
                ShizukuBridge.Result r = ShizukuBridge.runShellCommand(cmd);
                exitCode = r.exitCode;
                output = r.output;
            } else {
                output = "shizuku permission not granted";
            }
            reportControlResult(exitCode, output);
        } catch (Exception e) {
            reportControlResult(-1, "error: " + e.getMessage());
        }
    }

    // Sets an alarm under the app's own identity.
    // Sending SET_ALARM through Shizuku's shell identity can be silently dropped by some
    // clock apps (the shell has no SET_ALARM permission, yet the command still returns
    // exit_code 0). Veglia declares this normal permission in its manifest, so sending it
    // from the app works, and no UI has to be shown.
    // The payload is base64, format "HH:MM:message", same approach as the control command.
    private void runAlarm(String alarmB64) {
        try {
            String payload = new String(
                    android.util.Base64.decode(alarmB64, android.util.Base64.DEFAULT), "UTF-8");
            String[] parts = payload.split(":", 3);
            if (parts.length < 2) {
                reportControlResult(-1, "bad alarm payload");
                return;
            }
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            String message = parts.length >= 3 ? parts[2] : "";

            Intent i = new Intent("android.intent.action.SET_ALARM");
            i.putExtra("android.intent.extra.alarm.HOUR", hour);
            i.putExtra("android.intent.extra.alarm.MINUTES", minute);
            i.putExtra("android.intent.extra.alarm.MESSAGE", message);
            // Key point: do not show the clock UI, so this also works while the phone is idle.
            i.putExtra("android.intent.extra.alarm.SKIP_UI", true);
            i.putExtra("android.intent.extra.alarm.VIBRATE", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            reportControlResult(0, "alarm set " + hour + ":" + minute);
        } catch (Exception e) {
            reportControlResult(-1, "alarm error: " + e.getMessage());
        }
    }

    /*
     * Generic BLE write command (see BleBridge). Payloads:
     *   "config:<name prefix>|<service uuid>|<characteristic uuid>"
     *                  set which device to look for and where to write
     *   "probe"        connect without writing; reports the real service list on failure
     *   "off"          disconnect
     *   "<hex>"        connect and write these bytes to the configured characteristic, e.g. "aa5501ff"
     *   "rep:<hex>:<count>:<interval ms>"   write the same bytes repeatedly
     */
    private void runBle(String bleB64) {
        try {
            String payload = new String(
                    android.util.Base64.decode(bleB64, android.util.Base64.DEFAULT), "UTF-8").trim();
            BleBridge.Result cb = new BleBridge.Result() {
                public void done(int code, String msg) {
                    reportControlResult(code, "ble: " + msg);
                }
            };
            if (payload.startsWith("config:")) {
                String[] cfg = payload.substring(7).split("\\|");
                if (cfg.length != 3) {
                    reportControlResult(-1, "ble: config format is config:<prefix>|<service uuid>|<char uuid>");
                    return;
                }
                String err = BleBridge.configure(this, cfg[0], cfg[1], cfg[2]);
                reportControlResult(err == null ? 0 : -1, "ble: " + (err == null ? "configured" : err));
            } else if ("probe".equalsIgnoreCase(payload)) {
                BleBridge.probe(this, cb);
            } else if ("off".equalsIgnoreCase(payload) || "stop".equalsIgnoreCase(payload)) {
                BleBridge.disconnect();
                reportControlResult(0, "ble: disconnected");
            } else if (payload.startsWith("rep:")) {
                // Some devices treat each packet as a single momentary action instead of
                // running until told to stop. The phone polls the server every few
                // seconds and a new command replaces any not yet picked up, so packets
                // cannot be delivered fast enough one by one. The server therefore sends
                // one command and the phone repeats the write itself at millisecond intervals.
                // Format: rep:<hex>:<count>:<interval ms>   e.g. rep:aa5501ff:60:250
                runBleRepeat(payload, cb);
            } else {
                BleBridge.write(this, payload, cb);
            }
        } catch (Exception e) {
            reportControlResult(-1, "ble error: " + e.getMessage());
        }
    }

    private Thread bleRepeatThread;

    private void runBleRepeat(String payload, final BleBridge.Result cb) {
        final String[] parts = payload.split(":");
        if (parts.length < 4) {
            reportControlResult(-1, "ble: rep format is rep:<hex>:<count>:<interval ms>");
            return;
        }
        final String hex = parts[1];
        int n, gap;
        try {
            n = Integer.parseInt(parts[2]);
            gap = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            reportControlResult(-1, "ble: rep count/interval is not a number");
            return;
        }
        // Safety caps: at most 600 writes and an interval of at least 80 ms, so one command cannot tie up the device and thread.
        final int count = Math.max(1, Math.min(600, n));
        final int interval = Math.max(80, Math.min(5000, gap));

        // A new repeat task replaces the old one; two threads must never write to the device at once.
        if (bleRepeatThread != null && bleRepeatThread.isAlive()) {
            bleRepeatThread.interrupt();
        }
        bleRepeatThread = new Thread(new Runnable() {
            public void run() {
                int sent = 0;
                try {
                    for (int i = 0; i < count; i++) {
                        if (Thread.currentThread().isInterrupted()) break;
                        // Do not pass null: BleBridge.write calls cb.done(...) directly on a bad
                        // hex string, which would be an NPE. An empty callback is fine, since
                        // repeated writes do not need to report every packet.
                        BleBridge.write(CompanionService.this, hex, new BleBridge.Result() {
                            public void done(int code, String msg) { }
                        });
                        sent++;
                        Thread.sleep(interval);
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                }
                reportControlResult(0, "ble: repeated " + sent + "x " + hex + " @" + interval + "ms");
            }
        });
        bleRepeatThread.start();
    }

    // Native audio playback: sound is played by this foreground service instead of a web
    // page. A page playing audio is suspended once the browser leaves the foreground; a
    // foreground service is not affected by which app is in front.
    private android.media.MediaPlayer player;

    private void runAudio(String audioB64) {
        try {
            String payload = new String(
                    android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT), "UTF-8").trim();
            // The payload is "<route>\n<url>\n<caption>". Shorter forms are accepted too (the
            // old url-only format and the route+url format), so mismatched server and APK
            // versions do not go silent.
            String route = "speaker", url = payload, caption = "";
            String[] parts = payload.split("\n", 3);
            if (parts.length >= 2) {
                route = parts[0].trim();
                url = parts[1].trim();
                if (parts.length >= 3) caption = parts[2].trim();
            }
            if ("stop".equalsIgnoreCase(url)) {
                stopAudio();
                reportControlResult(0, "audio: stopped");
                return;
            }
            stopAudio();
            boolean earpiece = "earpiece".equalsIgnoreCase(route);
            // Earpiece: switch the audio mode to in-communication and turn off the speakerphone,
            // so sound comes out of the small earpiece speaker instead of the loudspeaker,
            // which is useful in public places without headphones.
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
            // Forcing the earpiece while a headset is connected would play sound to nobody.
            // So when a wired or Bluetooth headset is connected, do not force the earpiece and
            // let the system route to the headset. The earpiece only matters when there is none.
            if (am != null && earpiece && hasHeadset(am)) {
                earpiece = false;
            }
            try {
                if (am != null) {
                    am.setMode(earpiece ? android.media.AudioManager.MODE_IN_COMMUNICATION
                                        : android.media.AudioManager.MODE_NORMAL);
                    // Do not turn the speakerphone on with a headset connected, it would pull sound back to the speaker.
                    am.setSpeakerphoneOn(!earpiece && !hasHeadset(am));
                }
            } catch (Exception ignored) {
            }
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(earpiece ? android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                                       : android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            // Streaming works too. MediaPlayer supports custom request headers, so it can play a
            // streaming TTS endpoint directly, without synthesizing a file server-side first.
            // Convention: if the url carries "#auth=<token>", split it off and send it as the
            // Authorization header.
            String auth = "";
            int hash = url.indexOf("#auth=");
            if (hash > 0) {
                auth = url.substring(hash + 6);
                url = url.substring(0, hash);
            }
            if (auth.length() > 0) {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + auth);
                mp.setDataSource(this, android.net.Uri.parse(url), headers);
            } else {
                mp.setDataSource(url);
            }
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                public void onCompletion(android.media.MediaPlayer m) {
                    reportControlResult(0, "audio: finished");
                    stopAudio();
                }
            });
            mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                public boolean onError(android.media.MediaPlayer m, int what, int extra) {
                    reportControlResult(-1, "audio error: what=" + what + " extra=" + extra);
                    return true;
                }
            });
            final String cap = caption;
            mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                public void onPrepared(android.media.MediaPlayer m) {
                    m.start();
                    if (cap != null && cap.length() > 0) showCaption(cap);
                    reportControlResult(0, "audio: playing");
                }
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            reportControlResult(-1, "audio error: " + e.getMessage());
        }
    }

    // Shows a caption alongside the audio, e.g. a translation of what is being spoken, even
    // while another app is in the foreground. A high-priority notification is the fallback
    // because it needs no SYSTEM_ALERT_WINDOW grant and works right after install; the
    // overlay is preferred when its permission has been granted.
    private static final String CAPTION_CHANNEL_ID = "veglia_caption";
    private static final int CAPTION_NOTIF_ID = 4321;

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void showCaption(String text) {
        // Prefer the overlay: a heads-up banner vanishes quickly and long lines are hard to read.
        // Display time scales with text length, between 4 and 20 seconds.
        // Without the "display over other apps" permission allowed() returns false and this
        // quietly falls back to a notification.
        try {
            long hold = Math.max(4000, Math.min(20000, text.length() * 260L));
            if (CaptionOverlay.show(this, text, hold)) return;
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CAPTION_CHANNEL_ID, "Captions",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Captions shown while audio is playing");
                ch.setSound(null, null);   // a caption should not beep, the audio is already playing
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CAPTION_CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("Veglia")
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setSmallIcon(android.R.drawable.ic_btn_speak_now)
             .setAutoCancel(true);
            // Do not use setOnlyAlertOnce(true) and do not keep reusing one notification id:
            // the first caption would pop up and every later one would update silently,
            // with no banner again. Give each caption a new id so it alerts every time.
            nm.notify(CAPTION_NOTIF_ID + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Exception ignored) {
        }
    }

    /** Whether a wired or Bluetooth headset is connected. See the comment in runAudio. */
    private boolean hasHeadset(android.media.AudioManager am) {
        try {
            android.media.AudioDeviceInfo[] outs =
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
            if (outs == null) return false;
            for (android.media.AudioDeviceInfo d : outs) {
                int t = d.getType();
                if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void stopAudio() {
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
                player = null;
            }
            // Give the audio routing back to the system, so other apps are not left in call mode.
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(android.media.AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void reportControlResult(int exitCode, String output) {
        try {
            String urlStr = serverUrl + "/phone/control-result?token=" + token;
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            String json = "{\"exit_code\":" + exitCode + ",\"output\":\""
                    + output.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "")
                    + "\"}";
            byte[] out = json.getBytes("UTF-8");
            conn.getOutputStream().write(out);
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Veglia Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("keeps the connection alive");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Veglia")
                // Show the version name in the ongoing notification, so both sides can
                // see which build is installed without asking each other.
                .setContentText("connected · v" + appVersionName())
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        stopAudio();
        if (pollThread != null) {
            pollThread.quitSafely();
        }
        super.onDestroy();
    }
}
