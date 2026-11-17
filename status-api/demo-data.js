// Made-up sessions, usage and alerts for screenshots / trying the TV app without Claude Code.
// Used by `node dev-server.js --demo`.
import { applyEvent, parseEvent } from "./lib/status.js";

export async function seedDemo(redis, now = Date.now()) {
  const min = 60_000;
  const at = (m) => now - m * min;
  const send = (ts, body) => applyEvent(redis, parseEvent(body), ts);

  // A day of activity (for the hourly bars): bursts of events across the morning/afternoon.
  const filler = "demo-history-0001";
  const startOfDay = new Date(now);
  startOfDay.setHours(0, 0, 0, 0);
  for (const [h, minutes] of [[9, 35], [10, 50], [11, 20], [13, 45], [14, 55]]) {
    const base = startOfDay.getTime() + h * 60 * min;
    if (base > now - 10 * min) continue;
    for (let m = 0; m <= minutes; m += 3) {
      await send(base + m * min, { event: "PreToolUse", session_id: filler, project: "notes", tool: "Edit", activity: "Editing", edit: m % 9 === 0 });
    }
  }
  await send(at(30), { event: "SessionEnd", session_id: filler, project: "notes" });

  const s1 = { session_id: "demo-checkout-01", project: "shop-web", branch: "feat/checkout-v2", sessionTitle: "Refactor checkout flow", env: "wsl", host: "workstation" };
  const s2 = { session_id: "demo-e2e-tests-02", project: "api-server", branch: "fix/e2e-timeouts", sessionTitle: "Fix flaky e2e tests", env: "windows", host: "workstation" };
  const s3 = { session_id: "demo-darkmode-03", project: "dashboard", branch: "feat/dark-mode", sessionTitle: "Dark mode for the admin dashboard", env: "wsl", host: "laptop" };

  await send(at(48), { event: "SessionStart", ...s1, model: "claude-opus-5-5" });
  for (let i = 0; i < 6; i++) await send(at(46 - i * 6), { event: "UserPromptSubmit", ...s1 });
  await send(at(9), { event: "UserPromptSubmit", ...s1 });
  await send(at(1), { event: "PreToolUse", ...s1, tool: "Edit", activity: "Editing CheckoutForm.tsx", edit: true });
  await send(now, { event: "Usage", ...s1, modelName: "Opus 5.5", contextPct: 41, linesAdded: 386, linesRemoved: 122 });

  await send(at(35), { event: "SessionStart", ...s2, model: "claude-sonnet-5-5" });
  for (let i = 0; i < 4; i++) await send(at(33 - i * 7), { event: "UserPromptSubmit", ...s2 });
  await send(at(4), { event: "PreToolUse", ...s2, tool: "Bash", activity: "Running npm test" });
  await send(at(2), { event: "Stop", ...s2, ship: "push" });
  await send(at(2), {
    event: "Ask", ...s2, requestId: "demoask0001", askKind: "turn", title: "Ready to push?",
    options: [{ id: "yes", label: "Yes" }, { id: "push-dev", label: "Push & Commit to Dev" }, { id: "wait", label: "Wait" }, { id: "no", label: "No" }],
  });
  await send(now, { event: "Usage", ...s2, modelName: "Sonnet 5.5", contextPct: 63, linesAdded: 94, linesRemoved: 51 });

  await send(at(70), { event: "SessionStart", ...s3, model: "claude-opus-5-5" });
  await send(at(66), { event: "UserPromptSubmit", ...s3 });
  await send(at(21), { event: "PreToolUse", ...s3, tool: "Bash", activity: "Running git push" });
  await send(at(20), { event: "Shipped", ...s3, activity: "Pushed feat/dark-mode" });
  await send(at(18), { event: "Stop", ...s3 });

  const s = (h) => Math.floor((now + h * 3600_000) / 1000);
  await send(now, {
    event: "Usage", session_id: "cswap-usage", source: "cswap",
    fiveHour: { pct: 42, resetsAt: s(2.3) }, sevenDay: { pct: 37, resetsAt: s(70) },
    account: { label: "Account 1", plan: "Max", number: 1, of: 3 },
    weekPace: { expectedPct: 40, aheadOfPace: false, willLastToReset: true },
    pool: [
      { label: "Account 1", number: 1, fh: 42, fhr: s(2.3), sd: 37, sdr: s(70), active: true, ok: true, env: ["wsl"] },
      { label: "Account 2", number: 2, fh: 68, fhr: s(1.1), sd: 58, sdr: s(40), ok: true, env: ["windows"] },
      { label: "Account 3", number: 3, fh: 5, fhr: s(4.6), sd: 81, sdr: s(20), ok: true, willLast: false, env: [] },
    ],
  });
}
