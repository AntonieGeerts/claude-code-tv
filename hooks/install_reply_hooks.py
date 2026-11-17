#!/usr/bin/env python3
"""Add the "answer from the TV" hooks to ~/.claude/settings.json (run this yourself).

  python3 install_reply_hooks.py                 # replies to finished turns + away-mode approvals/questions
  python3 install_reply_hooks.py --no-approvals  # replies to finished turns only (no remote Allow/Deny)
  python3 install_reply_hooks.py --remove        # take them out again

What each hook does (all run hooks/tv_reply_wait.py):
  Stop (async, asyncRewake)      when a turn ends with a question / offer to deploy or push, wait in the
                                 background for a TV preset; the reply wakes the session. Never blocks typing.
  PermissionRequest              ONLY while Away mode is on (bell panel on the TV): Allow/Deny from the TV.
                                 Away off → returns instantly and the normal dialog shows. Deny rules still win.
  PreToolUse(AskUserQuestion)    ONLY while Away mode is on: answer Claude's question from the TV.
A backup of settings.json is written next to it first.
"""
import json
import os
import shutil
import sys
import time

SETTINGS = os.path.expanduser("~/.claude/settings.json")
CMD = f"{sys.executable or '/usr/bin/python3'} {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tv_reply_wait.py')}"


def main():
    args = set(sys.argv[1:])
    with open(SETTINGS) as f:
        s = json.load(f)
    backup = f"{SETTINGS}.bak-attic-tv-{time.strftime('%Y%m%d%H%M%S')}"
    shutil.copy2(SETTINGS, backup)
    hooks = s.setdefault("hooks", {})

    # Remove any previous copies first (idempotent).
    for ev in list(hooks):
        hooks[ev] = [g for g in hooks[ev] if not any("tv_reply_wait.py" in h.get("command", "") for h in g.get("hooks", []))]
        if not hooks[ev]:
            del hooks[ev]

    if "--remove" not in args:
        hooks.setdefault("Stop", []).append(
            {"hooks": [{"type": "command", "command": CMD, "async": True, "asyncRewake": True, "timeout": 1800}]})
        if "--no-approvals" not in args:
            hooks.setdefault("PermissionRequest", []).append({"hooks": [{"type": "command", "command": CMD, "timeout": 900}]})
            hooks.setdefault("PreToolUse", []).append(
                {"matcher": "AskUserQuestion", "hooks": [{"type": "command", "command": CMD, "timeout": 900}]})

    tmp = SETTINGS + ".tmp"
    with open(tmp, "w") as f:
        json.dump(s, f, indent=2)
        f.write("\n")
    with open(tmp) as f:
        json.load(f)
    os.replace(tmp, SETTINGS)
    action = "removed" if "--remove" in args else ("installed (turn replies only)" if "--no-approvals" in args else "installed")
    print(f"TV reply hooks {action}. Backup: {backup}")
    print("Start a new Claude Code session (or run /hooks) for them to take effect.")


if __name__ == "__main__":
    main()
