package tv.attic.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** All cswap accounts side by side: "#n" with a 5-hour bar over a weekly bar; the shown account is bright. */
final class PoolView extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<StatusClient.Pool> pool = new ArrayList<>();
    private int selected = -1;

    PoolView(Context c) {
        super(c);
        text.setTypeface(U.SANS_MEDIUM);
        text.setTextSize(U.pxf(15));
        text.setFontFeatureSettings("tnum");
    }

    void set(List<StatusClient.Pool> p, int selectedNumber) {
        pool = p;
        selected = selectedNumber;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = pool.size();
        if (n == 0) return;
        float gap = U.pxf(10), colW = (getWidth() - gap * (n - 1)) / n;
        float barH = U.pxf(5), r = barH / 2, labelH = U.pxf(18);
        for (int i = 0; i < n; i++) {
            StatusClient.Pool p = pool.get(i);
            boolean sel = p.number == selected;
            boolean inUse = !p.env.isEmpty();
            float x = i * (colW + gap);
            int alpha = sel ? 255 : inUse ? 200 : 120;
            text.setColor(sel ? U.TEXT : U.MUTED);
            text.setAlpha(alpha);
            String label = "#" + (p.number > 0 ? p.number : i + 1) + (inUse ? " •" : "");
            c.drawText(label, x, labelH - U.pxf(4), text);
            drawBar(c, x, labelH + U.pxf(2), colW, barH, r, p.fh, alpha);
            drawBar(c, x, labelH + U.pxf(2) + barH + U.pxf(4), colW, barH, r, p.sd, alpha);
        }
    }

    private void drawBar(Canvas c, float x, float y, float w, float h, float r, double pct, int alpha) {
        bar.setColor(0x26FFFFFF);
        c.drawRoundRect(x, y, x + w, y + h, r, r, bar);
        if (pct < 0) return;
        bar.setColor(RingView.colorFor((float) pct));
        bar.setAlpha(alpha);
        float fw = Math.max(h, (float) (w * Math.min(100, pct) / 100.0));
        if (pct > 0) c.drawRoundRect(x, y, x + fw, y + h, r, r, bar);
    }
}
