package tv.attic.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/**
 * Painted twilight-lake landscape: gradient sky, warm glow on the horizon, layered hills,
 * and a darker mirrored lake. Rendered once off the UI thread into a bitmap.
 */
final class WallpaperView extends View {
    private Bitmap bitmap;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);

    WallpaperView(Context c) {
        super(c);
        setBackgroundColor(0xFF0B0F16);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0 || (bitmap != null && bitmap.getWidth() == w && bitmap.getHeight() == h)) return;
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Bitmap b = render(w, h);
            ui.post(() -> { bitmap = b; invalidate(); });
        }, "attic-wallpaper").start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bitmap != null) c.drawBitmap(bitmap, 0, 0, blit);
    }

    static Bitmap render(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float horizon = h * 0.365f;
        float sunX = w * 0.72f;

        // Sky
        p.setShader(new LinearGradient(0, 0, 0, horizon, new int[]{0xFF262C3A, 0xFF383848, 0xFF54485A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon + 2, p);
        // Warm glow behind the hills
        p.setShader(new RadialGradient(sunX, horizon, w * 0.33f, new int[]{0x88D88E62, 0x33A0645A, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon * 1.6f, p);

        // Lake
        p.setShader(new LinearGradient(0, horizon, 0, h, new int[]{0xFF2A2834, 0xFF161A25, 0xFF0B0F16},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon, w, h, p);

        // Hills: far → near, each mirrored into the lake
        Random r = new Random(7);
        int[] colors = {0xFF4A4452, 0xFF3A3644, 0xFF2A2A36, 0xFF1A1D27};
        float[] heights = {0.13f, 0.10f, 0.065f, 0.025f};
        for (int i = 0; i < colors.length; i++) {
            Path ridge = ridge(w, horizon, h * heights[i], r, i);
            p.setShader(null);
            p.setColor(colors[i]);
            c.drawPath(ridge, p);
            // reflection
            c.save();
            c.scale(1, -1, 0, horizon);
            p.setColor(colors[i]);
            p.setAlpha(i == colors.length - 1 ? 200 : 70 + i * 25);
            c.drawPath(ridge, p);
            c.restore();
            p.setAlpha(255);
        }

        // Fade the reflection into the deep water
        p.setShader(new LinearGradient(0, horizon, 0, h, new int[]{0x00000000, 0xCC0D111A, 0xFF0B0F16},
                new float[]{0f, 0.3f, 0.75f}, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon, w, h, p);

        // Glow on the water
        p.setShader(new RadialGradient(sunX, horizon + h * 0.012f, w * 0.12f,
                new int[]{0x66E09A6A, 0x22B0705A, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1.9f, 0.32f, sunX, horizon);
        c.drawCircle(sunX, horizon, w * 0.12f, p);
        c.restore();

        // Soft ripples
        p.setShader(null);
        p.setStrokeWidth(Math.max(1, h / 1080f));
        for (int i = 0; i < 14; i++) {
            float y = horizon + (h - horizon) * (0.02f + (float) Math.pow(r.nextFloat(), 2.2) * 0.16f);
            float len = w * (0.03f + r.nextFloat() * 0.08f);
            float x = sunX - len / 2 + (r.nextFloat() - 0.5f) * w * 0.16f;
            p.setColor(0x10FFE2C8);
            c.drawLine(x, y, x + len, y, p);
        }

        // Gentle vignette so text stays readable
        p.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x66070A10, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.55f, w, h, p);
        return b;
    }

    /** A soft ridge line made of a few summed sine waves, closed down to the horizon. */
    private static Path ridge(int w, float horizon, float amp, Random r, int layer) {
        int n = 3 + layer;
        float[] freq = new float[n], phase = new float[n], weight = new float[n];
        for (int k = 0; k < n; k++) {
            freq[k] = (1.2f + r.nextFloat() * 2.5f) * (k + 1) * 0.7f;
            phase[k] = r.nextFloat() * 6.283f;
            weight[k] = 1f / (k + 1.3f);
        }
        Path path = new Path();
        path.moveTo(0, horizon + 1);
        int steps = 96;
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            float v = 0, norm = 0;
            for (int k = 0; k < n; k++) {
                v += weight[k] * Math.sin(t * freq[k] * 3.1416f + phase[k]);
                norm += weight[k];
            }
            v = 0.55f + 0.45f * (v / norm);
            path.lineTo(t * w, horizon - amp * v);
        }
        path.lineTo(w, horizon + 1);
        path.close();
        return path;
    }
}
