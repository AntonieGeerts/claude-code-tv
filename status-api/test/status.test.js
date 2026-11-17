import { test } from "node:test";
import assert from "node:assert/strict";
import { memoryClient } from "../lib/redis.js";
import { applyEvent, parseEvent, readStatus } from "../lib/status.js";

const T0 = Date.parse("2026-11-17T02:00:00Z"); // 10:00 in STATUS_TZ (UTC+8, set by npm test)
const min = 60_000;
const ev = (event, extra = {}) => parseEvent({ event, session_id: "sess-aaaa1111", project: "AndroidTV", host: "wsl", ...extra });

test("parseEvent rejects junk and unknown events", () => {
  assert.equal(parseEvent(null), null);
  assert.equal(parseEvent({ event: "Bogus", session_id: "abcd1234" }), null);
  assert.equal(parseEvent({ event: "Stop", session_id: "bad id with spaces" }), null);
  const e = parseEvent({ event: "Stop", session_id: "abcd1234", project: "x".repeat(500), edit: "yes" });
  assert.equal(e.project.length, 60);
  assert.equal(e.edit, false);
});

test("offline when nothing has happened", async () => {
  const s = await readStatus(memoryClient(), T0);
  assert.equal(s.state, "offline");
  assert.equal(s.sessions.length, 0);
});

test("session lifecycle drives state, counters and active time", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("SessionStart"), T0);
  assert.equal((await readStatus(r, T0)).state, "idle");

  await applyEvent(r, ev("UserPromptSubmit"), T0 + 1 * min);
  await applyEvent(r, ev("PreToolUse", { tool: "Edit", activity: "Editing HomeActivity.java", edit: true }), T0 + 2 * min);
  let s = await readStatus(r, T0 + 2 * min);
  assert.equal(s.state, "working");
  assert.equal(s.sessions[0].activity, "Editing HomeActivity.java");
  assert.match(s.headline, /Coding on AndroidTV/);

  await applyEvent(r, ev("Notification", { activity: "Needs permission" }), T0 + 3 * min);
  assert.equal((await readStatus(r, T0 + 3 * min)).state, "waiting");

  await applyEvent(r, ev("Stop"), T0 + 4 * min);
  s = await readStatus(r, T0 + 4 * min);
  assert.equal(s.state, "ready");
  assert.deepEqual(
    { prompts: s.today.prompts, tools: s.today.tools, edits: s.today.edits, sessions: s.today.sessions, activeMinutes: s.today.activeMinutes },
    { prompts: 1, tools: 1, edits: 1, sessions: 1, activeMinutes: 4 },
  );

  // A 20-minute gap is not counted as active time.
  await applyEvent(r, ev("UserPromptSubmit"), T0 + 24 * min);
  assert.equal((await readStatus(r, T0 + 24 * min)).today.activeMinutes, 4);

  await applyEvent(r, ev("SessionEnd"), T0 + 25 * min);
  s = await readStatus(r, T0 + 25 * min);
  assert.equal(s.state, "offline");
  assert.equal(s.today.sessions, 1);
});

test("parallel sessions: busiest wins, active time is not double counted", async () => {
  const r = memoryClient();
  const a = (e, x) => parseEvent({ event: e, session_id: "aaaa0001", project: "launcher", ...x });
  const b = (e, x) => parseEvent({ event: e, session_id: "bbbb0002", project: "status-api", ...x });
  await applyEvent(r, a("UserPromptSubmit"), T0);
  await applyEvent(r, b("UserPromptSubmit"), T0 + 1 * min);
  await applyEvent(r, a("Stop"), T0 + 2 * min);
  await applyEvent(r, b("PreToolUse", { tool: "Bash", activity: "Running npm" }), T0 + 3 * min);
  const s = await readStatus(r, T0 + 3 * min);
  assert.equal(s.state, "working");
  assert.equal(s.sessions[0].project, "status-api");
  assert.equal(s.sessions.length, 2);
  assert.match(s.headline, /2 sessions/);
  assert.equal(s.today.activeMinutes, 3);
});

