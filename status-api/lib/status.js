// Core state machine: turns sanitized Claude Code hook/statusline events into a live status
// snapshot (plus a short notification feed) that the TV launcher polls.
import { toObject } from "./redis.js";

export const EVENTS = new Set([
  "SessionStart", "UserPromptSubmit", "PreToolUse", "Notification",
  "Stop", "SubagentStart", "SessionEnd",
  "Usage",    // from the statusLine script: plan usage %, context %, model, lines
  "Shipped",  // a push / deploy / PR succeeded (PostToolUse)
  "Notify",   // manual: hooks/tv_notify.py "title" "body"
  "Ask",      // a session is waiting for a TV reply (hooks/tv_reply_wait.py): options to show
]);

const TZ = process.env.STATUS_TZ || "UTC"; // set STATUS_TZ (e.g. Europe/Dublin) so "today" matches your day
const IDLE_GAP_MS = 5 * 60 * 1000;         // gaps longer than this don't count as active time
const STALE_MS = 30 * 60 * 1000;           // no events for 30 min -> session shown as idle/away
const DROP_MS = 6 * 60 * 60 * 1000;        // no events for 6 h -> session dropped (crashed / closed without SessionEnd)
const LONG_TURN_MS = 3 * 60 * 1000;        // turns longer than this notify when they finish
const NOTE_WINDOW_MS = 30 * 60 * 1000;     // notifications returned to clients
const NOTE_DEDUPE_MS = 2 * 60 * 1000;      // same session + kind within this window -> one notification
const SESSION_TTL_S = 2 * 24 * 60 * 60;
const DAY_TTL_S = 14 * 24 * 60 * 60;

const K = {
  session: (id) => `tv:s:${id}`,
  active: "tv:active",
  day: (d) => `tv:day:${d}`,
  lastAny: "tv:lastany",
  usage: "tv:usage",
  notes: "tv:notes",
  reply: (id) => `tv:reply:${id}`,
  away: "tv:away",
};

export function dayKey(ms, tz = TZ) {
  // en-CA formats as YYYY-MM-DD
  return new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(ms);
}

export function hourOf(ms, tz = TZ) {
  return Number(new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", hourCycle: "h23" }).format(ms));
}

const clip = (v, n) => (typeof v === "string" ? v.replace(/[\u0000-\u001f]/g, " ").trim().slice(0, n) : "");
const pct = (v) => (typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.min(100, Math.round(v * 10) / 10)) : null);
const int = (v) => (typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.round(v)) : null);
const windowOf = (w) => (w && typeof w === "object" && pct(w.pct) !== null ? { pct: pct(w.pct), resetsAt: int(w.resetsAt) } : null);
const SHIP = new Set(["deploy", "push", "merge", "release", "publish", "pr"]);

/** Validate + normalise an incoming event. Returns null if unusable. */
export function parseEvent(body) {
  if (!body || typeof body !== "object") return null;
  const event = clip(body.event, 32);
  const session_id = clip(body.session_id, 100);
  if (!EVENTS.has(event) || !/^[A-Za-z0-9_-]{4,100}$/.test(session_id)) return null;
  const ship = clip(body.ship, 16);
  return {
    event,
    session_id,
    project: clip(body.project, 60) || "unknown",
    host: clip(body.host, 40),
    activity: clip(body.activity, 90),
    tool: clip(body.tool, 48),
    edit: body.edit === true,
    model: clip(body.model, 48),
    branch: clip(body.branch, 64),
    sessionTitle: clip(body.sessionTitle, 80),
    env: body.env === "windows" ? "windows" : body.env === "wsl" ? "wsl" : "",
    ship: SHIP.has(ship) ? ship : "",
    kind: clip(body.kind, 24),
    title: clip(body.title, 80),
    body: clip(body.body, 140),
    // Usage
    modelName: clip(body.modelName, 40),
    fiveHour: windowOf(body.fiveHour),
    sevenDay: windowOf(body.sevenDay),
    contextPct: pct(body.contextPct),
    linesAdded: int(body.linesAdded),
    linesRemoved: int(body.linesRemoved),
    costUsd: typeof body.costUsd === "number" && Number.isFinite(body.costUsd) ? Math.round(body.costUsd * 100) / 100 : null,
    source: body.source === "cswap" ? "cswap" : "statusline",
    ask: event === "Ask" ? askOf(body) : null,
    usageExtra: event === "Usage" ? usageExtra(body) : null,
  };
}

