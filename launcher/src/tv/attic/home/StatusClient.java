package tv.attic.home;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Polls the status API. Used by the home card (while visible) and by NotifyService. */
final class StatusClient {
    interface Listener {
        void onStatus(Snapshot s);
        void onError(String reason);
    }

    static final class Ask {
        String requestId, kind, title, question;
        final List<String> optionIds = new ArrayList<>();
        final List<String> optionLabels = new ArrayList<>();
    }

    static final class Session {
        String id, title, env;
        Ask ask;

        /** Session name (Claude Code title) when known, else the project folder. */
        String name() { return title != null && !title.isEmpty() ? title : project; }
        String project, host, model, branch, state, activity;
        long startedAt, lastAt, turnStartedAt;
        int prompts, edits;
        double contextPct = -1;
        long linesAdded = -1, linesRemoved = -1;
    }

    static final class Window {
        double pct;
        long resetsAt; // epoch ms, 0 = unknown
    }

    static final class Pool {
        String label;
        int number;
        double fh = -1, sd = -1;
        long fhResetsAt, sdResetsAt;
        boolean active, ahead, willLast = true;
        final List<String> env = new ArrayList<>(); // "wsl" / "windows": Claude Code installs logged in here
    }

    static final class Usage {
        Window fiveHour, sevenDay;
        String account, plan, source;
        int number, of;
        boolean aheadOfPace, willLastToReset = true;
        double expectedPct = -1;
        final List<String> scopedNames = new ArrayList<>();
        final List<Double> scopedPcts = new ArrayList<>();
        final List<Pool> pool = new ArrayList<>();
    }

    static final class Note {
        String id, kind, project, title, body;
        long at;
    }

    static final class Snapshot {
        String state, headline;
        final List<Session> sessions = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        Usage usage;
        int activeMinutes, prompts, edits, tools;
        int[] hours = new int[24];
        long lastActivityAt, serverTime;
        boolean away;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final long pollMs, retryMs;
    private boolean running;
    private final Runnable tick = this::fetch;

    StatusClient(Listener l) { this(l, 20_000, 30_000); }

    StatusClient(Listener l, long pollMs, long retryMs) {
        listener = l;
        this.pollMs = pollMs;
        this.retryMs = retryMs;
    }

    static boolean configured() {
        String u = Config.STATUS_URL;
        boolean local = u.startsWith("http://localhost:") || u.startsWith("http://127.0.0.1:");
        return (u.startsWith("https://") || local) && !Config.READ_TOKEN.isEmpty();
    }

    void start() {
        if (running || !configured()) return;
        running = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    void refreshNow() {
        if (!running) return;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void fetch() {
        if (!running) return;
        io.execute(() -> {
            long next = pollMs;
            try {
                Snapshot s = parse(get());
                ui.post(() -> { if (running) listener.onStatus(s); });
            } catch (Exception e) {
                next = retryMs;
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> { if (running) listener.onError(why); });
            }
            long delay = next;
            ui.post(() -> { if (running) { ui.removeCallbacks(tick); ui.postDelayed(tick, delay); } });
        });
    }

    private static String get() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.STATUS_URL).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("Authorization", "Bearer " + Config.READ_TOKEN);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    interface Done {
        void done(boolean ok, String message);
    }