test("a session waiting on you outranks one that is working", async () => {
  const r = memoryClient();
  await applyEvent(r, parseEvent({ event: "PreToolUse", session_id: "aaaa0001", project: "launcher", activity: "Editing x" }), T0);
  await applyEvent(r, parseEvent({ event: "Notification", session_id: "bbbb0002", project: "billing-api", activity: "Needs your permission" }), T0 + min);
  await applyEvent(r, parseEvent({ event: "PreToolUse", session_id: "aaaa0001", project: "launcher", activity: "Editing y" }), T0 + 2 * min);
  const s = await readStatus(r, T0 + 2 * min);
  assert.equal(s.state, "waiting");
  assert.equal(s.sessions[0].project, "billing-api");
  assert.match(s.headline, /billing-api needs input/);
});

test("usage events feed plan usage and session details without changing state", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("PreToolUse", { tool: "Edit", activity: "Editing a.js", edit: true }), T0);
  const resets5h = Math.floor((T0 + 2 * 60 * min) / 1000);
  await applyEvent(r, parseEvent({
    event: "Usage", session_id: "sess-aaaa1111", modelName: "Opus 5.5", contextPct: 38.44, linesAdded: 120, linesRemoved: 7,
    fiveHour: { pct: 64.2, resetsAt: resets5h }, sevenDay: { pct: 23, resetsAt: resets5h + 86400 },
  }), T0 + min);
  let s = await readStatus(r, T0 + min);
  assert.equal(s.state, "working");
  assert.equal(s.sessions[0].model, "Opus 5.5");
  assert.equal(s.sessions[0].contextPct, 38.4);
  assert.equal(s.sessions[0].linesAdded, 120);
  assert.deepEqual(s.usage.fiveHour, { pct: 64.2, resetsAt: resets5h * 1000 });
  assert.equal(s.usage.sevenDay.pct, 23);
  // After the 5-hour window resets it reads 0 %.
  s = await readStatus(r, T0 + 3 * 60 * min);
  assert.deepEqual(s.usage.fiveHour, { pct: 0, resetsAt: null });
});

test("notifications: permission (with what to approve), ship-ready, long turn, shipped; deduped", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("UserPromptSubmit"), T0);
  await applyEvent(r, ev("PreToolUse", { tool: "Bash", activity: "Running git push" }), T0 + min);
  await applyEvent(r, ev("Notification", { activity: "Needs your permission" }), T0 + min);
  await applyEvent(r, ev("Notification", { activity: "Needs your permission" }), T0 + min + 1000); // dupe
  await applyEvent(r, parseEvent({ event: "Shipped", session_id: "sess-aaaa1111", activity: "Pushed main" }), T0 + 2 * min);
  await applyEvent(r, ev("Stop", { ship: "deploy" }), T0 + 4 * min);
  await applyEvent(r, ev("UserPromptSubmit"), T0 + 10 * min);
  await applyEvent(r, ev("Stop"), T0 + 15 * min); // 5-minute turn -> "finished"
  await applyEvent(r, ev("UserPromptSubmit"), T0 + 16 * min);
  await applyEvent(r, ev("Stop"), T0 + 17 * min); // short turn -> nothing
  const s = await readStatus(r, T0 + 17 * min);
  const kinds = s.notifications.map((n) => n.kind);
  assert.deepEqual(kinds, ["done", "ship", "shipped", "permission"]);
  assert.equal(s.notifications[3].body, "Approve: Running git push");
  assert.equal(s.notifications[3].title, "AndroidTV needs your OK");
  assert.equal(s.notifications[2].title, "Pushed main · AndroidTV");
  assert.equal(s.notifications[1].title, "AndroidTV is ready to deploy");
  assert.match(s.notifications[0].body, /Worked for 5 min/);
  assert.ok(new Set(s.notifications.map((n) => n.id)).size === 4);
  // Old notifications age out of the feed.
  assert.equal((await readStatus(r, T0 + 60 * min)).notifications.length, 0);
});

