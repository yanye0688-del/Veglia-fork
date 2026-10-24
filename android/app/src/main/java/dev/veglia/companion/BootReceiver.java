// Veglia · boot receiver.
//
// After a reboot the polling service is gone until the phone's owner opens the
// app, and nobody would notice it was down. This receiver restarts it.
// MY_PACKAGE_REPLACED is handled too, so a freshly installed build starts itself
// without another tap.
package dev.veglia.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // Do not start if no server was ever configured; CompanionService also stops itself.
            SharedPreferences prefs =
                    context.getSharedPreferences("veglia_companion", Context.MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", null);
            String token = prefs.getString("token", null);
            if (serverUrl == null || token == null || serverUrl.isEmpty() || token.isEmpty()) {
                return;
            }

            Intent svc = new Intent(context, CompanionService.class);
            svc.putExtra("server_url", serverUrl);
            svc.putExtra("token", token);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable ignored) {
            // Nothing thrown during the boot broadcast may make the system treat this receiver as faulty.
        }
    }
}
