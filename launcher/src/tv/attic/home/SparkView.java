package tv.attic.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Calendar;

/** 24 bars: active minutes per hour today; the current hour is highlighted. */
final class SparkView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] hours = new int[24];

    SparkView(Context c) {
        super(c);
        tick.setTypeface(U.SANS);
        tick.setTextSize(U.pxf(15));
        tick.setColor(U.FAINT);
    }

    void setHours(int[] h) {
        if (h != null && h.length == 24) { hours = h; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas c) {
        float labelH = U.pxf(22);
        float h = getHeight() - labelH, w = getWidth();
        float gap = U.pxf(3), bw = (w - gap * 23) / 24f, r = Math.min(bw / 2, U.pxf(3));
        int max = 15;
        for (int v : hours) max = Math.max(max, v);
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        for (int i = 0; i < 24; i++) {
            float x = i * (bw + gap);
            float bh = hours[i] <= 0 ? U.pxf(3) : Math.max(U.pxf(5), h * hours[i] / (float) max);
            bar.setColor(i == now ? U.WORKING : hours[i] > 0 ? 0x80FFFFFF : 0x1AFFFFFF);
            c.drawRoundRect(x, h - bh, x + bw, h, r, r, bar);
        }
        String[] labels = {"00", "06", "12", "18"};
        for (int k = 0; k < 4; k++) {
            float x = k * 6 * (bw + gap);
            c.drawText(labels[k], x, getHeight() - U.pxf(2), tick);
        }
    }
}