    /** POST /api/control on a background thread; result delivered on the UI thread. */
    void control(JSONObject body, Done cb) {
        io.execute(() -> {
            boolean ok = false;
            String msg;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(Config.STATUS_URL.replace("/api/status", "/api/control")).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + Config.READ_TOKEN);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                JSONObject r = new JSONObject(out.size() > 0 ? out.toString("UTF-8") : "{}");
                ok = code == 200;
                msg = ok ? "ok" : r.optString("error", "HTTP " + code);
                c.disconnect();
            } catch (Exception e) {
                msg = e.getMessage() == null ? "network error" : e.getMessage();
            }
            boolean fOk = ok;
            String fMsg = msg;
            ui.post(() -> cb.done(fOk, fMsg));
        });
        refreshSoon();
    }

    private void refreshSoon() {
        ui.postDelayed(this::refreshNow, 1200);
    }

    private static String str(JSONObject o, String k) {
        return o == null || o.isNull(k) ? null : o.optString(k, null);
    }

    private static double dbl(JSONObject o, String k) {
        return o == null || o.isNull(k) ? -1 : o.optDouble(k, -1);
    }

    private static Window window(JSONObject o) {
        if (o == null || o.isNull("pct")) return null;
        Window w = new Window();
        w.pct = o.optDouble("pct", 0);
        w.resetsAt = o.isNull("resetsAt") ? 0 : o.optLong("resetsAt", 0);
        return w;
    }

    static Snapshot parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        Snapshot s = new Snapshot();
        s.state = o.optString("state", "offline");
        s.headline = o.optString("headline", "");
        s.lastActivityAt = o.optLong("lastActivityAt", 0);
        s.serverTime = o.optLong("serverTime", System.currentTimeMillis());
        s.away = o.optBoolean("away", false);
        JSONObject today = o.optJSONObject("today");
        if (today != null) {
            s.activeMinutes = today.optInt("activeMinutes");
            s.prompts = today.optInt("prompts");
            s.edits = today.optInt("edits");
            s.tools = today.optInt("tools");
            JSONArray h = today.optJSONArray("hours");
            for (int i = 0; h != null && i < Math.min(24, h.length()); i++) s.hours[i] = h.optInt(i);
        }
        JSONArray arr = o.optJSONArray("sessions");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            Session x = new Session();
            x.project = j.optString("project", "");
            x.host = str(j, "host");
            x.model = str(j, "model");
            x.branch = str(j, "branch");
            x.state = j.optString("state", "idle");
            x.activity = j.optString("activity", "");
            x.startedAt = j.optLong("startedAt");
            x.lastAt = j.optLong("lastAt");
            x.turnStartedAt = j.isNull("turnStartedAt") ? 0 : j.optLong("turnStartedAt");
            x.prompts = j.optInt("prompts");
            x.edits = j.optInt("edits");
            x.contextPct = dbl(j, "contextPct");
            x.linesAdded = (long) dbl(j, "linesAdded");
            x.linesRemoved = (long) dbl(j, "linesRemoved");
            x.id = j.optString("id", "");
            x.title = str(j, "title");
            x.env = str(j, "env");
            JSONObject a = j.optJSONObject("ask");
            if (a != null) {
                Ask k = new Ask();
                k.requestId = a.optString("requestId");
                k.kind = a.optString("kind", "turn");
                k.title = a.optString("title", "");
                k.question = a.optString("question", "");
                JSONArray opts = a.optJSONArray("options");
                for (int q = 0; opts != null && q < opts.length(); q++) {
                    k.optionIds.add(opts.getJSONObject(q).optString("id"));
                    k.optionLabels.add(opts.getJSONObject(q).optString("label"));
                }
                if (!k.optionIds.isEmpty()) x.ask = k;
            }
            s.sessions.add(x);
        }
        JSONObject u = o.optJSONObject("usage");
        if (u != null) {
            Usage x = new Usage();
            x.fiveHour = window(u.optJSONObject("fiveHour"));
            x.sevenDay = window(u.optJSONObject("sevenDay"));
            x.source = str(u, "source");
            JSONObject acc = u.optJSONObject("account");
            if (acc != null) {
                x.account = str(acc, "label");
                x.plan = str(acc, "plan");
                x.number = acc.optInt("number");
                x.of = acc.optInt("of");
            }
            JSONObject pace = u.optJSONObject("weekPace");
            if (pace != null) {
                x.aheadOfPace = pace.optBoolean("aheadOfPace");
                x.willLastToReset = pace.optBoolean("willLastToReset", true);
                x.expectedPct = dbl(pace, "expectedPct");
            }
            JSONArray sc = u.optJSONArray("scoped");
            for (int i = 0; sc != null && i < sc.length(); i++) {
                JSONObject j = sc.getJSONObject(i);
                x.scopedNames.add(j.optString("name"));
                x.scopedPcts.add(j.optDouble("pct", 0));
            }
            JSONArray pl = u.optJSONArray("pool");
            for (int i = 0; pl != null && i < pl.length(); i++) {
                JSONObject j = pl.getJSONObject(i);
                Pool p = new Pool();
                p.label = j.optString("label");
                p.fh = dbl(j, "fh");
                p.sd = dbl(j, "sd");
                p.active = j.optBoolean("active");
                p.number = j.optInt("number");
                p.fhResetsAt = j.isNull("fhr") ? 0 : j.optLong("fhr");
                p.sdResetsAt = j.isNull("sdr") ? 0 : j.optLong("sdr");
                p.ahead = j.optBoolean("ahead");
                p.willLast = j.optBoolean("willLast", true);
                JSONArray env = j.optJSONArray("env");
                for (int e = 0; env != null && e < env.length(); e++) p.env.add(env.optString(e));
                x.pool.add(p);
            }
            if (x.fiveHour != null || x.sevenDay != null) s.usage = x;
        }
        JSONArray notes = o.optJSONArray("notifications");
        for (int i = 0; notes != null && i < notes.length(); i++) {
            JSONObject j = notes.getJSONObject(i);
            Note n = new Note();
            n.id = j.optString("id");
            n.at = j.optLong("at");
            n.kind = j.optString("kind", "info");
            n.project = j.optString("project", "");
            n.title = j.optString("title", "");
            n.body = j.optString("body", "");
            s.notes.add(n);
        }
        return s;
    }
}
