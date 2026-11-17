package tv.attic.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class HomeActivity extends Activity implements StatusClient.Listener {
    private static final String TAG = "AtticHome";
    private static final int APPS_COLUMNS = 7;

    private AppRepository repo;
    private List<AppEntry> installed = new ArrayList<>();
    private List<String> pins = new ArrayList<>();

    private FrameLayout root;
    private LinearLayout row;
    private ImageView gear, wifi;
    private TextView clock;
    private FrameLayout appsLayer;
    private LinearLayout appsGrid;
    private ScrollView appsScroll;
    private StatusCardView card;
    private FrameLayout bell;
    private TextView badge;
    private NotificationsPanel panel;
    private StatusClient.Snapshot lastSnapshot;
    private StatusClient status;
    private boolean appsDirty;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (hasWindowFocus()) reloadApps();
        }
    };
    private ConnectivityManager.NetworkCallback netCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        U.init(this);
        repo = new AppRepository(this);
        buildViews();
        setContentView(root);
        hideSystemBars();
        reloadApps();
        status = new StatusClient(this);
        card.setVisibility(StatusClient.configured() ? View.VISIBLE : View.GONE);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
        registerNetworkCallback();
        NotifyService.start(this);
        NotifListener.setOnChange(() -> {
            updateBell();
            if (panel.isShowing()) panel.render(lastSnapshot, NotifListener.visible(getPackageName()), NotifListener.connected());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        updateNetwork();
        if (appsDirty) reloadApps();
        status.start();
        card.resumeAnimations();
        NotifyService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeReceiver);
        status.stop();
        card.pauseAnimations();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: close the apps layer and go back to the first tile.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            hidePanel();
            hideApps();
            if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (panel.isShowing()) hidePanel();
        else if (appsLayer.getVisibility() == View.VISIBLE) hideApps();
        // A launcher never finishes on Back.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.addView(new WallpaperView(this), match());

        gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_gear);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int gp = U.px(10);
        gear.setPadding(gp, gp, gp, gp);
        gear.setFocusable(true);
        gear.setId(View.generateViewId());
        gear.setContentDescription("Settings");
        gear.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? circle(0x33FFFFFF) : null);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(140).start();
        });
        gear.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        root.addView(gear, at(U.px(76), U.px(42), U.px(76), U.px(76)));

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setClipChildren(false);
        bell = new FrameLayout(this);
        bell.setClipChildren(false);
        bell.setFocusable(true);
        bell.setId(View.generateViewId());
        bell.setContentDescription("Notifications");
        ImageView bellIcon = new ImageView(this);
        bellIcon.setImageResource(R.drawable.ic_bell);
        bell.addView(bellIcon, new FrameLayout.LayoutParams(U.px(40), U.px(40), Gravity.CENTER));
        badge = U.text(this, 17, 0xFF10131A, U.SANS_MEDIUM);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(U.px(28));
        badge.setPadding(U.px(6), 0, U.px(6), 0);
        badge.setVisibility(View.GONE);
        bell.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, U.px(28), Gravity.TOP | Gravity.END));
        bell.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? circle(0x33FFFFFF) : null);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(140).start();
        });
        bell.setOnClickListener(v -> showPanel());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(U.px(76), U.px(76));
        bl.rightMargin = U.px(14);
        status.addView(bell, bl);
        wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.ic_wifi);
        status.addView(wifi, new LinearLayout.LayoutParams(U.px(36), U.px(36)));
        clock = U.text(this, 34, 0xFFE8EAED, U.SANS);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = U.px(18);
        status.addView(clock, cl);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, U.px(76), Gravity.END | Gravity.TOP);
        sl.topMargin = U.px(42);
        sl.rightMargin = U.px(96);
        root.addView(status, sl);

        row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        rl.topMargin = U.px(476);
        root.addView(row, rl);

        card = new StatusCardView(this);
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(U.px(1480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        kl.topMargin = U.px(722);
        root.addView(card, kl);

        buildAppsLayer();
        panel = new NotificationsPanel(this, new NotificationsPanel.Actions() {
            @Override public void reply(StatusClient.Session x, String optionId, String label) {
                try {
                    org.json.JSONObject b = new org.json.JSONObject().put("action", "reply").put("session", x.id)
                            .put("requestId", x.ask.requestId).put("option", optionId);
                    HomeActivity.this.status.control(b, (ok, msg) -> Toast.makeText(HomeActivity.this,
                            ok ? "Sent to " + x.name() + ": " + label : "Couldn't send: " + msg, Toast.LENGTH_LONG).show());
                } catch (org.json.JSONException ignored) { }
            }
            @Override public void setAway(boolean on) {
                try {
                    HomeActivity.this.status.control(new org.json.JSONObject().put("action", "away").put("on", on), (ok, msg) -> Toast.makeText(HomeActivity.this,
                            ok ? (on ? "Away mode on — answer prompts from here" : "Away mode off") : "Couldn't change: " + msg, Toast.LENGTH_SHORT).show());
                } catch (org.json.JSONException ignored) { }
            }
        });
        root.addView(panel, match());
        gear.setNextFocusRightId(bell.getId());
        bell.setNextFocusLeftId(gear.getId());
        bell.setNextFocusRightId(bell.getId());
        bell.setNextFocusUpId(bell.getId());
    }

    private void buildAppsLayer() {
        appsLayer = new FrameLayout(this);
        appsLayer.setBackgroundColor(0xFF0B0F16);
        appsLayer.setVisibility(View.GONE);
        TextView title = U.text(this, 44, U.TEXT, U.SANS);
        title.setText("Apps");
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = U.px(120);
        tl.topMargin = U.px(70);
        appsLayer.addView(title, tl);
        TextView hint = U.text(this, 22, U.FAINT, U.SANS);
        hint.setText("Hold OK on an app to add it to Home");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        hl.rightMargin = U.px(120);
        hl.topMargin = U.px(86);
        appsLayer.addView(hint, hl);

        appsScroll = new ScrollView(this);
        appsScroll.setClipChildren(false);
        appsScroll.setClipToPadding(false);
        appsScroll.setVerticalScrollBarEnabled(false);
        appsScroll.setPadding(0, U.px(30), 0, U.px(60));
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsScroll.addView(appsGrid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.topMargin = U.px(150);
        appsLayer.addView(appsScroll, gl);
        root.addView(appsLayer, match());
    }

    // ---------------------------------------------------------------- data

    private void reloadApps() {
        appsDirty = false;
        installed = repo.loadAll();
        pins = repo.pins(installed);
        rebuildRow(null);
        rebuildAppsGrid();
    }

    private void rebuildRow(String focusPkg) {
        row.removeAllViews();
        List<AppEntry> items = new ArrayList<>();
        for (String p : pins) items.add(AppRepository.find(installed, p));
        Drawable appsIcon = getDrawable(R.drawable.ic_apps);
        items.add(AppEntry.appsTile(appsIcon));
        View focusTarget = null;
        for (int i = 0; i < items.size(); i++) {
            AppEntry a = items.get(i);
            TileView t = new TileView(this, a);
            t.setId(View.generateViewId());
            t.setNextFocusUpId(i >= (items.size() + 1) / 2 ? bell.getId() : gear.getId());
            t.setNextFocusDownId(t.getId()); // nothing focusable below the row
            if (a.special) {
                t.setOnClickListener(v -> showApps());
            } else {
                t.setOnClickListener(v -> launch(a));
                t.setOnLongClickListener(v -> { pinnedMenu(a); return true; });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(TileView.widthPx(), TileView.heightPx());
            if (i > 0) lp.leftMargin = U.px(80);
            row.addView(t, lp);
            if (a.pkg.equals(focusPkg)) focusTarget = t;
        }
        // Wrap horizontally at the ends.
        View first = row.getChildAt(0), last = row.getChildAt(row.getChildCount() - 1);
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
        gear.setNextFocusDownId(first.getId());
        bell.setNextFocusDownId(last.getId());
        View target = focusTarget != null ? focusTarget : first;
        if (appsLayer.getVisibility() != View.VISIBLE) target.post(target::requestFocus);
    }

    private void rebuildAppsGrid() {
        appsGrid.removeAllViews();
        if (installed.isEmpty()) {
            TextView empty = U.text(this, 28, U.MUTED, U.SANS);
            empty.setText("No apps installed");
            appsGrid.addView(empty);
            return;
        }
        LinearLayout line = null;
        for (int i = 0; i < installed.size(); i++) {
            if (i % APPS_COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                line.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ll.bottomMargin = U.px(36);
                appsGrid.addView(line, ll);
            }
            AppEntry a = installed.get(i);
            TileView t = new TileView(this, a);
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> { appMenu(a); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(TileView.widthPx(), TileView.heightPx());
            if (i % APPS_COLUMNS > 0) lp.leftMargin = U.px(80);
            line.addView(t, lp);
        }
    }

    private void showApps() {
        appsLayer.setAlpha(0f);
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.animate().alpha(1f).setDuration(160).start();
        appsScroll.scrollTo(0, 0);
        if (appsGrid.getChildCount() > 0) {
            View firstLine = appsGrid.getChildAt(0);
            if (firstLine instanceof ViewGroup && ((ViewGroup) firstLine).getChildCount() > 0) {
                ((ViewGroup) firstLine).getChildAt(0).requestFocus();
            }
        }
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false);
    }

    private void hideApps() {
        if (appsLayer.getVisibility() != View.VISIBLE) return;
        appsLayer.setVisibility(View.GONE);
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true);
        View appsTile = row.getChildAt(row.getChildCount() - 1);
        if (appsTile != null) appsTile.requestFocus();
    }

    // ---------------------------------------------------------------- menus

    private void pinnedMenu(AppEntry a) {
        int idx = pins.indexOf(a.pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (idx > 0) { labels.add("Move Left"); actions.add(() -> movePin(a.pkg, -1)); }
        if (idx >= 0 && idx < pins.size() - 1) { labels.add("Move Right"); actions.add(() -> movePin(a.pkg, 1)); }
        labels.add("Remove from Home");
        actions.add(() -> { pins.remove(a.pkg); repo.savePins(pins); rebuildRow(null); });
        addCommon(a, labels, actions);
        menu(a.label, labels, actions);
    }

    private void appMenu(AppEntry a) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (pins.contains(a.pkg)) {
            labels.add("Remove from Home");
            actions.add(() -> { pins.remove(a.pkg); repo.savePins(pins); rebuildRow(null); });
        } else {
            labels.add("Add to Home");
            actions.add(() -> {
                if (pins.size() >= AppRepository.MAX_PINS) {
                    Toast.makeText(this, "Home is full — remove an app first", Toast.LENGTH_LONG).show();
                    return;
                }
                pins.add(a.pkg);
                repo.savePins(pins);
                rebuildRow(null);
                Toast.makeText(this, a.label + " added to Home", Toast.LENGTH_SHORT).show();
            });
        }
        addCommon(a, labels, actions);
        menu(a.label, labels, actions);
    }

    private void addCommon(AppEntry a, List<String> labels, List<Runnable> actions) {
        labels.add("App Info");
        actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))));
        labels.add("Uninstall…");
        actions.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
    }

    private void menu(String title, List<String> labels, List<Runnable> actions) {
        new AlertDialog.Builder(this, R.style.AtticDialog)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void movePin(String pkg, int delta) {
        int i = pins.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= pins.size()) return;
        pins.remove(i);
        pins.add(j, pkg);
        repo.savePins(pins);
        rebuildRow(pkg);
    }

    // ---------------------------------------------------------------- actions

    private void launch(AppEntry a) {
        Intent i = repo.launchIntent(a);
        if (i == null) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            return;
        }
        open(i);
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed: " + i, e);
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu/settings key on the remote opens Android settings.
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            open(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- notifications (bell)

    private void showPanel() {
        getSharedPreferences("attic", MODE_PRIVATE).edit().putLong("panelSeenAt",
                lastSnapshot != null ? lastSnapshot.serverTime : System.currentTimeMillis()).apply();
        panel.render(lastSnapshot, NotifListener.visible(getPackageName()), NotifListener.connected());
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false);
        panel.show();
        updateBell();
        this.status.refreshNow();
    }

    private void hidePanel() {
        if (!panel.isShowing()) return;
        panel.hide();
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true);
        bell.requestFocus();
    }

    /** Badge = sessions waiting on you + unseen alerts + Android notifications. Amber when someone waits. */
    private void updateBell() {
        int waiting = 0, unseen = 0;
        long seenAt = getSharedPreferences("attic", MODE_PRIVATE).getLong("panelSeenAt", 0);
        if (lastSnapshot != null) {
            for (StatusClient.Session x : lastSnapshot.sessions) if ("waiting".equals(x.state) || x.ask != null) waiting++;
            for (StatusClient.Note n : lastSnapshot.notes) if (n.at > seenAt && !isNeedsYou(n.kind)) unseen++;
        }
        int system = NotifListener.visible(getPackageName()).size();
        int count = waiting + unseen + system;
        if (count == 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(U.pxf(14));
        bg.setColor(waiting > 0 ? U.WAITING : U.READY);
        badge.setBackground(bg);
        badge.setText(count > 9 ? "9+" : String.valueOf(count));
        badge.setVisibility(View.VISIBLE);
    }

    private static boolean isNeedsYou(String kind) {
        return "permission".equals(kind) || "question".equals(kind) || "reply".equals(kind);
    }

    // ---------------------------------------------------------------- status card

    @Override
    public void onStatus(StatusClient.Snapshot s) {
        card.render(s);
        lastSnapshot = s;
        updateBell();
        if (panel.isShowing()) panel.render(s, NotifListener.visible(getPackageName()), NotifListener.connected());
    }

    @Override
    public void onError(String reason) {
        card.showError(reason);
    }

    // ---------------------------------------------------------------- top bar

    private void updateClock() {
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { runOnUiThread(HomeActivity.this::updateNetwork); }
            @Override public void onLost(Network n) { runOnUiThread(HomeActivity.this::updateNetwork); }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException e) {
            netCallback = null;
        }
    }

    private void updateNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean online = c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean ethernet = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        wifi.setVisibility(ethernet ? View.GONE : View.VISIBLE);
        wifi.setAlpha(online ? 1f : 0.35f);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams at(int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.START | Gravity.TOP);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
