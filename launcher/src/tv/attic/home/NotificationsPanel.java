package tv.attic.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Right-hand side sheet opened from the bell: who needs you, every live Claude Code session,
 * recent alerts, and Android's own notifications (OK opens them, hold OK dismisses).
 */
final class NotificationsPanel extends FrameLayout {
    private static final float SHEET_W = 760;

    interface Actions {
        void reply(StatusClient.Session s, String optionId, String label);
        void setAway(boolean on);
    }

    private final Actions actions;

    private final LinearLayout sheet, list;
    private final ScrollView scroll;
    private final TextView subtitle;

    NotificationsPanel(Context c, Actions actions) {
        super(c);
        this.actions = actions;
        setBackgroundColor(0x99000000);
        setVisibility(GONE);
        setClickable(true);

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(U.px(52), U.px(56), U.px(52), U.px(40));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFA171C27, 0xFA0E121A});
        sheet.setBackground(bg);
        addView(sheet, new LayoutParams(U.px(SHEET_W), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        TextView title = U.text(c, 40, U.TEXT, U.SANS_MEDIUM);
        title.setText("Notifications");
        sheet.addView(title);
        subtitle = U.text(c, 21, U.FAINT, U.SANS);
        LinearLayout.LayoutParams sl = lp();
        sl.topMargin = U.px(8);
        sheet.addView(subtitle, sl);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, U.px(12), 0, U.px(40));
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scl.topMargin = U.px(18);
        sheet.addView(scroll, scl);
    }

    boolean isShowing() { return getVisibility() == VISIBLE; }

    void show() {
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).start();
        sheet.setTranslationX(U.pxf(120));
        sheet.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        scroll.scrollTo(0, 0);
        focusFirst();
    }

    void hide() {
        setVisibility(GONE);
    }

    private void focusFirst() {
        // Prefer the first reply button, then the first focusable row.
        View firstButton = list.findViewWithTag("firstopt");
        if (firstButton != null) { firstButton.requestFocus(); return; }
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) { v.requestFocus(); return; }
        }
    }

    /** Away mode switch: while on, permission prompts and Claude's questions can be answered here. */
    private void awayRow(boolean away) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setTag("away");
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(U.px(18), U.px(14), U.px(18), U.px(14));
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(away ? 0x26FFB454 : 0x0FFFFFFF);
        idle.setCornerRadius(U.pxf(16));
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(away ? 0x40FFB454 : 0x1FFFFFFF);
        focus.setCornerRadius(U.pxf(16));
        focus.setStroke(Math.max(1, U.px(2)), 0x66FFFFFF);
        r.setBackground(idle);
        r.setOnFocusChangeListener((v, has) -> v.setBackground(has ? focus : idle));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = U.text(c, 24, U.TEXT, U.SANS_MEDIUM);
        t.setText(away ? "Away mode is on" : "Away mode is off");
        col.addView(t);
        TextView sub = U.text(c, 19, U.MUTED, U.SANS);
        sub.setSingleLine(false);
        sub.setMaxLines(2);
        sub.setText(away ? "Permission prompts and Claude's questions wait for your answer here"
                : "Turn on to approve prompts and answer questions from the TV");
        LinearLayout.LayoutParams sl = lp();
        sl.topMargin = U.px(4);
        col.addView(sub, sl);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pill = U.text(c, 19, away ? 0xFF10131A : U.TEXT, U.SANS_MEDIUM);
        pill.setText(away ? "ON" : "OFF");
        pill.setGravity(Gravity.CENTER);
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(U.pxf(20));
        pb.setColor(away ? U.WAITING : 0x33FFFFFF);
        pill.setBackground(pb);
        pill.setPadding(U.px(18), U.px(6), U.px(18), U.px(6));
        r.addView(pill);
        r.setOnClickListener(v -> actions.setAway(!away));
        LinearLayout.LayoutParams l = lp();
        l.bottomMargin = U.px(6);
        list.addView(r, l);
    }

    /** One row of reply buttons for a session's pending question. */
    private void options(StatusClient.Session x) {
        Context c = getContext();
        LinearLayout bar = new LinearLayout(c);
        bar.setPadding(U.px(76), 0, 0, U.px(10));
        boolean first = list.findViewWithTag("firstopt") == null;
        for (int i = 0; i < x.ask.optionIds.size(); i++) {
            String id = x.ask.optionIds.get(i), label = x.ask.optionLabels.get(i);
            TextView b = U.text(c, 21, U.TEXT, U.SANS_MEDIUM);
            b.setText(label);
            b.setTag(first && i == 0 ? "firstopt" : "opt:" + x.id + ":" + id);
            b.setFocusable(true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(U.px(20), U.px(10), U.px(20), U.px(10));
            GradientDrawable idle = new GradientDrawable();
            idle.setCornerRadius(U.pxf(24));
            idle.setColor(0x1FFFFFFF);
            idle.setStroke(Math.max(1, U.px(1.5f)), 0x33FFFFFF);
            GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(U.pxf(24));
            focus.setColor(0xFFF1F3F4);
            b.setBackground(idle);
            b.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? focus : idle);
                ((TextView) v).setTextColor(has ? 0xFF10131A : U.TEXT);
            });
            b.setOnClickListener(v -> {
                v.setEnabled(false);
                ((TextView) v).setText("Sending…");
                actions.reply(x, id, label);
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.rightMargin = U.px(12);
            bar.addView(b, bl);
        }
        list.addView(bar, lp());
    }

    // ---------------------------------------------------------------- content

    void render(StatusClient.Snapshot s, List<StatusBarNotification> system, boolean listenerOk) {
        View focused = findFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        list.removeAllViews();
        int waiting = 0, live = 0;

        if (s != null) {
            for (StatusClient.Session x : s.sessions) {
                if ("waiting".equals(x.state)) waiting++;
                if (!"away".equals(x.state)) live++;
            }
            awayRow(s.away);
            int asking = 0;
            for (StatusClient.Session x : s.sessions) if (x.ask != null || "waiting".equals(x.state)) asking++;
            if (asking > 0) {
                header("NEEDS YOU", U.WAITING);
                for (StatusClient.Session x : s.sessions) {
                    if (x.ask != null) {
                        String sub = x.ask.question != null && !x.ask.question.isEmpty() ? x.ask.question : x.activity;
                        row(U.WAITING, "question".equals(x.ask.kind) || "turn".equals(x.ask.kind) ? "?" : "!", x.name() + " — " + x.ask.title, sub, ago(s, x.lastAt), null, "askrow:" + x.id);
                        options(x);
                    } else if ("waiting".equals(x.state)) {
                        String hint = s.away ? "answer in Claude Code" : "answer in Claude Code · Away mode lets you answer here";
                        row(U.WAITING, "!", x.name() + " is waiting for you", x.activity + "  ·  " + hint, ago(s, x.lastAt), null, "wait:" + x.id);
                    }
                }
            }
            header("CLAUDE CODE SESSIONS  ·  " + s.sessions.size(), U.FAINT);
            if (s.sessions.isEmpty()) empty("No open sessions");
            for (StatusClient.Session x : s.sessions) {
                StringBuilder t = new StringBuilder(x.name());
                StringBuilder sub = new StringBuilder(x.activity);
                if (x.title != null && !x.title.isEmpty()) sub.append("  ·  ").append(x.project);
                if (x.branch != null) sub.append("  ⎇ ").append(x.branch);
                if (x.host != null) sub.append("  ·  ").append(x.host);
                if (x.model != null) sub.append("  ·  ").append(x.model);
                row(U.colorFor(x.state), null, t.toString(), sub.toString(), stateLabel(x.state) + " · " + ago(s, x.lastAt), null, "sess:" + x.id);
            }
            header("RECENT ALERTS", U.FAINT);
            if (s.notes.isEmpty()) empty("Nothing in the last 30 minutes");
            for (StatusClient.Note n : s.notes) {
                row(NotifyOverlay.colorFor(n.kind), glyph(n.kind), n.title, n.body, ago(s, n.at), null, "note:" + n.id);
            }
        } else {
            header("CLAUDE CODE", U.FAINT);
            empty("Status not loaded yet");
        }

        header("ANDROID", U.FAINT);
        if (!listenerOk) {
            empty("Notification access is off — run install.sh to enable it");
        } else if (system.isEmpty()) {
            empty("No notifications");
        } else {
            PackageManager pm = getContext().getPackageManager();
            for (StatusBarNotification sbn : system) {
                Notification n = sbn.getNotification();
                CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                String app;
                try {
                    app = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = sbn.getPackageName();
                }
                String head = title != null && title.length() > 0 ? title.toString() : app;
                String sub = (text != null ? text + "  ·  " : "") + app;
                row(U.READY, "•", head, sub, agoLocal(sbn.getPostTime()), sbn, "sys:" + sbn.getKey());
            }
        }

        int asks = 0;
        if (s != null) for (StatusClient.Session x : s.sessions) if (x.ask != null) asks++;
        if (asks > waiting) waiting = asks;
        subtitle.setText(waiting > 0 ? waiting + (waiting == 1 ? " session needs you" : " sessions need you")
                : live > 0 ? live + (live == 1 ? " live session" : " live sessions") : "All quiet");

        if (isShowing()) {
            View target = focusTag == null ? null : list.findViewWithTag(focusTag);
            if (target != null && target.isFocusable()) target.requestFocus();
            else focusFirst();
        }
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void header(String text, int color) {
        TextView h = U.text(getContext(), 17, color, U.SANS_MEDIUM);
        h.setLetterSpacing(0.12f);
        h.setText(text);
        LinearLayout.LayoutParams l = lp();
        l.topMargin = U.px(list.getChildCount() == 0 ? 6 : 30);
        l.bottomMargin = U.px(8);
        list.addView(h, l);
    }

    private void empty(String text) {
        TextView t = U.text(getContext(), 22, U.FAINT, U.SANS);
        t.setText(text);
        t.setPadding(U.px(18), U.px(10), U.px(18), U.px(10));
        list.addView(t, lp());
    }

    private void row(int color, String glyph, String title, String sub, String right, StatusBarNotification sbn, String tag) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setTag(tag);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(U.px(18), U.px(16), U.px(18), U.px(16));
        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0x1FFFFFFF);
        focusBg.setCornerRadius(U.pxf(16));
        focusBg.setStroke(Math.max(1, U.px(2)), 0x66FFFFFF);
        r.setOnFocusChangeListener((v, has) -> v.setBackground(has ? focusBg : null));

        TextView icon = U.text(c, glyph == null ? 1 : 22, 0xFF10131A, U.SANS_MEDIUM);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ib = new GradientDrawable();
        ib.setShape(GradientDrawable.OVAL);
        ib.setColor(color);
        icon.setBackground(ib);
        int size = glyph == null ? 16 : 40;
        if (glyph != null) icon.setText(glyph);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(U.px(size), U.px(size));
        il.leftMargin = glyph == null ? U.px(12) : 0;
        il.rightMargin = glyph == null ? U.px(12) : 0;
        r.addView(icon, il);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = U.px(18);
        r.addView(col, cl);
        TextView t = U.text(c, 25, U.TEXT, U.SANS_MEDIUM);
        t.setText(title);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = U.text(c, 20, U.MUTED, U.SANS);
            s.setText(sub);
            LinearLayout.LayoutParams sl = lp();
            sl.topMargin = U.px(5);
            col.addView(s, sl);
        }
        TextView rt = U.text(c, 18, U.FAINT, U.SANS);
        rt.setText(right);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.leftMargin = U.px(14);
        r.addView(rt, rl);

        if (sbn != null) {
            r.setOnClickListener(v -> open(sbn));
            r.setOnLongClickListener(v -> {
                if (sbn.isClearable()) {
                    NotifListener.dismiss(sbn);
                    Toast.makeText(c, "Dismissed", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        LinearLayout.LayoutParams l = lp();
        l.bottomMargin = U.px(4);
        list.addView(r, l);
    }

    private void open(StatusBarNotification sbn) {
        PendingIntent pi = sbn.getNotification().contentIntent;
        if (pi == null) {
            Toast.makeText(getContext(), "This notification has no action", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pi.send();
            if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) NotifListener.dismiss(sbn);
            hide();
        } catch (PendingIntent.CanceledException e) {
            Toast.makeText(getContext(), "That notification expired", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- formatting

    private static String glyph(String kind) {
        switch (kind) {
            case "permission": case "reply": return "!";
            case "question": return "?";
            case "ship": return "↑";
            case "shipped": case "done": return "✓";
            default: return "•";
        }
    }

    private static String stateLabel(String state) {
        switch (state == null ? "" : state) {
            case "working": return "working";
            case "waiting": return "needs you";
            case "ready": return "ready";
            case "idle": return "open";
            default: return "away";
        }
    }

    private static String ago(StatusClient.Snapshot s, long at) {
        return fmtAgo(s.serverTime - at);
    }

    private static String agoLocal(long at) {
        return fmtAgo(System.currentTimeMillis() - at);
    }

    private static String fmtAgo(long ms) {
        if (ms < 60_000) return "now";
        long m = ms / 60_000;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        return h < 24 ? h + "h ago" : (h / 24) + "d ago";
    }
}
