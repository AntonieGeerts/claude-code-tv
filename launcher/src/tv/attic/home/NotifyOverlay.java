package tv.attic.home;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/** Corner pop-up drawn over whatever app is on screen (needs "display over other apps"). */
final class NotifyOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<StatusClient.Note> queue = new ArrayDeque<>();
    private View showing;

    NotifyOverlay(Context c) {
        ctx = c;
        wm = c.getSystemService(WindowManager.class);
        U.init(c);
    }

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    void show(StatusClient.Note n) {
        queue.add(n);
        if (showing == null) next();
    }

    private void next() {
        StatusClient.Note n = queue.poll();
        if (n == null) return;
        View v = build(n);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                U.px(640), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = U.px(56);
        lp.y = U.px(132); // below the launcher's top bar (clock, bell)
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            queue.clear();
            return;
        }
        showing = v;
        v.setAlpha(0f);
        v.setTranslationX(U.pxf(80));
        v.animate().alpha(1f).translationX(0).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        ui.postDelayed(() -> dismiss(v), holdMs(n.kind));
    }

    private void dismiss(View v) {
        v.animate().alpha(0f).translationX(U.pxf(80)).setDuration(260).withEndAction(() -> {
            try { wm.removeView(v); } catch (RuntimeException ignored) { }
            showing = null;
            ui.postDelayed(this::next, 400);
        }).start();
    }

    private static long holdMs(String kind) {
        switch (kind) {
            case "permission": case "question": case "reply": return 20_000;
            case "ship": return 15_000;
            default: return 9_000;
        }
    }

    static int colorFor(String kind) {
        switch (kind) {
            case "permission": case "question": case "reply": return U.WAITING;
            case "ship": return U.READY;
            case "shipped": case "done": return U.WORKING;
            default: return U.TEXT;
        }
    }

    static String kicker(String kind) {
        switch (kind) {
            case "permission": return "NEEDS YOUR OK";
            case "question": return "QUESTION FOR YOU";
            case "reply": return "WAITING FOR YOU";
            case "ship": return "READY TO SHIP";
            case "shipped": return "SHIPPED";
            case "done": return "FINISHED";
            default: return "CLAUDE CODE";
        }
    }

    private static String glyph(String kind) {
        switch (kind) {
            case "permission": case "reply": return "!";
            case "question": return "?";
            case "ship": return "↑";
            case "shipped": case "done": return "✓";
            default: return "•";
        }
    }

    private View build(StatusClient.Note n) {
        int accent = colorFor(n.kind);
        LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(U.px(26), U.px(24), U.px(30), U.px(24));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF21A1F2B, 0xF2121620});
        bg.setCornerRadius(U.pxf(22));
        bg.setStroke(Math.max(1, U.px(2)), (accent & 0x00FFFFFF) | 0x55000000);
        box.setBackground(bg);
        box.setElevation(U.pxf(16));

        TextView icon = U.text(ctx, 30, 0xFF10131A, U.SANS_MEDIUM);
        icon.setGravity(Gravity.CENTER);
        icon.setText(glyph(n.kind));
        GradientDrawable ib = new GradientDrawable();
        ib.setShape(GradientDrawable.OVAL);
        ib.setColor(accent);
        icon.setBackground(ib);
        box.addView(icon, new LinearLayout.LayoutParams(U.px(58), U.px(58)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = U.px(22);
        box.addView(col, cl);

        TextView k = U.text(ctx, 16, accent, U.SANS_MEDIUM);
        k.setLetterSpacing(0.14f);
        k.setText(kicker(n.kind));
        col.addView(k);
        TextView t = U.text(ctx, 29, U.TEXT, U.SANS_MEDIUM);
        t.setText(n.title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.topMargin = U.px(8);
        col.addView(t, tl);
        if (n.body != null && !n.body.isEmpty()) {
            TextView b = U.text(ctx, 22, U.MUTED, U.SANS);
            b.setText(n.body);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bl.topMargin = U.px(6);
            col.addView(b, bl);
        }
        return box;
    }
}