test("prettyModel", async () => {
  const { prettyModel } = await import("../lib/status.js");
  assert.equal(prettyModel("claude-opus-5-5"), "Opus 5.5");
  assert.equal(prettyModel("claude-sonnet-5-5"), "Sonnet 5.5");
  assert.equal(prettyModel(null), null);
});

test("stale sessions show as away, very old ones are dropped", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("Stop"), T0);
  const away = await readStatus(r, T0 + 45 * min);
  assert.equal(away.state, "away");
  assert.equal(away.sessions[0].activity, "Idle");
  const gone = await readStatus(r, T0 + 7 * 60 * min);
  assert.equal(gone.state, "offline");
});

test("day rollover resets daily stats (midnight in STATUS_TZ)", async () => {
  const r = memoryClient();
  const lateNight = Date.parse("2026-11-17T15:58:00Z"); // 23:58 UTC+8
  await applyEvent(r, ev("UserPromptSubmit"), lateNight);
  await applyEvent(r, ev("PreToolUse", { tool: "Read" }), lateNight + 4 * min); // 00:02 next day
  const s = await readStatus(r, lateNight + 4 * min);
  assert.equal(s.today.date, "2026-11-18");
  assert.equal(s.today.prompts, 0);
  assert.equal(s.today.tools, 1);
  assert.equal(s.today.activeMinutes, 0);
});

test("cswap usage wins over statusline usage and carries account/pool extras", async () => {
  const r = memoryClient();
  await applyEvent(r, parseEvent({
    event: "Usage", session_id: "cswap-usage", source: "cswap",
    fiveHour: { pct: 14, resetsAt: Math.floor((T0 + 3600e3) / 1000) }, sevenDay: { pct: 28, resetsAt: null },
    account: { label: "Account 2", plan: "Team", number: 2, of: 5, email: "x@y.z" },
    weekPace: { expectedPct: 54.7, aheadOfPace: false, willLastToReset: true },
    scoped: [{ name: "Fable", pct: 37 }],
    pool: [{ label: "Account 1", fh: 14, sd: 28, active: false, ok: true }, { label: "Account 2", fh: 0, sd: 0, active: true }],
  }), T0);
  await applyEvent(r, parseEvent({ event: "Usage", session_id: "sess-aaaa1111", fiveHour: { pct: 99 }, contextPct: 12 }), T0 + min);
  const s = await readStatus(r, T0 + min);
  assert.equal(s.usage.fiveHour.pct, 14);
  assert.equal(s.usage.source, "cswap");
  assert.deepEqual(s.usage.account, { label: "Account 2", plan: "Team", number: 2, of: 5 });
  assert.equal(s.usage.pool.length, 2);
  assert.equal(s.usage.scoped[0].name, "Fable");
  assert.ok(!JSON.stringify(s).includes("x@y.z"));
  // After 10 minutes without cswap, statusline data is accepted again.
  await applyEvent(r, parseEvent({ event: "Usage", session_id: "sess-aaaa1111", fiveHour: { pct: 50 } }), T0 + 11 * min);
  assert.equal((await readStatus(r, T0 + 11 * min)).usage.fiveHour.pct, 50);
});

