#!/usr/bin/env python3
"""Push Claude plan usage (from cswap's cache) to the TV status API.

Runs `cswap list --json` (cswap already polls each account's usage), and sends only
percentages, reset times, pace and an account label — never emails, org names or tokens.
Meant to run every ~2 minutes from a systemd user timer (see systemd/ next to this file).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tv_status_hook import load_config, log  # noqa: E402

STATE = os.path.expanduser("~/.cache/attic-tv-usage.json")
RESEND_S = 300


def epoch(iso):
    if not iso:
        return None
    try:
        return int(datetime.fromisoformat(str(iso).replace("Z", "+00:00")).timestamp())
    except ValueError:
        return None


def pct(v):
    return round(float(v), 1) if isinstance(v, (int, float)) else None


def win(w):
    if not isinstance(w, dict) or pct(w.get("pct")) is None:
        return None
    return {"pct": pct(w["pct"]), "resetsAt": epoch(w.get("resetsAt"))}


def login_files():
    """Which Claude Code install is signed into which account (matched by email, locally only).
    `wsl` = this machine's ~/.claude.json. On WSL, set TV_STATUS_WINDOWS_CLAUDE_JSON in tv_status.env
    (e.g. /mnt/c/Users/<you>/.claude.json) so sessions from the Windows-side app map to their account."""
    files = {"wsl": os.path.expanduser("~/.claude.json")}
    win = load_config().get("TV_STATUS_WINDOWS_CLAUDE_JSON")
    if win:
        files["windows"] = win
    return files


def logins_by_email():
    out = {}
    for env, path in login_files().items():
        try:
            with open(path) as f:
                em = ((json.load(f).get("oauthAccount") or {}).get("emailAddress") or "").lower()
            if em:
                out.setdefault(em, []).append(env)
        except (OSError, ValueError):
            pass
    return out


def label(a):
    return (a.get("alias") or f"Account {a.get('number', '?')}")[:24]


def pool_entry(a, envs):
    u = a.get("usage") or {}
    fh, sd = win(u.get("fiveHour")), win(u.get("sevenDay"))
    sdr = u.get("sevenDay") or {}
    return {
        "label": label(a),
        "number": a.get("number"),
        "fh": fh["pct"] if fh else None, "fhr": fh["resetsAt"] if fh else None,
        "sd": sd["pct"] if sd else None, "sdr": sd["resetsAt"] if sd else None,
        "ahead": bool(sdr.get("aheadOfPace")), "willLast": sdr.get("willLastToReset") is not False,
        "active": bool(a.get("active")),
        "ok": a.get("usageStatus") == "ok",
        "env": envs.get((a.get("email") or "").lower(), []),  # e.g. ["windows"]: logged in there
    }


def build(data):
    accounts = data.get("accounts") or []
    envs = logins_by_email()
    active = next((a for a in accounts if a.get("active")), None) or \
        next((a for a in accounts if a.get("number") == data.get("activeAccountNumber")), None)
    if not active:
        return None
    u = active.get("usage") or {}
    sd = u.get("sevenDay") or {}
    return {
        "event": "Usage",
        "session_id": "cswap-usage",
        "source": "cswap",
        "fiveHour": win(u.get("fiveHour")),
        "sevenDay": win(sd),
        "account": {
            "label": label(active),
            "plan": ((active.get("plan") or {}).get("label") or "")[:20],
            "number": active.get("number"),
            "of": len(accounts),
        },
        "weekPace": {
            "expectedPct": pct(sd.get("expectedPct")),
            "aheadOfPace": bool(sd.get("aheadOfPace")),
            "willLastToReset": sd.get("willLastToReset") is not False,
        } if sd else None,
        "scoped": [{"name": str(s.get("name", ""))[:24], "pct": pct(s.get("pct"))}
                   for s in (u.get("scoped") or []) if pct(s.get("pct")) is not None][:3],
        "pool": [pool_entry(a, envs) for a in accounts][:8],
    }


def main():
    cfg = load_config()
    if not cfg.get("TV_STATUS_URL") or not cfg.get("TV_STATUS_TOKEN"):
        return
    try:
        out = subprocess.run(["cswap", "list", "--json"], capture_output=True, text=True, timeout=90,
                             env={**os.environ, "PATH": os.environ.get("PATH", "") + ":" + os.path.expanduser("~/.local/bin")})
        ev = build(json.loads(out.stdout))
    except (OSError, ValueError, subprocess.SubprocessError) as e:
        log(f"usage: cswap failed: {e}")
        return
    if not ev:
        return
    if os.environ.get("TV_STATUS_DRY_RUN") == "1":
        print(json.dumps(ev, indent=1))
        return
    sig = json.dumps({k: ev[k] for k in ("fiveHour", "sevenDay", "account", "pool")}, sort_keys=True)  # pool includes env
    try:
        with open(STATE) as f:
            st = json.load(f)
    except (OSError, ValueError):
        st = {}
    if st.get("sig") == sig and time.time() - st.get("at", 0) < RESEND_S:
        return
    req = urllib.request.Request(cfg["TV_STATUS_URL"], data=json.dumps(ev).encode(), method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {cfg['TV_STATUS_TOKEN']}",
                                          "User-Agent": "attic-tv-usage/1"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        with open(STATE, "w") as f:
            json.dump({"sig": sig, "at": time.time()}, f)
    except Exception as e:  # noqa: BLE001
        log(f"usage post failed: {e}")


if __name__ == "__main__":
    main()
