package tv.attic.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Circular usage gauge: track + coloured arc (green → amber → red) with the % in the middle. */
final class RingView extends View {
    static final int RED = 0xFFFF7A7A;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float value = -1, shown;      // -1 = unknown
    private ValueAnimator anim;

    RingView(Context c) {
        super(c);
        float stroke = U.pxf(10);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x1FFFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        big.setTypeface(U.SANS_MEDIUM);
        big.setTextSize(U.pxf(31));
        big.setColor(U.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setFontFeatureSettings("tnum");
        small.setTypeface(U.SANS);
        small.setTextSize(U.pxf(18));
        small.setColor(U.MUTED);
    }

    static int colorFor(float pct) {
        return pct >= 85 ? RED : pct >= 60 ? U.WAITING : U.WORKING;
    }

    void setValue(float pct) {
        if (pct == value) return;
        float from = value < 0 ? 0 : shown;
        value = pct;
        if (anim != null) anim.cancel();
        if (pct < 0) { shown = 0; invalidate(); return; }
        anim = ValueAnimator.ofFloat(from, pct);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = track.getStrokeWidth() / 2 + U.pxf(2);
        float size = Math.min(getWidth(), getHeight());
        oval.set(pad, pad, size - pad, size - pad);
        c.drawArc(oval, 0, 360, false, track);
        if (value >= 0) {
            arc.setColor(colorFor(shown));
            c.drawArc(oval, -90, Math.max(1.5f, 360f * Math.min(100, shown) / 100f), false, arc);
        }
        String num = value < 0 ? "–" : String.valueOf(Math.round(shown));
        float cx = size / 2f, cy = size / 2f - (big.descent() + big.ascent()) / 2f;
        if (value < 0) {
            c.drawText(num, cx, cy, big);
        } else {
            float nw = big.measureText(num), pw = small.measureText("%");
            float left = cx - (nw + pw) / 2f;
            big.setTextAlign(Paint.Align.LEFT);
            c.drawText(num, left, cy, big);
            c.drawText("%", left + nw + U.pxf(2), cy, small);
            big.setTextAlign(Paint.Align.CENTER);
        }
    }
}
