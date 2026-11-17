package tv.attic.home;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String pkg;
    final ComponentName component;
    final String label;
    final Drawable icon;
    final Drawable banner;   // leanback banner, may be null
    final boolean leanback;
    final boolean special;   // the "Apps" tile

    AppEntry(String pkg, ComponentName component, String label, Drawable icon, Drawable banner, boolean leanback) {
        this(pkg, component, label, icon, banner, leanback, false);
    }

    private AppEntry(String pkg, ComponentName component, String label, Drawable icon, Drawable banner,
                     boolean leanback, boolean special) {
        this.pkg = pkg;
        this.component = component;
        this.label = label;
        this.icon = icon;
        this.banner = banner;
        this.leanback = leanback;
        this.special = special;
    }

    static AppEntry appsTile(Drawable icon) {
        return new AppEntry("", null, "Apps", icon, null, false, true);
    }
}
