package tv.attic.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One app tile: rounded artwork, label underneath, scale + white ring when focused. */
final class TileView extends View {
    static final float TILE_W = 160, TILE_H = 144, LABEL_GAP = 30, LABEL_SIZE = 25, FOCUS_SCALE = 1.12f;

    final AppEntry app;
    private final RectF art = new RectF();
    private final Path clip = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int[] bgColor;
    private boolean moving;

    TileView(Context c, AppEntry app) {
        super(c);
        this.app = app;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setClipToOutline(false);
        label.setTypeface(U.SANS);
        label.setTextSize(U.pxf(LABEL_SIZE));
        label.setTextAlign(Paint.Align.CENTER);
        bgColor = app.special ? new int[]{0x2EFFFFFF, 0x2EFFFFFF} : backgroundFor(app);
        setContentDescription(app.label);
        setOnFocusChangeListener((v, has) -> animateFocus(has));
    }

    static int widthPx() { return U.px(TILE_W); }
    static int heightPx() { return U.px(TILE_H + LABEL_GAP + LABEL_SIZE + 12); }

    void setMoving(boolean m) { moving = m; invalidate(); }

    private void animateFocus(boolean has) {
        setPivotX(getWidth() / 2f);
        setPivotY(U.pxf(TILE_H) / 2f);
        animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f).translationZ(has ? U.pxf(12) : 0)
                .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(widthPx(), heightPx());
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = U.pxf(16);
        art.set(0, 0, getWidth(), U.pxf(TILE_H));
        boolean focused = isFocused();

        if (focused) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(U.pxf(4));
            paint.setColor(moving ? U.WAITING : Color.WHITE);
            paint.setShadowLayer(U.pxf(18), 0, U.pxf(4), 0x80000000);
            float o = U.pxf(5);
            c.drawRoundRect(art.left - o, art.top - o, art.right + o, art.bottom + o, r + o, r + o, paint);
            paint.clearShadowLayer();
        }

        c.save();
        clip.reset();
        clip.addRoundRect(art, r, r, Path.Direction.CW);
        c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bgColor[0]);
        if (bgColor[0] == bgColor[1]) {
            c.drawRect(art, paint);
        } else {
            c.drawRect(art.left, art.top, art.right, art.centerY() + 1, paint);
            paint.setColor(bgColor[1]);
            c.drawRect(art.left, art.centerY(), art.right, art.bottom, paint);
        }
        drawArtwork(c);
        c.restore();

        label.setColor(focused ? Color.WHITE : 0xFFDADCE0);
        label.setTextSize(U.pxf(focused ? LABEL_SIZE + 1 : LABEL_SIZE));
        CharSequence t = TextUtils.ellipsize(app.label, label, getWidth() + U.pxf(70), TextUtils.TruncateAt.END);
        float y = art.bottom + U.pxf(LABEL_GAP) + U.pxf(LABEL_SIZE) * 0.8f + (focused ? U.pxf(6) : 0);
        c.drawText(t, 0, t.length(), getWidth() / 2f, y, label);
    }

    private void drawArtwork(Canvas c) {
        Rect b = new Rect();
        if (app.special) {
            int s = U.px(56);
            b.set((int) art.centerX() - s / 2, (int) art.centerY() - s / 2, (int) art.centerX() + s / 2, (int) art.centerY() + s / 2);
            app.icon.setBounds(b);
            app.icon.draw(c);
        } else if (app.banner != null) {
            fitInto(app.banner, c, 16f / 9f);
        } else if (app.icon instanceof AdaptiveIconDrawable) {
            coverInto(app.icon, c, 1f);
        } else if (app.icon != null) {
            int s = Math.round(art.height() * 0.62f);
            b.set((int) art.centerX() - s / 2, (int) art.centerY() - s / 2, (int) art.centerX() + s / 2, (int) art.centerY() + s / 2);
            app.icon.setBounds(b);
            app.icon.draw(c);
        }
    }

    /** Scale a drawable to cover the artwork rect (centre crop). */
    private void coverInto(Drawable d, Canvas c, float fallbackAspect) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? iw / (float) ih : fallbackAspect;
        float w = art.width(), h = art.height();
        if (w / h > aspect) h = w / aspect; else w = h * aspect;
        int l = Math.round(art.centerX() - w / 2), t = Math.round(art.centerY() - h / 2);
        d.setBounds(l, t, l + Math.round(w), t + Math.round(h));
        d.draw(c);
    }

    /** Scale a drawable to fit entirely inside the artwork rect (letterboxed on the tile colour). */
    private void fitInto(Drawable d, Canvas c, float fallbackAspect) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? iw / (float) ih : fallbackAspect;
        float w = art.width(), h = art.height();
        if (w / h > aspect) w = h * aspect; else h = w / aspect;
        int l = Math.round(art.centerX() - w / 2), t = Math.round(art.centerY() - h / 2);
        d.setBounds(l, t, l + Math.round(w), t + Math.round(h));
        d.draw(c);
    }

    /**
     * Edge colours of the artwork ({top, bottom}): fill the letterbox around banners and the
     * background behind legacy icons so the tile looks seamless. Banners often differ top vs bottom.
     */
    private static int[] backgroundFor(AppEntry app) {
        int[] none = {0xFF20232B, 0xFF20232B};
        Drawable src = app.banner != null ? app.banner : app.icon;
        if (src == null || (app.banner == null && app.icon instanceof AdaptiveIconDrawable)) return none;
        try {
            int bw = app.banner != null ? 48 : 24, bh = app.banner != null ? 27 : 24;
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            src.setBounds(0, 0, bw, bh);
            src.draw(c);
            int top = average(bm, 0, bh / 2, true), bottom = average(bm, bh / 2, bh, false);
            bm.recycle();
            if (top == 0 && bottom == 0) return none;
            if (top == 0) top = bottom;
            if (bottom == 0) bottom = top;
            return app.banner != null ? new int[]{top, bottom} : new int[]{top, top};
        } catch (RuntimeException e) {
            return none;
        }
    }

    /** Average opaque colour of one horizontal edge row plus the side columns in [y0, y1); 0 if none. */
    private static int average(Bitmap bm, int y0, int y1, boolean topEdge) {
        int bw = bm.getWidth(), bh = bm.getHeight();
        long r = 0, g = 0, b = 0, n = 0;
        int edgeY = topEdge ? 0 : bh - 1;
        for (int x = 0; x < bw; x++) {
            int p = bm.getPixel(x, edgeY);
            if (Color.alpha(p) < 200) continue;
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
        }
        for (int y = y0; y < y1; y++) {
            for (int p : new int[]{bm.getPixel(0, y), bm.getPixel(bw - 1, y)}) {
                if (Color.alpha(p) < 200) continue;
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
            }
        }
        return n < 4 ? 0 : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }
}
