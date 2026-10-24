// Veglia · Shizuku bridge — lets a consenting partner's AI act on the phone
// (tap/type/launch), not just look at it. Copyright (c) 2026 Evelyn & River — MIT License.
package dev.veglia.companion;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Thin wrapper around the Shizuku AIDL service: permission + "run a shell command". */
public class ShizukuBridge {
    private static final int REQUEST_CODE = 511513;

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPermissionGranted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fires the system/Shizuku consent prompt. Result arrives via the listener below. */
    public static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && !isPermissionGranted()) {
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable t) {
        }
    }

    public static void addPermissionResultListener(Shizuku.OnRequestPermissionResultListener l) {
        Shizuku.addRequestPermissionResultListener(l);
    }

    /** Runs `sh -c cmd` as the Shizuku-granted identity (shell/adb, or root). Blocks for exit. */
    public static Result runShellCommand(String cmd) throws Exception {
        IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        moe.shizuku.server.IRemoteProcess remote =
                service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        String output;
        try (InputStream stdout = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                remote.getInputStream())) {
            output = readAll(stdout);
        }
        int exitCode = remote.waitFor();
        return new Result(exitCode, output);
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    public static class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
