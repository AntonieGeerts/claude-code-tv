package tv.attic.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/** Layout is designed in 1080p pixels and scaled to the real screen: px(100) = 100 px at 1920x1080. */
final class U {
    static float scale = 1f;

    static final int TEXT = 0xFFF1F3F4;
    static final int MUTED = 0x9EF1F3F4;
    static final int FAINT = 0x61F1F3F4;
    static final int CARD = 0x99121620;
    static final int CARD_LINE = 0x17FFFFFF;
    static final int WORKING = 0xFF7EE2A8;
    static final int WAITING = 0xFFFFB454;
    static final int READY = 0xFF8AB4F8;
    static final int IDLE = 0xFF9AA0A6;
    static final int AWAY = 0xFF5F6368;

    static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private U() {}

    static void init(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels);
        scale = w / 1920f;
    }

    static int px(float designPx) {
        return Math.round(designPx * scale);
    }

    static float pxf(float designPx) {
        return designPx * scale;
    }

    static TextView text(Context c, float sizeDesignPx, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(sizeDesignPx));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static int colorFor(String state) {
        if (state == null) return AWAY;
        switch (state) {
            case "working": return WORKING;
            case "waiting": return WAITING;
            case "ready": return READY;
            case "idle": return IDLE;
            default: return AWAY;
        }
    }
}
