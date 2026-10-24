package dev.veglia.companion;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic BLE write bridge: connect to nearby BLE peripherals and write raw bytes to one
 * characteristic. It knows nothing about any particular device or protocol.
 *
 * What to connect to is configured at runtime and stored in SharedPreferences
 * ("veglia_companion"):
 *   ble_name_prefix   advertised-name prefix to look for (required)
 *   ble_service       service UUID (required)
 *   ble_char          characteristic UUID to write (required)
 * Set them with {@link #configure(Context, String, String, String)}, which the service calls
 * for a "config:<prefix>|<service uuid>|<char uuid>" BLE command.
 *
 * Devices are matched by advertised name, never by MAC address, because a MAC can change
 * when hardware is swapped while the name does not.
 *
 * Several matching devices can be connected at once (they are independent peripherals with
 * their own addresses). A payload is written to every connected device; a device that does
 * not understand it ignores it.
 */
public class BleBridge {

    public interface Result {
        void done(int code, String msg);
    }

    /** Connect to at most this many matching devices, so devices nearby cannot fill all slots. */
    private static final int MAX_DEVICES = 4;
    /**
     * Disconnect after this long without a new command. The link is meant to be released
     * after use, not held indefinitely, so another app can take the device afterwards.
     */
    private static final long IDLE_MS = 90_000;
    /** Scan window: wait this long, then connect to everything found. */
    private static final long SCAN_MS = 6000;

    private static String namePrefix = "";
    private static UUID svcUuid;
    private static UUID charUuid;

    // key = MAC address, so the same device is never connected twice
    private static final Map<String, BluetoothGatt> gatts = new LinkedHashMap<>();
    private static final Map<String, BluetoothGattCharacteristic> chars = new LinkedHashMap<>();
    private static final Map<String, String> names = new LinkedHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable idleKick = new Runnable() {
        public void run() {
            if (!gatts.isEmpty()) disconnect();
        }
    };

    /** Stores the target. Returns null on success, or an error message. */
    public static String configure(Context ctx, String prefix, String service, String characteristic) {
        try {
            if (prefix == null || prefix.trim().isEmpty()) return "empty name prefix";
            UUID s = UUID.fromString(service.trim());
            UUID c = UUID.fromString(characteristic.trim());
            SharedPreferences prefs =
                    ctx.getSharedPreferences("veglia_companion", Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("ble_name_prefix", prefix.trim())
                    .putString("ble_service", s.toString())
                    .putString("ble_char", c.toString())
                    .apply();
            disconnect();
            return null;
        } catch (Exception e) {
            return "bad config: " + e.getMessage();
        }
    }

    /** Loads the configured target. Returns false if it has not been configured. */
    private static boolean loadConfig(Context ctx) {
        try {
            SharedPreferences prefs =
                    ctx.getSharedPreferences("veglia_companion", Context.MODE_PRIVATE);
            String p = prefs.getString("ble_name_prefix", "");
            String s = prefs.getString("ble_service", "");
            String c = prefs.getString("ble_char", "");
            if (p.isEmpty() || s.isEmpty() || c.isEmpty()) return false;
            namePrefix = p;
            svcUuid = UUID.fromString(s);
            charUuid = UUID.fromString(c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Postpones the automatic disconnect after every write. */
    private static void armIdleTimer() {
        main.removeCallbacks(idleKick);
        main.postDelayed(idleKick, IDLE_MS);
    }

    /** Connects if needed and writes these bytes to every connected device. hex looks like "0a1b2c". */
    public static void write(Context ctx, final String hex, final Result cb) {
        final byte[] payload = hexToBytes(hex);
        if (payload == null || payload.length == 0) {
            cb.done(-1, "bad hex payload");
            return;
        }
        if (!chars.isEmpty()) {
            doWriteAll(payload, cb);
            return;
        }
        connectAll(ctx, new Result() {
            public void done(int code, String msg) {
                if (chars.isEmpty()) { cb.done(-1, msg); return; }
                doWriteAll(payload, cb);
            }
        });
    }

    /** Connects without writing, to check that the service and characteristic exist and how many devices connected. */
    public static void probe(final Context ctx, final Result cb) {
        connectAll(ctx, cb);
    }

    public static void disconnect() {
        main.removeCallbacks(idleKick);
        for (BluetoothGatt g : gatts.values()) {
            try { g.disconnect(); g.close(); } catch (Exception ignored) {}
        }
        gatts.clear();
        chars.clear();
        names.clear();
    }

    private static void doWriteAll(byte[] payload, Result cb) {
        int ok = 0, fail = 0;
        StringBuilder who = new StringBuilder();
        for (Map.Entry<String, BluetoothGattCharacteristic> e : chars.entrySet()) {
            BluetoothGatt g = gatts.get(e.getKey());
            if (g == null) continue;
            try {
                BluetoothGattCharacteristic c = e.getValue();
                c.setValue(payload);
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                if (g.writeCharacteristic(c)) {
                    ok++;
                    if (who.length() > 0) who.append(",");
                    who.append(names.get(e.getKey()));
                } else {
                    fail++;
                }
            } catch (SecurityException se) {
                cb.done(-1, "no BLUETOOTH_CONNECT permission");
                return;
            } catch (Exception ex) {
                fail++;
            }
        }
        if (ok == 0) { cb.done(-1, "write failed on all " + fail + " device(s)"); return; }
        armIdleTimer();          // release the link after IDLE_MS without a new command
        cb.done(0, "wrote " + payload.length + " bytes to " + ok + " device(s): " + who
                + (fail > 0 ? (" (" + fail + " failed)") : ""));
    }

    /** Scans for SCAN_MS, collects every device whose name matches, then connects to each. */
    private static void connectAll(final Context ctx, final Result cb) {
        if (!loadConfig(ctx)) {
            cb.done(-1, "ble target not configured (send config:<prefix>|<service uuid>|<char uuid>)");
            return;
        }
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) { cb.done(-1, "bluetooth off"); return; }
            final BluetoothLeScanner scanner = ad.getBluetoothLeScanner();
            if (scanner == null) { cb.done(-1, "no le scanner"); return; }

            final List<BluetoothDevice> found = new ArrayList<>();
            final boolean[] settled = {false};
            final ScanCallback[] holder = new ScanCallback[1];

            ScanCallback sc = new ScanCallback() {
                public void onScanResult(int type, ScanResult r) {
                    try {
                        BluetoothDevice d = r.getDevice();
                        String n = d.getName();
                        if (n == null || !n.startsWith(namePrefix)) return;
                        for (BluetoothDevice x : found) {
                            if (x.getAddress().equals(d.getAddress())) return;   // already collected
                        }
                        if (found.size() >= MAX_DEVICES) return;
                        found.add(d);
                        names.put(d.getAddress(), n);
                    } catch (SecurityException ignored) {
                    }
                }
                public void onScanFailed(int err) {
                    if (settled[0]) return;
                    settled[0] = true;
                    cb.done(-1, "scan failed " + err);
                }
            };
            holder[0] = sc;
            scanner.startScan(sc);

            main.postDelayed(new Runnable() {
                public void run() {
                    if (settled[0]) return;
                    settled[0] = true;
                    try { scanner.stopScan(holder[0]); } catch (Exception ignored) {}
                    if (found.isEmpty()) {
                        cb.done(-1, "device not found (" + namePrefix + "*) in " + (SCAN_MS / 1000) + "s");
                        return;
                    }
                    openAll(ctx, found, cb);
                }
            }, SCAN_MS);
        } catch (SecurityException se) {
            cb.done(-1, "no BLUETOOTH_SCAN permission");
        } catch (Exception e) {
            cb.done(-1, "scan error: " + e.getMessage());
        }
    }

    /** connectGatt on each device, then report once every one has a result or the timeout hits. */
    private static void openAll(Context ctx, final List<BluetoothDevice> devs, final Result cb) {
        final int total = devs.size();
        final int[] doneCount = {0};
        final boolean[] replied = {false};
        final StringBuilder report = new StringBuilder();

        final Runnable finish = new Runnable() {
            public void run() {
                if (replied[0]) return;
                replied[0] = true;
                if (chars.isEmpty()) cb.done(-1, "no device ready. " + report);
                else cb.done(0, "connected " + chars.size() + "/" + total + " device(s), characteristic ready: " + report);
            }
        };

        for (BluetoothDevice dev : devs) {
            final String mac = dev.getAddress();
            final String nm = names.containsKey(mac) ? names.get(mac) : mac;
            final boolean[] settled = {false};
            try {
                BluetoothGatt g = dev.connectGatt(ctx, false, new BluetoothGattCallback() {
                    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                        try {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                g.discoverServices();
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                chars.remove(mac);
                                gatts.remove(mac);
                                if (!settled[0]) {
                                    settled[0] = true;
                                    report.append("[").append(nm).append(": dropped]");
                                    if (++doneCount[0] >= total) main.post(finish);
                                }
                            }
                        } catch (SecurityException ignored) {
                        }
                    }

                    public void onServicesDiscovered(BluetoothGatt g, int status) {
                        if (settled[0]) return;
                        settled[0] = true;
                        BluetoothGattService s = g.getService(svcUuid);
                        if (s == null) {
                            StringBuilder sb = new StringBuilder("[" + nm + ": service not found, has");
                            List<BluetoothGattService> all = g.getServices();
                            if (all != null) for (BluetoothGattService x : all) sb.append(" ").append(x.getUuid());
                            report.append(sb).append("]");
                        } else {
                            BluetoothGattCharacteristic c = s.getCharacteristic(charUuid);
                            if (c == null) {
                                report.append("[").append(nm).append(": service ok, characteristic not found]");
                            } else {
                                gatts.put(mac, g);
                                chars.put(mac, c);
                                report.append("[").append(nm).append(": ok]");
                            }
                        }
                        if (++doneCount[0] >= total) main.post(finish);
                    }
                });
                if (g != null) gatts.put(mac, g);
            } catch (SecurityException se) {
                cb.done(-1, "no BLUETOOTH_CONNECT permission");
                return;
            } catch (Exception e) {
                report.append("[").append(nm).append(": ").append(e.getMessage()).append("]");
                if (++doneCount[0] >= total) main.post(finish);
            }
        }
        // Fallback: do not let the whole request hang if one device never calls back.
        main.postDelayed(finish, 12000);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String h = hex.replaceAll("[^0-9a-fA-F]", "");
        if (h.length() % 2 != 0) return null;
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
