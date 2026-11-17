package tv.attic.home;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mirrors Android's notifications so the launcher's bell can show them (this TV has no other
 * notification panel because the Google TV launcher is disabled). Access is granted with:
 *   adb shell cmd notification allow_listener tv.attic.home/tv.attic.home.NotifListener
 */
public final class NotifListener extends NotificationListenerService {
    private static NotifListener instance;
    private static Runnable onChange;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    static void setOnChange(Runnable r) { onChange = r; }

    static boolean connected() { return instance != null; }

    /** Notifications worth showing: not ours, not silent background-service notices, has text. */
    static List<StatusBarNotification> visible(String ownPackage) {
        NotifListener l = instance;
        if (l == null) return Collections.emptyList();
        List<StatusBarNotification> out = new ArrayList<>();
        StatusBarNotification[] all;
        try {
            all = l.getActiveNotifications();
        } catch (RuntimeException e) {
            return out;
        }
        if (all == null) return out;
        for (StatusBarNotification s : all) {
            Notification n = s.getNotification();
            if (s.getPackageName().equals(ownPackage)) continue;
            if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            boolean hasText = (title != null && title.length() > 0) || (text != null && text.length() > 0);
            if (!hasText) continue;
            if (s.isOngoing() && n.contentIntent == null) continue;
            out.add(s);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out;
    }

    static void dismiss(StatusBarNotification s) {
        NotifListener l = instance;
        if (l != null && s.isClearable()) {
            try { l.cancelNotification(s.getKey()); } catch (RuntimeException ignored) { }
        }
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) ui.post(r);
    }

    @Override public void onListenerConnected() { instance = this; changed(); }
    @Override public void onListenerDisconnected() { instance = null; changed(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { changed(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { changed(); }
}
