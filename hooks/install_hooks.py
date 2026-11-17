#!/usr/bin/env python3
"""Install the TV status hooks into Claude Code (run this yourself).

  python3 hooks/install_hooks.py                      # ~/.claude/settings.json: status hooks + statusLine
  python3 hooks/install_hooks.py --windows /mnt/c/Users/<you>/.claude/settings.json
                                                      # also the Windows-side Claude Code (desktop app) from WSL
  python3 hooks/install_hooks.py --usage-timer        # write a systemd --user timer for cswap plan usage
  python3 hooks/install_hooks.py --remove [--windows ...]

The status hooks only report (async, never block). For answering sessions from the TV see
install_reply_hooks.py. Settings files are backed up next to themselves before any change.
"""
import json
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable or "/usr/bin/python3"
HOOK = os.path.join(HERE, "tv_status_hook.py")
STATUSLINE = os.path.join(HERE, "tv_statusline.py")
EVENTS = ["SessionStart", "UserPromptSubmit", "PreToolUse", "SubagentStart", "Notification", "Stop", "SessionEnd", "PostToolUse"]


def hook_groups(cmd, windows=False):
    groups = {}
    for ev in EVENTS:
        h = {"type": "command", "command": cmd}
        if ev == "SessionEnd":
            h["timeout"] = 5          # stays synchronous so the last event is sent before exit
        else:
            h.update({"async": True, "timeout": 10})
        g = {"hooks": [h]}
        if ev == "PostToolUse":
            g["matcher"] = "Bash|PowerShell" if windows else "Bash"
        groups[ev] = g
    return groups


def edit(path, fn):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    s = {}
    if os.path.exists(path):
        with open(path) as f:
            s = json.load(f)
        backup = f"{path}.bak-tv-status-{time.strftime('%Y%m%d%H%M%S')}"
        shutil.copy2(path, backup)
        print(f"  backup: {backup}")
    fn(s)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(s, f, indent=2)
        f.write("\n")
    with open(tmp) as f:
        json.load(f)
    os.replace(tmp, path)


def strip(s):
    hooks = s.get("hooks", {})
    for ev in list(hooks):
        hooks[ev] = [g for g in hooks[ev] if not any("tv_status_hook.py" in h.get("command", "") for h in g.get("hooks", []))]
        if not hooks[ev]:
            del hooks[ev]
    if "tv_statusline.py" in (s.get("statusLine") or {}).get("command", ""):
        del s["statusLine"]


def install(path, cmd, windows):
    def fn(s):
        strip(s)
        if "--remove" in sys.argv:
            return
        hooks = s.setdefault("hooks", {})
        for ev, g in hook_groups(cmd, windows).items():
            hooks.setdefault(ev, []).append(g)
        if not windows and not s.get("statusLine"):
            s["statusLine"] = {"type": "command", "command": f"{PY} {STATUSLINE}", "refreshInterval": 60}
    edit(path, fn)
    print(f"{'removed from' if '--remove' in sys.argv else 'installed in'} {path}")


def usage_timer():
    unit_dir = os.path.expanduser("~/.config/systemd/user")
    os.makedirs(unit_dir, exist_ok=True)
    path_env = os.pathsep.join([os.path.expanduser("~/.local/bin"), "/usr/local/bin", "/usr/bin", "/bin"])
    with open(os.path.join(unit_dir, "tv-status-usage.service"), "w") as f:
        f.write(f"""[Unit]
Description=Push Claude plan usage (cswap) to the TV status API

[Service]
Type=oneshot
ExecStart={PY} {os.path.join(HERE, 'tv_usage_push.py')}
Environment=PATH={path_env}
TimeoutStartSec=120
""")
    with open(os.path.join(unit_dir, "tv-status-usage.timer"), "w") as f:
        f.write("""[Unit]
Description=Every 2 minutes: push Claude plan usage to the TV status API

[Timer]
OnBootSec=1min
OnUnitActiveSec=2min
AccuracySec=15s

[Install]
WantedBy=timers.target
""")
    print(f"wrote {unit_dir}/tv-status-usage.{{service,timer}}. Enable with:")
    print("  systemctl --user daemon-reload && systemctl --user enable --now tv-status-usage.timer")


def main():
    args = sys.argv[1:]
    if "--usage-timer" in args:
        usage_timer()
        return
    install(os.path.expanduser("~/.claude/settings.json"), f"{PY} {HOOK}", windows=False)
    if "--windows" in args:
        i = args.index("--windows")
        win_settings = args[i + 1] if i + 1 < len(args) else None
        if not win_settings:
            sys.exit("--windows needs the path to the Windows settings.json, e.g. /mnt/c/Users/<you>/.claude/settings.json")
        distro = os.environ.get("WSL_DISTRO_NAME", "Ubuntu")
        # Windows Claude Code runs hooks through Git Bash, which rewrites /usr/... paths unless told not to.
        install(win_settings, f"MSYS_NO_PATHCONV=1 wsl.exe -d {distro} -e {PY} {HOOK}", windows=True)
    print("Running sessions pick the change up automatically.")


if __name__ == "__main__":
    main()
