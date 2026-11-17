#!/usr/bin/env python3
"""Claude Code statusLine: prints a compact status line AND forwards plan usage to the TV card.

Terminal output, e.g.:
  Opus 5.5 · AndroidTV ⎇ main · ctx 38% · 5h 64% ↻3:40 PM · wk 23%

Forwarded (throttled to one POST per session per 45 s unless values change, sent from a
forked child so the status line never waits on the network):
  rate_limits.five_hour / seven_day (used %, resets_at), context window %, model display name,
  lines added/removed, estimated session cost.

Config: same tv_status.env as tv_status_hook.py. Always exits 0.
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tv_status_hook import load_config, log, project_and_branch  # noqa: E402

STATE = os.path.expanduser("~/.cache/attic-tv-statusline.json")
MIN_INTERVAL_S = 45


def num(v):
    try:
        return None if v is None else round(float(v), 1)
    except (TypeError, ValueError):
        return None


def window(rl, key):
    w = (rl or {}).get(key) or {}
    pct, resets = num(w.get("used_percentage")), w.get("resets_at")
    if pct is None:
        return None
    return {"pct": pct, "resetsAt": int(resets) if isinstance(resets, (int, float)) else None}


def build(h):
    rl = h.get("rate_limits") or {}
    ctx = h.get("context_window") or {}
    cost = h.get("cost") or {}
    ws = h.get("workspace") or {}
    project, branch = project_and_branch(ws.get("project_dir") or h.get("cwd"))
    return {
        "event": "Usage",
        "session_id": h.get("session_id", ""),
        "sessionTitle": (h.get("session_name") or "")[:80],
        "project": project,
        "branch": branch,
        "modelName": ((h.get("model") or {}).get("display_name") or "")[:40],
        "fiveHour": window(rl, "five_hour"),
        "sevenDay": window(rl, "seven_day"),
        "contextPct": num(ctx.get("used_percentage")),
        "linesAdded": cost.get("total_lines_added"),
        "linesRemoved": cost.get("total_lines_removed"),
        "costUsd": num(cost.get("total_cost_usd")),
    }


def line(ev):
    parts = [ev["modelName"] or "Claude"]
    parts.append(ev["project"] + (f" ⎇ {ev['branch']}" if ev["branch"] else ""))
    if ev["contextPct"] is not None:
        parts.append(f"ctx {ev['contextPct']:.0f}%")
    fh, sd = ev["fiveHour"], ev["sevenDay"]
    if fh:
        reset = ""
        if fh.get("resetsAt"):
            reset = " ↻" + time.strftime("%-I:%M %p", time.localtime(fh["resetsAt"]))
        parts.append(f"5h {fh['pct']:.0f}%{reset}")
    if sd:
        parts.append(f"wk {sd['pct']:.0f}%")
    return " · ".join(parts)


def should_send(ev):
    sig = json.dumps([ev["fiveHour"], ev["sevenDay"], ev["contextPct"], ev["modelName"], ev["linesAdded"]], sort_keys=True)
    try:
        with open(STATE) as f:
            state = json.load(f)
    except (OSError, ValueError):
        state = {}
    prev = state.get(ev["session_id"], {})
    now = time.time()
    if prev.get("sig") == sig and now - prev.get("at", 0) < MIN_INTERVAL_S:
        return False
    state = {k: v for k, v in state.items() if now - v.get("at", 0) < 86400}
    state[ev["session_id"]] = {"sig": sig, "at": now}
    try:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        tmp = STATE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.replace(tmp, STATE)
    except OSError:
        pass
    return True


def post(ev, cfg):
    req = urllib.request.Request(
        cfg["TV_STATUS_URL"], data=json.dumps(ev).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {cfg['TV_STATUS_TOKEN']}",
                 "User-Agent": "attic-tv-statusline/1"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            r.read()
    except Exception as e:  # noqa: BLE001
        log(f"statusline post failed: {e}")


def main():
    try:
        h = json.load(sys.stdin)
    except (ValueError, OSError):
        print("Claude")
        return
    ev = build(h)
    print(line(ev))
    sys.stdout.flush()
    cfg = load_config()
    if os.environ.get("TV_STATUS_DRY_RUN") == "1":
        print(json.dumps(ev), file=sys.stderr)
        return
    if not ev["session_id"] or not cfg.get("TV_STATUS_URL") or not cfg.get("TV_STATUS_TOKEN") or not should_send(ev):
        return
    # Detach so Claude Code gets the status line immediately.
    if os.fork() == 0:
        try:
            os.setsid()
            devnull = os.open(os.devnull, os.O_RDWR)
            for fd in (0, 1, 2):
                os.dup2(devnull, fd)
            post(ev, cfg)
        finally:
            os._exit(0)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        log(f"statusline crashed: {e}")
    sys.exit(0)