test("TV replies: ask → reply (only offered options) → consumed once; local prompt clears the ask; away flag", async () => {
  const { postReply, pollControl, setAway } = await import("../lib/status.js");
  const r = memoryClient();
  await applyEvent(r, ev("Stop", { ship: "deploy" }), T0);
  await applyEvent(r, parseEvent({
    event: "Ask", session_id: "sess-aaaa1111", requestId: "req123456", askKind: "turn", title: "Ready to deploy?",
    options: [{ id: "yes", label: "Yes" }, { id: "push-dev", label: "Push & Commit to Dev" }, { id: "bad id!", label: "x" }],
  }), T0 + 1000);
  let s = await readStatus(r, T0 + 2000);
  assert.deepEqual(s.sessions[0].ask.options.map((o) => o.id), ["yes", "push-dev"]);
  assert.equal(s.away, false);

  assert.equal((await postReply(r, { session: "sess-aaa", requestId: "req123456", option: "rm-rf" }, T0 + 3000)).error, "unknown option");
  assert.equal((await postReply(r, { session: "sess-aaa", requestId: "wrong0000", option: "yes" }, T0 + 3000)).error, "nothing pending");
  assert.ok((await postReply(r, { session: "sess-aaa", requestId: "req123456", option: "push-dev" }, T0 + 3000)).ok);
  assert.equal((await readStatus(r, T0 + 3000)).sessions[0].ask, null);

  let c = await pollControl(r, "sess-aaaa1111", "req123456");
  assert.equal(c.reply.option, "push-dev");
  c = await pollControl(r, "sess-aaaa1111", "req123456");
  assert.equal(c.reply, null); // consumed

  // A second question, answered locally instead → ask cleared, hook sees pending=false.
  await applyEvent(r, parseEvent({ event: "Ask", session_id: "sess-aaaa1111", requestId: "req999999", options: [{ id: "yes", label: "Yes" }] }), T0 + 5000);
  assert.equal((await pollControl(r, "sess-aaaa1111", "req999999")).pending, true);
  await applyEvent(r, ev("UserPromptSubmit"), T0 + 6000);
  assert.equal((await pollControl(r, "sess-aaaa1111", "req999999")).pending, false);

  await setAway(r, true);
  assert.equal((await readStatus(r, T0 + 7000)).away, true);
  assert.equal((await pollControl(r, "sess-aaaa1111", "x")).away, true);
  await setAway(r, false);
  assert.equal((await readStatus(r, T0 + 7000)).away, false);
});

test("session titles: stored, exposed, used in alerts and headline", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("UserPromptSubmit", { sessionTitle: "Projector/TV launcher widget" }), T0);
  await applyEvent(r, ev("PreToolUse", { tool: "Bash", activity: "Running git push" }), T0 + min);
  await applyEvent(r, ev("Notification", { activity: "Needs your permission" }), T0 + min);
  const s = await readStatus(r, T0 + min);
  assert.equal(s.sessions[0].title, "Projector/TV launcher widget");
  assert.equal(s.sessions[0].project, "AndroidTV");
  assert.equal(s.notifications[0].title, "Projector/TV launcher widget needs your OK");
  assert.match(s.headline, /Projector\/TV launcher widget/);
});

test("pool carries per-account resets + login env; sessions carry env", async () => {
  const r = memoryClient();
  await applyEvent(r, ev("UserPromptSubmit", { env: "windows" }), T0);
  const soon = Math.floor((T0 + 30 * min) / 1000);
  await applyEvent(r, parseEvent({
    event: "Usage", session_id: "cswap-usage", source: "cswap", fiveHour: { pct: 0 }, sevenDay: { pct: 0 },
    pool: [
      { label: "Account 1", number: 1, fh: 34, fhr: soon, sd: 31, sdr: soon + 86400, env: ["windows"], ahead: false, willLast: true },
      { label: "Account 2", number: 2, fh: 0, sd: 0, active: true, env: ["wsl", "evil"] },
    ],
  }), T0);
  let s = await readStatus(r, T0 + min);
  assert.equal(s.sessions[0].env, "windows");
  assert.deepEqual(s.usage.pool[0].env, ["windows"]);
  assert.deepEqual(s.usage.pool[1].env, ["wsl"]);
  assert.equal(s.usage.pool[0].fh, 34);
  assert.equal(s.usage.pool[0].fhr, soon * 1000);
  s = await readStatus(r, T0 + 40 * min); // 5-hour window of #1 has reset
  assert.equal(s.usage.pool[0].fh, 0);
  assert.equal(s.usage.pool[0].fhr, null);
});
