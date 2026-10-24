// Veglia · floating caption overlay.
//
// Captions used to go out as high-priority notifications, but the heads-up
// banner disappears quickly and long lines are hard to read.
//
// This overlay is drawn over any app, stays until it has been read, and is
// dismissed with a tap.
// It needs the "display over other apps" permission, which the phone's owner
// must grant by hand in system settings. Without it addView() throws; the
// overlay then falls back to the notification path quietly and never crashes
// the service.
package dev.veglia.companion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class CaptionOverlay {

    private static WindowManager wm;
    private static TextView view;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Runnable pendingHide;

    /** Whether the "display over other apps" permission is granted. If not, do not try. */
    public static boolean allowed(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shows one caption line and hides it after holdMs milliseconds (longer for long lines).
     * Returns false if it could not be shown; the caller should fall back to a notification.
     */
    public static boolean show(final Context ctx, final String text, final long holdMs) {
        if (text == null || text.length() == 0) return false;
        if (!allowed(ctx)) return false;
        final boolean[] ok = {true};
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (view == null) {
                        view = new TextView(ctx);
                        // Semi-transparent background, small text, loose line spacing and a faint
                        // outline so the dark block stays readable over video.
                        view.setTextColor(Color.parseColor("#F2FFFFFF"));
                        view.setTextSize(13f);
                        view.setPadding(30, 20, 30, 20);
                        view.setLineSpacing(6f, 1.0f);
                        view.setMaxWidth(880);
                        // A light shadow keeps the text from washing out on bright backgrounds.
                        view.setShadowLayer(6f, 0f, 1f, Color.parseColor("#99000000"));
                        GradientDrawable bg = new GradientDrawable();
                        bg.setColor(Color.parseColor("#9E0E1216"));
                        bg.setCornerRadius(26f);
                        bg.setStroke(1, Color.parseColor("#33FFFFFF"));
                        view.setBackground(bg);
                        // A tap hides it.
                        view.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { hide(); }
                        });

                        int type = (android.os.Build.VERSION.SDK_INT >= 26)
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE;
                        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                type,
                                // NOT_FOCUSABLE: do not steal input focus from the app underneath.
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                        lp.y = 160;
                        wm.addView(view, lp);
                    }
                    view.setText(text);
                    view.setVisibility(View.VISIBLE);

                    if (pendingHide != null) ui.removeCallbacks(pendingHide);
                    pendingHide = new Runnable() {
                        public void run() { hide(); }
                    };
                    ui.postDelayed(pendingHide, holdMs);
                } catch (Exception e) {
                    // Permission revoked or the system refused: fall back to notifications quietly.
                    ok[0] = false;
                    view = null;
                }
            }
        });
        return ok[0];
    }

    public static void hide() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (view != null) view.setVisibility(View.GONE);
                } catch (Exception ignored) {
                }
            }
        });
    }
}
