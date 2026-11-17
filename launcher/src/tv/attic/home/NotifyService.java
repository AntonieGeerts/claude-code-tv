package tv.attic.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.util.List;

/**
 * Keeps polling the status API while you watch other apps and pops up Claude Code alerts
 * (needs your OK, ready to ship, shipped, finished) over them. Started by HomeActivity,
 * which the system launches at boot.
 */
public final class NotifyService extends Service implements StatusClient.Listener {
    private static final String CH_SERVICE = "attic-service", CH_ALERTS = "attic-alerts";
    private static final int ID_SERVICE = 1;

    private StatusClient client;
    private NotifyOverlay overlay;
    private SharedPreferences prefs;
    private int alertId = 100;

    static void start(Context c) {
        if (!StatusClient.configured()) return;
        try {
            c.startForegroundService(new Intent(c, NotifyService.class));
        } catch (RuntimeException ignored) {
            // e.g. background-start restrictions; HomeActivity retries on next resume
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("attic", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "Status watcher", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERTS, "Claude Code alerts", NotificationManager.IMPORTANCE_HIGH));
        Notification n = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Attic Home")
                .setContentText("Watching Claude Code activity")
                .setOngoing(true)
                .build();
        startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        overlay = new NotifyOverlay(this);
        client = new StatusClient(this, 15_000, 30_000);
        client.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (client != null) client.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatus(StatusClient.Snapshot s) {
        long seen = prefs.getLong("lastNoteAt", 0);
        if (seen == 0) {
            // First run: don't replay old alerts.
            prefs.edit().putLong("lastNoteAt", s.serverTime).apply();
            return;
        }
        List<StatusClient.Note> notes = s.notes; // newest first
        long newest = seen;
        for (int i = notes.size() - 1; i >= 0; i--) {
            StatusClient.Note n = notes.get(i);
            if (n.at <= seen) continue;
            deliver(n);
            newest = Math.max(newest, n.at);
        }
        if (newest != seen) prefs.edit().putLong("lastNoteAt", newest).apply();
    }

    @Override
    public void onError(String reason) { }

    private void deliver(StatusClient.Note n) {
        if (NotifyOverlay.allowed(this)) {
            overlay.show(n);
            return;
        }
        // Fallback: a regular notification (shows in the Google TV notification panel).
        Notification notif = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setSubText(NotifyOverlay.kicker(n.kind))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(alertId++, notif);
    }
}