/** A pending question for the TV: request id, kind, prompt text and up to 6 options (id + label only). */
function askOf(b) {
  const requestId = clip(b.requestId, 40);
  const kind = ["turn", "permission", "question"].includes(b.askKind) ? b.askKind : "turn";
  const options = Array.isArray(b.options)
    ? b.options.slice(0, 6).map((o) => ({ id: clip(o?.id, 24), label: clip(o?.label, 40) })).filter((o) => /^[a-z0-9_-]{1,24}$/i.test(o.id) && o.label)
    : [];
  if (!/^[A-Za-z0-9_-]{6,40}$/.test(requestId) || !options.length) return null;
  return { requestId, kind, title: clip(b.title, 90), question: clip(b.question, 200), options };
}

/** Account label, weekly pace, scoped limits and the account pool (cswap only), sanitised. */
function usageExtra(b) {
  const a = b.account && typeof b.account === "object" ? b.account : null;
  const p = b.weekPace && typeof b.weekPace === "object" ? b.weekPace : null;
  return {
    account: a ? { label: clip(a.label, 24), plan: clip(a.plan, 20), number: int(a.number), of: int(a.of) } : null,
    weekPace: p ? { expectedPct: pct(p.expectedPct), aheadOfPace: p.aheadOfPace === true, willLastToReset: p.willLastToReset !== false } : null,
    scoped: Array.isArray(b.scoped) ? b.scoped.slice(0, 3).map((s) => ({ name: clip(s?.name, 24), pct: pct(s?.pct) })).filter((s) => s.pct !== null) : [],
    pool: Array.isArray(b.pool) ? b.pool.slice(0, 8).map((x) => ({
      label: clip(x?.label, 24), number: int(x?.number), fh: pct(x?.fh), fhr: int(x?.fhr), sd: pct(x?.sd), sdr: int(x?.sdr),
      ahead: x?.ahead === true, willLast: x?.willLast !== false, active: x?.active === true, ok: x?.ok !== false,
      env: Array.isArray(x?.env) ? x.env.filter((v) => v === "wsl" || v === "windows") : [],
    })) : [],
  };
}

const STATE_FOR = {
  SessionStart: "idle",
  UserPromptSubmit: "working",
  PreToolUse: "working",
  SubagentStart: "working",
  Notification: "waiting",
  Stop: "ready",
  SessionEnd: "ended",
};

const DEFAULT_ACTIVITY = {
  SessionStart: "Session started",
  UserPromptSubmit: "Thinking…",
  PreToolUse: "Working",
  SubagentStart: "Running a subagent",
  Notification: "Needs your attention",
  Stop: "Done — ready for the next prompt",
  SessionEnd: "Session ended",
};

const SHIP_VERB = { deploy: "deploy", push: "push", merge: "merge", release: "release", publish: "publish", pr: "open a PR" };

