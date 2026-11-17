#!/usr/bin/env python3
"""Answer Claude Code from the TV.

Three hook roles (configured in ~/.claude/settings.json, see settings-snippet.json):

  Stop (async + asyncRewake): if Claude's turn ended with a question or an offer to
      deploy/push, publish preset options to the TV and wait (in the background, never blocking
      the terminal). When you pick one on the TV, exit 2 so Claude wakes up with the preset's
      text. If you type a prompt locally first, the wait is abandoned silently.

  PermissionRequest (sync)          only in AWAY mode (toggled from the TV's bell panel):
      wait for Allow / Deny from the TV. Not away → returns immediately (normal dialog).

  PreToolUse AskUserQuestion (sync) only in AWAY mode: show Claude's question + its options on
      the TV and answer it from there. Not away → returns immediately.

The TV only ever sends option ids; the text lives in tv_replies.json on this PC.
"""
import json
import os
import re
import secrets
import socket
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tv_status_hook import describe_tool, load_config, log, project_and_branch, session_title, ship_intent  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
POLL_S = 3
TURN_WAIT_S = 28 * 60
SYNC_WAIT_S = 14 * 60


def presets():
    with open(os.path.join(HERE, "tv_replies.json")) as f:
        return json.load(f)


def api(cfg, method, path, body=None, timeout=8):
    base = cfg["TV_STATUS_URL"].rsplit("/api/", 1)[0]
    req = urllib.request.Request(base + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {cfg['TV_STATUS_TOKEN']}",
                                          "User-Agent": "attic-tv-reply/1"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


def control(cfg, session_id, request_id):
    q = urllib.parse.urlencode({"session": session_id, "request": request_id})
    return api(cfg, "GET", f"/api/control?{q}")


def publish_ask(cfg, h, request_id, kind, title, options, question=""):
    project, branch = project_and_branch(h.get("cwd"))
    api(cfg, "POST", "/api/event", {
        "event": "Ask", "session_id": h["session_id"], "requestId": request_id, "askKind": kind,
        "title": title, "question": question, "project": project, "branch": branch,
        "host": cfg.get("TV_STATUS_HOST") or socket.gethostname(), "sessionTitle": session_title(h),
        "options": [{"id": o["id"], "label": o["label"]} for o in options],
    })


def wait_for_reply(cfg, session_id, request_id, max_s, stop_when_not_away=False):
    """Poll until a reply arrives. Returns the option id, or None (timed out / answered locally)."""
    deadline = time.time() + max_s
    errors = 0
    while time.time() < deadline:
        try:
            c = control(cfg, session_id, request_id)
            errors = 0
            if c.get("reply"):
                return c["reply"]["option"]
            if not c.get("pending"):
                return None  # answered locally / superseded
            if stop_when_not_away and not c.get("away"):
                return None
        except Exception as e:  # noqa: BLE001
            errors += 1
            if errors in (1, 20):
                log(f"reply poll error: {e}")
        time.sleep(POLL_S)
    return None


def is_away(cfg, session_id):
    try:
        return bool(control(cfg, session_id, "none").get("away"))
    except Exception:  # noqa: BLE001
        return False


def ends_with_question(message):
    lines = [l.strip() for l in message.strip().splitlines() if l.strip()]
    tail = " ".join(lines[-3:])[-400:]
    return "?" in tail


# ---------------------------------------------------------------- roles

def on_stop(h, cfg):
    if h.get("stop_hook_active"):
        return 0
    msg = h.get("last_assistant_message") or ""
    ship = ship_intent(msg)
    if not ship and not ends_with_question(msg):
        return 0
    verbs = {"deploy": "deploy", "push": "push", "merge": "merge", "release": "release", "publish": "publish", "pr": "open a PR"}
    title = f"Ready to {verbs.get(ship, ship)}?" if ship else "Claude is asking you something"
    options = presets()["turn"]
    rid = secrets.token_hex(6)
    publish_ask(cfg, h, rid, "turn", title, options)
    choice = wait_for_reply(cfg, h["session_id"], rid, TURN_WAIT_S)
    if not choice:
        return 0
    text = next((o["text"] for o in options if o["id"] == choice), None)
    if not text:
        return 0
    label = next(o["label"] for o in options if o["id"] == choice)
    print(f'The user replied from their TV by choosing "{label}": {text}', file=sys.stderr)
    return 2  # asyncRewake: wake Claude with this reply


def on_permission(h, cfg):
    if not is_away(cfg, h["session_id"]):
        return 0
    activity, _ = describe_tool(h.get("tool_name", ""), h.get("tool_input"))
    options = presets()["permission"]
    rid = secrets.token_hex(6)
    publish_ask(cfg, h, rid, "permission", f"Allow: {activity}?", options)
    choice = wait_for_reply(cfg, h["session_id"], rid, SYNC_WAIT_S, stop_when_not_away=True)
    if choice == "allow":
        decision = {"behavior": "allow"}
    elif choice == "deny":
        decision = {"behavior": "deny", "message": "Denied from the TV — the user is away from the desk. Don't retry; wait for them."}
    else:
        return 0
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}}))
    return 0


def on_ask_user_question(h, cfg):
    if not is_away(cfg, h["session_id"]):
        return 0
    inp = h.get("tool_input") or {}
    questions = inp.get("questions") or []
    if len(questions) != 1:
        return 0  # multi-question forms stay on the PC
    q = questions[0]
    opts = [o.get("label", "") for o in (q.get("options") or [])][:5]
    if not opts or q.get("multiSelect"):
        return 0
    options = [{"id": f"o{i}", "label": label[:40]} for i, label in enumerate(opts)] + [{"id": "wait", "label": "Wait for me"}]
    rid = secrets.token_hex(6)
    publish_ask(cfg, h, rid, "question", q.get("header") or "Question", options, question=q.get("question", "")[:200])
    choice = wait_for_reply(cfg, h["session_id"], rid, SYNC_WAIT_S, stop_when_not_away=True)
    if not choice:
        return 0
    if choice == "wait":
        out = {"hookEventName": "PreToolUse", "permissionDecision": "deny",
               "permissionDecisionReason": "The user is away from the desk and chose 'Wait for me' on their TV. Pause and don't continue until they're back."}
    else:
        label = opts[int(choice[1:])]
        out = {"hookEventName": "PreToolUse", "permissionDecision": "allow",
               "updatedInput": {**inp, "answers": {q.get("question", ""): label}}}
    print(json.dumps({"hookSpecificOutput": out}))
    return 0


def main():
    try:
        h = json.load(sys.stdin)
    except (ValueError, OSError):
        return 0
    cfg = load_config()
    if not cfg.get("TV_STATUS_URL") or not cfg.get("TV_STATUS_TOKEN") or not h.get("session_id"):
        return 0
    ev = h.get("hook_event_name")
    if ev == "Stop":
        return on_stop(h, cfg)
    if ev == "PermissionRequest":
        return on_permission(h, cfg)
    if ev == "PreToolUse" and h.get("tool_name") == "AskUserQuestion":
        return on_ask_user_question(h, cfg)
    return 0


if __name__ == "__main__":
    try:
        code = main()
    except Exception as e:  # noqa: BLE001
        log(f"reply hook crashed: {e}")
        code = 0
    sys.exit(code)
