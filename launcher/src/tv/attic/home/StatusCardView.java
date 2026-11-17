package tv.attic.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home-screen card with live Claude Code activity:
 *   [ session: state · project · activity · details ] | [ plan usage rings ] | [ today + hourly bars ]
 */
final class StatusCardView extends LinearLayout {
    private static final int RED = RingView.RED;

    // session column
    private final View dot;
    private final TextView label, project, activity, meta;
    private final LinearLayout chips, others, sessionCol;
    private final TextView pager;
    private String featuredId;          // session currently shown (rotates every ROTATE_MS)
    private static final long ROTATE_MS = 10_000;
    // usage column
    private final LinearLayout usageCol;
    private final TextView usageAccount, fhSub, sdSub;
    private final PoolView poolView;
    private final RingView fhRing, sdRing;
    // today column
    private final TextView todayActive, todayCounts;
    private final SparkView spark;

    private ObjectAnimator pulse;
    private StatusClient.Snapshot last;
    private long clockSkew; // serverTime - local time
    private final Runnable rotator = new Runnable() {
        @Override public void run() {
            rotate();
            postDelayed(this, ROTATE_MS);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (last != null) renderActivity(last);
            postDelayed(this, 5_000);
        }
    };

    StatusCardView(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        setPadding(U.px(40), U.px(24), U.px(40), U.px(24));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xB0161B26, 0x9E10141C});
        bg.setCornerRadius(U.pxf(26));
        bg.setStroke(Math.max(1, U.px(1.5f)), U.CARD_LINE);
        setBackground(bg);

        // ---------------- session column
        LinearLayout left = column(c);
        sessionCol = left;
        addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        dot.setBackground(d);
        top.addView(dot, new LayoutParams(U.px(14), U.px(14)));
        label = U.text(c, 19, U.AWAY, U.SANS_MEDIUM);
        label.setLetterSpacing(0.14f);
        top.addView(label, marginLeft(12));
        chips = new LinearLayout(c);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(chips, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ((LayoutParams) chips.getLayoutParams()).leftMargin = U.px(18);
        pager = U.text(c, 20, U.FAINT, U.SANS);
        pager.setLetterSpacing(0.2f);
        top.addView(pager, marginLeft(12));
        left.addView(top);

        project = U.text(c, 46, U.TEXT, U.SANS_MEDIUM);
        left.addView(project, marginTop(14));
        activity = U.text(c, 27, U.MUTED, U.SANS);
        left.addView(activity, marginTop(10));
        meta = U.text(c, 21, U.FAINT, U.SANS);
        left.addView(meta, marginTop(14));
        others = new LinearLayout(c);
        left.addView(others, marginTop(14));

        addView(divider(c), dividerParams());

        // ---------------- usage column
        usageCol = column(c);
        LinearLayout uh = new LinearLayout(c);
        uh.setGravity(Gravity.CENTER_VERTICAL);
        uh.addView(caption(c, "PLAN USAGE"));
        usageAccount = U.text(c, 18, U.FAINT, U.SANS);
        uh.addView(usageAccount, marginLeft(14));
        usageCol.addView(uh);
        LinearLayout rings = new LinearLayout(c);
        fhRing = new RingView(c);
        sdRing = new RingView(c);
        fhSub = U.text(c, 18, U.FAINT, U.SANS);
        sdSub = U.text(c, 18, U.FAINT, U.SANS);
        rings.addView(ringBlock(c, fhRing, "5-HOUR", fhSub));
        LinearLayout second = ringBlock(c, sdRing, "WEEK", sdSub);
        rings.addView(second, marginLeft(34));
        usageCol.addView(rings, marginTop(12));
        poolView = new PoolView(c);
        LayoutParams pl = new LayoutParams(U.px(250), U.px(36));
        pl.topMargin = U.px(12);
        usageCol.addView(poolView, pl);
        addView(usageCol, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(divider(c), dividerParams());

        // ---------------- today column
        LinearLayout right = column(c);
        right.addView(caption(c, "TODAY"));
        todayActive = U.text(c, 44, U.TEXT, U.SANS_MEDIUM);
        todayActive.setFontFeatureSettings("tnum");
        right.addView(todayActive, marginTop(12));
        spark = new SparkView(c);
        LayoutParams sp = new LayoutParams(U.px(250), U.px(76));
        sp.topMargin = U.px(14);
        right.addView(spark, sp);
        todayCounts = U.text(c, 19, U.FAINT, U.SANS);
        right.addView(todayCounts, marginTop(10));
        addView(right, new LayoutParams(U.px(250), LayoutParams.WRAP_CONTENT));

        renderEmpty("Waiting for Claude Code activity");
    }

    // ---------------------------------------------------------------- building blocks

    private LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(VERTICAL);
        return l;
    }

    private TextView caption(Context c, String s) {
        TextView t = U.text(c, 17, U.FAINT, U.SANS_MEDIUM);
        t.setLetterSpacing(0.12f);
        t.setText(s);
        return t;
    }

    private LinearLayout ringBlock(Context c, RingView ring, String title, TextView sub) {
        LinearLayout b = column(c);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.addView(ring, new LayoutParams(U.px(100), U.px(100)));
        TextView t = caption(c, title);
        b.addView(t, marginTop(8));
        b.addView(sub, marginTop(4));
        return b;
    }

    private View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(U.CARD_LINE);
        return v;
    }

    private LayoutParams dividerParams() {
        LayoutParams l = new LayoutParams(Math.max(1, U.px(1.5f)), U.px(170));
        l.leftMargin = U.px(36);
        l.rightMargin = U.px(36);
        return l;
    }

    private LayoutParams marginTop(int px) {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = U.px(px);
        return lp;
    }

    private LayoutParams marginLeft(int px) {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = U.px(px);
        return lp;
    }

    private TextView chip(String text, int dotColor) {
        TextView t = U.text(getContext(), 18, U.MUTED, U.SANS);
        t.setText(text);
        t.setMaxWidth(U.px(330));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(U.pxf(40));
        t.setBackground(bg);
        if (dotColor != 0) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(dotColor);
            d.setSize(U.px(9), U.px(9));
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
            t.setCompoundDrawablePadding(U.px(8));
        }
        t.setPaddingRelative(U.px(14), U.px(6), U.px(14), U.px(6));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = U.px(10);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------------------------------------------------------- rendering

    void showError(String reason) {
        if (last == null) renderEmpty("Status unavailable · " + reason);
        else meta.setText("Connection lost · showing last update");
    }

    void render(StatusClient.Snapshot s) {
        last = s;
        clockSkew = s.serverTime - System.currentTimeMillis();
        List<StatusClient.Session> rot = rotation(s);
        if (featured(s, rot) == null) featuredId = rot.isEmpty() ? null : rot.get(0).id;
        renderSession(s);
        todayActive.setText(dur(s.activeMinutes * 60_000L));
        todayCounts.setText(s.prompts + " prompts  ·  " + s.edits + " edits");
        spark.setHours(s.hours);
    }

    /**
     * Sessions the card cycles through: only the ones that need you (waiting / pending question)
     * if there are any, otherwise every live session; falls back to all (away) sessions.
     */
    private static List<StatusClient.Session> rotation(StatusClient.Snapshot s) {
        List<StatusClient.Session> urgent = new ArrayList<>(), live = new ArrayList<>();
        for (StatusClient.Session x : s.sessions) {
            if ("waiting".equals(x.state) || x.ask != null) urgent.add(x);
            if (!"away".equals(x.state)) live.add(x);
        }
        if (!urgent.isEmpty()) return urgent;
        if (!live.isEmpty()) return live;
        return s.sessions.isEmpty() ? s.sessions : s.sessions.subList(0, 1);
    }

    private StatusClient.Session featured(StatusClient.Snapshot s, List<StatusClient.Session> rot) {
        for (StatusClient.Session x : rot) if (x.id != null && x.id.equals(featuredId)) return x;
        return null;
    }

    /** Advance to the next session with a short cross-fade (only when there is more than one). */
    private void rotate() {
        if (last == null) return;
        List<StatusClient.Session> rot = rotation(last);
        if (rot.size() < 2) return;
        int i = 0;
        for (int k = 0; k < rot.size(); k++) if (rot.get(k).id.equals(featuredId)) i = k;
        featuredId = rot.get((i + 1) % rot.size()).id;
        sessionCol.animate().alpha(0f).translationX(-U.pxf(24)).setDuration(220).withEndAction(() -> {
            renderSession(last);
            sessionCol.setTranslationX(U.pxf(24));
            sessionCol.animate().alpha(1f).translationX(0).setDuration(280).start();
        }).start();
    }

    private void renderSession(StatusClient.Snapshot s) {
        List<StatusClient.Session> rot = rotation(s);
        StatusClient.Session top = featured(s, rot);
        if (top == null && !rot.isEmpty()) top = rot.get(0);
        setState(top != null ? (top.ask != null ? "waiting" : top.state) : s.state);

        chips.removeAllViews();
        if (top != null) {
            if (top.title != null && !top.title.isEmpty()) chips.addView(chip(top.project, 0));
            if (top.branch != null) chips.addView(chip("⎇ " + top.branch, 0));
            if (top.model != null) chips.addView(chip(top.model, 0));
            if (top.host != null && top.branch == null) chips.addView(chip(top.host, 0));
            project.setText(top.name());
            fitHeadline();
        } else {
            project.setText(s.headline);
        }
        // Page dots when rotating: ● ○ ○
        if (rot.size() > 1) {
            SpannableStringBuilder dots = new SpannableStringBuilder();
            for (StatusClient.Session x : rot) {
                boolean on = x == top;
                dots.append(span(on ? "●" : "○", on ? U.colorFor(x.state) : U.FAINT)).append(" ");
            }
            pager.setText(dots);
            pager.setVisibility(VISIBLE);
        } else {
            pager.setVisibility(GONE);
        }
        renderActivity(s);

        // details line
        SpannableStringBuilder m = new SpannableStringBuilder();
        if (top != null) {
            if (top.ask != null) {
                sep(m).append(span("Reply from the 🔔 on Home", U.WAITING));
            }
            if (top.contextPct >= 0) sep(m).append(span(String.format(Locale.US, "Context %.0f%%", top.contextPct),
                    top.contextPct >= 80 ? U.WAITING : U.FAINT));
            if (top.linesAdded >= 0) {
                sep(m).append(span("+" + top.linesAdded, 0xB37EE2A8)).append(" ")
                        .append(span("−" + Math.max(0, top.linesRemoved), 0xB3FF7A7A));
            } else if (top.edits > 0) {
                sep(m).append(top.edits + (top.edits == 1 ? " edit" : " edits"));
            }
            sep(m).append(top.prompts + (top.prompts == 1 ? " prompt" : " prompts"));
            sep(m).append("session ").append(dur(top.lastAt - top.startedAt));
        }
        meta.setText(m);

        // Chips for sessions not in the rotation (e.g. working ones while another needs you).
        others.removeAllViews();
        for (StatusClient.Session x : s.sessions) {
            if (others.getChildCount() >= 3) break;
            if (rot.contains(x) || "away".equals(x.state)) continue;
            others.addView(chip(x.name() + " — " + x.activity, U.colorFor(x.state)));
        }
        others.setVisibility(others.getChildCount() > 0 ? VISIBLE : GONE);
        renderUsage(s.usage, top);
    }

    /** Shrink the session name (46 → 32 px) until it fits on one line; ellipsize only after that. */
    private void fitHeadline() {
        project.post(() -> {
            int avail = project.getWidth() - project.getPaddingLeft() - project.getPaddingRight();
            if (avail <= 0) return;
            CharSequence t = project.getText();
            for (float size = 46; size >= 32; size -= 2) {
                project.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, U.pxf(size));
                if (project.getPaint().measureText(t, 0, t.length()) <= avail) break;
            }
        });
    }

    private void renderActivity(StatusClient.Snapshot s) {
        List<StatusClient.Session> rot = rotation(s);
        StatusClient.Session top = featured(s, rot);
        if (top == null && !rot.isEmpty()) top = rot.get(0);
        if (top == null) {
            activity.setText(s.lastActivityAt > 0 ? "Last activity " + ago(now() - s.lastActivityAt) : "Waiting for Claude Code activity");
            return;
        }
        SpannableStringBuilder a = new SpannableStringBuilder(top.ask != null ? top.ask.title : top.activity);
        if ("working".equals(top.state) && top.turnStartedAt > 0) {
            a.append(span("  ·  " + clock(now() - top.turnStartedAt), U.FAINT));
        }
        activity.setText(a);
    }

    /**
     * Rings show the account the featured session runs on (matched by where it runs: WSL or the
     * Windows-side app), falling back to cswap's active account. The pool strip shows all accounts.
     */
    private void renderUsage(StatusClient.Usage u, StatusClient.Session session) {
        if (u == null) {
            usageCol.setAlpha(0.5f);
            fhRing.setValue(-1);
            sdRing.setValue(-1);
            usageAccount.setText("");
            fhSub.setText("no data");
            sdSub.setText("");
            poolView.set(new java.util.ArrayList<>(), -1);
            return;
        }
        usageCol.setAlpha(1f);
        StatusClient.Pool acct = null;
        if (session != null && session.env != null) {
            for (StatusClient.Pool p : u.pool) if (p.env.contains(session.env)) { acct = p; break; }
        }
        if (acct == null) for (StatusClient.Pool p : u.pool) if (p.active) { acct = p; break; }

        double fh, sd;
        long fhr, sdr;
        boolean ahead, willLast;
        String where = null;
        int number;
        if (acct != null) {
            fh = acct.fh; sd = acct.sd; fhr = acct.fhResetsAt; sdr = acct.sdResetsAt;
            ahead = acct.ahead; willLast = acct.willLast; number = acct.number;
            if (!acct.env.isEmpty()) where = acct.env.contains("windows") && !acct.env.contains("wsl") ? "Windows" : acct.env.contains("wsl") && !acct.env.contains("windows") ? "WSL" : null;
        } else {
            fh = u.fiveHour == null ? -1 : u.fiveHour.pct; fhr = u.fiveHour == null ? 0 : u.fiveHour.resetsAt;
            sd = u.sevenDay == null ? -1 : u.sevenDay.pct; sdr = u.sevenDay == null ? 0 : u.sevenDay.resetsAt;
            ahead = u.aheadOfPace; willLast = u.willLastToReset; number = u.number;
        }
        StringBuilder acc = new StringBuilder();
        if (number > 0) acc.append("#").append(number);
        if (where != null) acc.append(acc.length() > 0 ? " · " : "").append(where);
        else if (u.plan != null && !u.plan.isEmpty()) acc.append(acc.length() > 0 ? " · " : "").append(u.plan);
        usageAccount.setText(acc);

        fhRing.setValue((float) fh);
        fhSub.setText(fh < 0 ? "" : fhr > 0 ? "resets " + timeOf(fhr) : "fresh window");
        sdRing.setValue((float) sd);
        if (sd < 0) sdSub.setText("");
        else if (!willLast) sdSub.setText(span("won't last to reset", RED));
        else if (ahead) sdSub.setText(span("ahead of pace", U.WAITING));
        else if (sdr > 0) sdSub.setText("resets " + dayOf(sdr));
        else sdSub.setText("on pace");

        poolView.set(u.pool, number);
    }

    private void renderEmpty(String why) {
        setState("offline");
        chips.removeAllViews();
        project.setText("No active sessions");
        activity.setText(why);
        meta.setText("");
        others.setVisibility(GONE);
        renderUsage(null, null);
        todayActive.setText("–");
        todayCounts.setText("");
    }

    private void setState(String state) {
        int color = U.colorFor(state);
        ((GradientDrawable) dot.getBackground()).setColor(color);
        label.setTextColor(color);
        label.setText(labelFor(state));
        setAlpha("away".equals(state) || "offline".equals(state) ? 0.78f : 1f);
        boolean animate = "working".equals(state) || "waiting".equals(state);
        if (pulse != null) { pulse.cancel(); dot.setAlpha(1f); pulse = null; }
        if (animate) {
            pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f);
            pulse.setDuration("waiting".equals(state) ? 600 : 900);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        }
    }

    void pauseAnimations() {
        if (pulse != null) pulse.pause();
        removeCallbacks(ticker);
        removeCallbacks(rotator);
    }

    void resumeAnimations() {
        if (pulse != null) pulse.resume();
        removeCallbacks(ticker);
        removeCallbacks(rotator);
        post(ticker);
        postDelayed(rotator, ROTATE_MS);
    }

    // ---------------------------------------------------------------- formatting

    private long now() { return System.currentTimeMillis() + clockSkew; }

    private static SpannableStringBuilder sep(SpannableStringBuilder b) {
        if (b.length() > 0) b.append("   ·   ");
        return b;
    }

    private static SpannableStringBuilder span(String s, int color) {
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        b.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private String timeOf(long ms) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(ms));
    }

    private String dayOf(long ms) {
        long days = (ms - System.currentTimeMillis()) / 86_400_000L;
        if (days < 1) return timeOf(ms);
        return DateFormat.format("EEE", new Date(ms)).toString();
    }

    private static String labelFor(String state) {
        switch (state == null ? "" : state) {
            case "working": return "WORKING";
            case "waiting": return "NEEDS YOU";
            case "ready": return "READY";
            case "idle": return "OPEN";
            case "away": return "AWAY";
            default: return "OFFLINE";
        }
    }

    static String dur(long ms) {
        long m = Math.max(0, Math.round(ms / 60000.0));
        return m < 60 ? m + "m" : String.format(Locale.US, "%dh %02dm", m / 60, m % 60);
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 3600 ? String.format(Locale.US, "%d:%02d", s / 60, s % 60) : String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String ago(long ms) {
        return ms < 60_000 ? "just now" : dur(ms) + " ago";
    }
}