export async function applyEvent(redis, e, now = Date.now()) {
  if (e.event === "Usage") return applyUsage(redis, e, now);
  if (e.event === "Ask") {
    if (!e.ask) return;
    const sKey = K.session(e.session_id);
    const [flat] = await redis.pipeline([["HGETALL", sKey]]);
    const sess = toObject(flat);
    const cmds = [
      ["HSET", sKey, "ask", JSON.stringify({ ...e.ask, at: now }), "last", now, ...(e.sessionTitle ? ["title", e.sessionTitle] : [])],
      ["EXPIRE", sKey, SESSION_TTL_S],
      ["DEL", K.reply(e.session_id)],
    ];
    // A finished turn that asks something gets its own pop-up, unless "ready to ship" just fired.
    const shipJustFired = sess["note:ship"] && now - Number(sess["note:ship"]) < NOTE_DEDUPE_MS;
    if (e.ask.kind === "turn" && !shipJustFired) {
      const project = e.sessionTitle || sess.title || sess.project || e.project;
      await pushNote(redis, sess, e, now, { kind: "question", project, title: `${project} is asking you something`, body: "Reply from the 🔔 on your TV's home screen" }, cmds);
    } else {
      await redis.pipeline(cmds);
    }
    return;
  }

  const sKey = K.session(e.session_id);
  const day = dayKey(now);
  const [sessFlat, lastAnyRaw] = await redis.pipeline([["HGETALL", sKey], ["GET", K.lastAny]]);
  const sess = toObject(sessFlat);
  const isNew = !sess.started;
  const project = e.sessionTitle || sess.title || sess.project || e.project; // display name in alerts

  if (e.event === "Shipped" || e.event === "Notify") {
    const kind = e.event === "Shipped" ? "shipped" : (e.kind || "info");
    const title = e.title || (e.event === "Shipped" ? `${e.activity || "Shipped"} · ${project}` : project);
    await pushNote(redis, sess, e, now, { kind, project, title, body: e.body || "" }, [["HSET", sKey, "last", now, ...(e.sessionTitle ? ["title", e.sessionTitle] : [])]]);
    return;
  }

  // Account-wide wall-clock active time: parallel sessions don't double count.
  const lastAny = Number(lastAnyRaw || 0);
  const gap = now - lastAny;
  const activeAdd = lastAny && dayKey(lastAny) === day && gap > 0 && gap <= IDLE_GAP_MS ? Math.round(gap / 1000) : 0;

  const fields = [
    "state", STATE_FOR[e.event],
    "activity", e.activity || DEFAULT_ACTIVITY[e.event],
    "last", now,
  ];
  // The project is where the session started; later `cd`s into subfolders don't rename it.
  if (isNew) fields.push("started", now, "project", e.project);
  if (e.host) fields.push("host", e.host);
  if (e.model) fields.push("model", e.model);
  if (e.branch) fields.push("branch", e.branch);
  if (e.sessionTitle) fields.push("title", e.sessionTitle);
  if (e.env) fields.push("env", e.env);
  if (e.event === "UserPromptSubmit" || e.event === "SessionEnd") fields.push("ask", ""); // answered locally / gone
  if (e.event === "UserPromptSubmit") fields.push("turnStart", now);
  if (e.event === "PreToolUse") fields.push("lastTool", e.activity || e.tool);

  const cmds = [
    ["HSET", sKey, ...fields],
    ["EXPIRE", sKey, SESSION_TTL_S],
    ["SET", K.lastAny, now],
  ];
  const dKey = K.day(day);
  if (activeAdd) cmds.push(["HINCRBY", dKey, "activeSec", activeAdd], ["HINCRBY", dKey, `h${hourOf(now)}`, activeAdd]);
  if (isNew) cmds.push(["HINCRBY", dKey, "sessions", 1]);
  if (e.event === "UserPromptSubmit") {
    cmds.push(["HINCRBY", sKey, "prompts", 1], ["HINCRBY", dKey, "prompts", 1]);
  }
  if (e.event === "PreToolUse") {
    cmds.push(["HINCRBY", sKey, "tools", 1], ["HINCRBY", dKey, "tools", 1]);
    if (e.edit) cmds.push(["HINCRBY", sKey, "edits", 1], ["HINCRBY", dKey, "edits", 1]);
  }
  cmds.push(["EXPIRE", dKey, DAY_TTL_S]);
  cmds.push(e.event === "SessionEnd" ? ["ZREM", K.active, e.session_id] : ["ZADD", K.active, now, e.session_id]);

  const note = noteFor(e, sess, project, now);
  if (note) await pushNote(redis, sess, e, now, note, cmds);
  else await redis.pipeline(cmds);
}

