package tv.attic.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps + the user's pinned row, persisted as a comma-separated package list. */
final class AppRepository {
    static final int MAX_PINS = 8;
    /** Default row when nothing has been pinned yet (first match per slot wins). */
    private static final String[][] DEFAULT_PINS = {
            {"com.google.android.youtube.tv"},
            {"com.netflix.ninja"},
            {"com.beinsports.connect.tv.apac"},
            {"org.xbmc.kodi"},
            {"com.disney.disneyplus", "com.disneyplus.ph"},
    };

    /** Short display names for apps whose launcher labels are too long for a tile. */
    private static final Map<String, String> LABELS = new java.util.HashMap<>();
    static {
        LABELS.put("com.google.android.youtube.tv", "YouTube");
        LABELS.put("com.netflix.ninja", "Netflix");
        LABELS.put("com.beinsports.connect.tv.apac", "beIN Sports");
        LABELS.put("org.xbmc.kodi", "Kodi");
        LABELS.put("com.disney.disneyplus", "Disney+");
        LABELS.put("com.disneyplus.ph", "Disney+");
        LABELS.put("com.android.vending", "Play Store");
    }

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppRepository(Context c) {
        ctx = c;
        pm = c.getPackageManager();
        prefs = c.getSharedPreferences("attic", Context.MODE_PRIVATE);
    }

    /** All launchable apps (leanback activity preferred), sorted by label, excluding this launcher. */
    List<AppEntry> loadAll() {
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        collect(byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(byPkg, Intent.CATEGORY_LAUNCHER, false);
        List<AppEntry> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private void collect(Map<String, AppEntry> out, String category, boolean leanback) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ctx.getPackageName().equals(ai.packageName) || out.containsKey(ai.packageName)) continue;
            String label = LABELS.containsKey(ai.packageName) ? LABELS.get(ai.packageName) : String.valueOf(ri.loadLabel(pm)).trim();
            Drawable banner = null;
            if (leanback) {
                banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            }
            Drawable icon = ri.loadIcon(pm);
            out.put(ai.packageName, new AppEntry(ai.packageName, new ComponentName(ai.packageName, ai.name),
                    label.isEmpty() ? ai.packageName : label, icon, banner, leanback));
        }
    }

    List<String> pins(List<AppEntry> installed) {
        String raw = prefs.getString("pins", null);
        List<String> pins = new ArrayList<>();
        if (raw != null) {
            if (!raw.isEmpty()) pins.addAll(Arrays.asList(raw.split(",")));
        } else {
            for (String[] slot : DEFAULT_PINS) {
                for (String p : slot) {
                    if (find(installed, p) != null) { pins.add(p); break; }
                }
            }
        }
        // Drop anything uninstalled since it was pinned.
        List<String> kept = new ArrayList<>();
        for (String p : pins) if (find(installed, p) != null && !kept.contains(p)) kept.add(p);
        return kept;
    }

    void savePins(List<String> pins) {
        prefs.edit().putString("pins", String.join(",", pins)).apply();
    }

    static AppEntry find(List<AppEntry> apps, String pkg) {
        for (AppEntry a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    Intent launchIntent(AppEntry a) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null && a.component != null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