/** Decide whether an event deserves a TV notification. */
function noteFor(e, sess, project, now) {
  if (e.event === "Notification") {
    const permission = /permission/i.test(e.activity);
    const question = /question/i.test(e.activity);
    return {
      kind: permission ? "permission" : question ? "question" : "reply",
      project,
      title: permission ? `${project} needs your OK` : question ? `${project} has a question` : `${project} is waiting for you`,
      body: permission && sess.lastTool ? `Approve: ${sess.lastTool}` : e.activity,
    };
  }
  if (e.event === "PreToolUse" && e.tool === "AskUserQuestion") {
    return { kind: "question", project, title: `${project} has a question`, body: "Answer in Claude Code" };
  }
  if (e.event === "Stop") {
    if (e.ship) {
      return { kind: "ship", project, title: `${project} is ready to ${SHIP_VERB[e.ship]}`, body: "Waiting for your go-ahead" };
    }
    const started = Number(sess.turnStart || 0);
    if (started && now - started >= LONG_TURN_MS) {
      return { kind: "done", project, title: `${project} finished`, body: `Worked for ${fmtDur(now - started)} — ready for review` };
    }
  }
  return null;
}

async function pushNote(redis, sess, e, now, note, cmds) {
  const sKey = K.session(e.session_id);
  const dupe = sess[`note:${note.kind}`] && now - Number(sess[`note:${note.kind}`]) < NOTE_DEDUPE_MS;
  if (!dupe) {
    const id = `${now.toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
    const payload = JSON.stringify({ id, at: now, ...note });
    cmds.push(["LPUSH", K.notes, payload], ["LTRIM", K.notes, 0, 29], ["EXPIRE", K.notes, DAY_TTL_S],
      ["HSET", sKey, `note:${note.kind}`, now], ["EXPIRE", sKey, SESSION_TTL_S]);
  }
  await redis.pipeline(cmds);
}

async function applyUsage(redis, e, now) {
  const sKey = K.session(e.session_id);
  const cmds = [];
  const sf = [];
  if (e.modelName) sf.push("modelName", e.modelName);
  if (e.contextPct !== null) sf.push("contextPct", e.contextPct);
  if (e.linesAdded !== null) sf.push("linesAdded", e.linesAdded);
  if (e.linesRemoved !== null) sf.push("linesRemoved", e.linesRemoved);
  if (e.costUsd !== null) sf.push("costUsd", e.costUsd);
  if (e.branch) sf.push("branch", e.branch);
  if (e.sessionTitle) sf.push("title", e.sessionTitle);
  if (sf.length) cmds.push(["HSET", sKey, ...sf], ["EXPIRE", sKey, SESSION_TTL_S]);
  // Plan usage is account-wide: latest report wins, but cswap (all accounts, always polled)
  // takes precedence over statusline reports for 10 minutes.
  if (e.fiveHour || e.sevenDay) {
    let accept = true;
    if (e.source !== "cswap") {
      const [src, at] = await redis.pipeline([["HGET", K.usage, "src"], ["HGET", K.usage, "at"]]);
      accept = !(src === "cswap" && now - Number(at || 0) < 10 * 60 * 1000);
    }
    if (accept) {
      const uf = ["at", now, "src", e.source];
      uf.push("fh", e.fiveHour ? e.fiveHour.pct : "", "fhr", e.fiveHour?.resetsAt ?? "");
      uf.push("sd", e.sevenDay ? e.sevenDay.pct : "", "sdr", e.sevenDay?.resetsAt ?? "");
      uf.push("extra", JSON.stringify(e.source === "cswap" ? e.usageExtra : {}));
      cmds.push(["HSET", K.usage, ...uf], ["EXPIRE", K.usage, DAY_TTL_S]);
    }
  }
  if (cmds.length) await redis.pipeline(cmds);
}

const RANK = { waiting: 4, working: 3, ready: 2, idle: 1, away: 0 };

export async function readStatus(redis, now = Date.now()) {
  const day = dayKey(now);
  const [, ids, dayFlat, lastAnyRaw, usageFlat, notesRaw, awayRaw] = await redis.pipeline([
    ["ZREMRANGEBYSCORE", K.active, "-inf", now - DROP_MS],
    ["ZRANGEBYSCORE", K.active, now - DROP_MS, "+inf"],
    ["HGETALL", K.day(day)],
    ["GET", K.lastAny],
    ["HGETALL", K.usage],
    ["LRANGE", K.notes, 0, 19],
    ["GET", K.away],
  ]);
  const flats = ids.length ? await redis.pipeline(ids.map((id) => ["HGETALL", K.session(id)])) : [];
  const numOrNull = (v) => (v === undefined || v === "" ? null : Number(v));

  const sessions = flats
    .map((flat, i) => ({ id: ids[i], ...toObject(flat) }))
    .filter((s) => s.started)
    .map((s) => {
      const last = Number(s.last);
      const stale = now - last > STALE_MS;
      return {
        id: s.id.slice(0, 8),
        project: s.project,
        title: s.title || null,
        env: s.env || null,
        host: s.host || null,
        model: s.modelName || prettyModel(s.model),
        branch: s.branch || null,
        state: stale ? "away" : s.state,
        activity: stale ? "Idle" : s.activity,
        startedAt: Number(s.started),
        lastAt: last,
        turnStartedAt: s.state === "working" && s.turnStart ? Number(s.turnStart) : null,
        prompts: Number(s.prompts || 0),
        tools: Number(s.tools || 0),
        edits: Number(s.edits || 0),
        contextPct: numOrNull(s.contextPct),
        linesAdded: numOrNull(s.linesAdded),
        linesRemoved: numOrNull(s.linesRemoved),
        costUsd: numOrNull(s.costUsd),
        ask: !stale ? parseAsk(s.ask) : null,
      };
    })
    .sort((a, b) => RANK[b.state] - RANK[a.state] || b.lastAt - a.lastAt);

  const top = sessions[0];
  const state = top ? top.state : "offline";
  const d = toObject(dayFlat);
  const live = sessions.filter((s) => s.state !== "away").length;

  return {
    state,                                  // working | waiting | ready | idle | away | offline
    headline: headline(state, top, live),
    sessions,
    usage: usageOf(toObject(usageFlat), now),
    notifications: (notesRaw || [])
      .map((raw) => { try { return JSON.parse(raw); } catch { return null; } })
      .filter((n) => n && now - n.at <= NOTE_WINDOW_MS),
    today: {
      date: day,
      activeMinutes: Math.round(Number(d.activeSec || 0) / 60),
      prompts: Number(d.prompts || 0),
      tools: Number(d.tools || 0),
      edits: Number(d.edits || 0),
      sessions: Number(d.sessions || 0),
      // active minutes per hour of the day (local TZ), for the sparkline
      hours: Array.from({ length: 24 }, (_, h) => Math.round(Number(d[`h${h}`] || 0) / 60)),
    },
    away: awayRaw === "1",
    lastActivityAt: lastAnyRaw ? Number(lastAnyRaw) : null,
    serverTime: now,
    tz: TZ,
  };
}

/** Plan usage windows; a window whose reset time has passed is reported as 0 % (fresh window). */
function usageOf(u, now) {
  if (!u.at) return null;
  const win = (p, r) => {
    if (u[p] === undefined || u[p] === "") return null;
    const resetsAt = u[r] ? Number(u[r]) * 1000 : null;
    const expired = resetsAt !== null && resetsAt <= now;
    return { pct: expired ? 0 : Number(u[p]), resetsAt: expired ? null : resetsAt };
  };
  let extra = {};
  try { extra = JSON.parse(u.extra || "{}") || {}; } catch { extra = {}; }
  const out = {
    fiveHour: win("fh", "fhr"),
    sevenDay: win("sd", "sdr"),
    source: u.src || "statusline",
    account: extra.account || null,
    weekPace: extra.weekPace || null,
    scoped: extra.scoped || [],
    // Every cswap account; windows past their reset read 0 %, times in ms.
    pool: (extra.pool || []).map((p) => {
      const fhExpired = p.fhr && p.fhr * 1000 <= now, sdExpired = p.sdr && p.sdr * 1000 <= now;
      return {
        ...p,
        fh: fhExpired ? 0 : p.fh, fhr: fhExpired || !p.fhr ? null : p.fhr * 1000,
        sd: sdExpired ? 0 : p.sd, sdr: sdExpired || !p.sdr ? null : p.sdr * 1000,
        env: p.env || [],
      };
    }),
    updatedAt: Number(u.at),
  };
  return out.fiveHour || out.sevenDay ? out : null;
}

/** claude-opus-5-5 -> "Opus 5.5" (fallback when the statusline hasn't reported a display name yet). */
export function prettyModel(id) {
  if (!id) return null;
  const m = /claude-([a-z]+)-(\d+)(?:-(\d+))?/i.exec(id);
  if (!m) return id;
  return `${m[1][0].toUpperCase()}${m[1].slice(1)} ${m[2]}${m[3] && m[3].length <= 2 ? `.${m[3]}` : ""}`;
}

function fmtDur(ms) {
  const m = Math.max(1, Math.round(ms / 60000));
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}

function headline(state, top, live) {
  if (top) top = { ...top, project: top.title || top.project };
  const more = live > 1 ? ` · ${live} sessions` : "";
  switch (state) {
    case "working": return `Coding on ${top.project}${more}`;
    case "waiting": return `${top.project} needs input${more}`;
    case "ready": return `Paused on ${top.project}${more}`;
    case "idle": return `Session open on ${top.project}${more}`;
    case "away": return "Away from the keyboard";
    default: return "No active sessions";
  }
}

function parseAsk(raw) {
  if (!raw) return null;
  try {
    const a = JSON.parse(raw);
    return a && a.requestId ? a : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- TV → session control

export async function setAway(redis, on) {
  await redis.pipeline([on ? ["SET", K.away, "1"] : ["DEL", K.away]]);
  return { away: !!on };
}

/**
 * The TV picked an option for a pending question. `session` may be the 8-char id prefix the
 * status feed exposes. Only an option that the waiting hook offered is accepted.
 */
export async function postReply(redis, { session, requestId, option }, now = Date.now()) {
  const prefix = clip(session, 100);
  if (!/^[A-Za-z0-9_-]{4,100}$/.test(prefix)) return { error: "bad session" };
  const [ids] = await redis.pipeline([["ZRANGEBYSCORE", K.active, now - DROP_MS, "+inf"]]);
  const matches = ids.filter((id) => id.startsWith(prefix));
  if (matches.length !== 1) return { error: matches.length ? "ambiguous session" : "unknown session" };
  const id = matches[0];
  const [askRaw] = await redis.pipeline([["HGET", K.session(id), "ask"]]);
  const ask = parseAsk(askRaw);
  if (!ask || ask.requestId !== requestId) return { error: "nothing pending" };
  if (!ask.options.some((o) => o.id === option)) return { error: "unknown option" };
  await redis.pipeline([
    ["SET", K.reply(id), JSON.stringify({ requestId, option, at: now }), "EX", 3600],
    ["HSET", K.session(id), "ask", "", "activity", "Reply sent from TV", "last", now],
  ]);
  return { ok: true };
}

/** Polled by the waiting hook: away flag, whether its question is still pending, and any reply (consumed). */
export async function pollControl(redis, sessionId, requestId) {
  const [awayRaw, replyRaw, askRaw] = await redis.pipeline([
    ["GET", K.away], ["GET", K.reply(sessionId)], ["HGET", K.session(sessionId), "ask"],
  ]);
  let reply = null;
  if (replyRaw) {
    try { reply = JSON.parse(replyRaw); } catch { reply = null; }
    if (reply && reply.requestId === requestId) await redis.pipeline([["DEL", K.reply(sessionId)]]);
    else reply = null;
  }
  const ask = parseAsk(askRaw);
  return { away: awayRaw === "1", reply, pending: !!(ask && ask.requestId === requestId) };
}
