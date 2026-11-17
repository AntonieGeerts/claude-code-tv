#!/usr/bin/env python3
"""Claude Code hook -> attic TV status card.

Reads the hook JSON on stdin, reduces it to a short, privacy-safe activity line
(e.g. "Editing HomeActivity.java", "Running git status") and POSTs it to the
Vercel status API. Prompt text, file contents and full shell commands never
leave this machine unless TV_STATUS_SHARE_PROMPTS=1 (prompt snippet only).

Config (first found wins): env vars, then hooks/tv_status.env next to this
script, then ~/.config/attic-tv/status.env:
  TV_STATUS_URL=https://<project>.vercel.app/api/event
  TV_STATUS_TOKEN=<INGEST_TOKEN>
  TV_STATUS_HOST=<optional label shown on the card, default hostname>
  TV_STATUS_SHARE_PROMPTS=0

Always exits 0 so it can never block or break a Claude Code session.
"""
import json
import os
import re
import shlex
import socket
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILES = [os.path.join(HERE, "tv_status.env"), os.path.expanduser("~/.config/attic-tv/status.env")]
LOG = os.path.expanduser("~/.cache/attic-tv-status.log")
UUIDISH = re.compile(r"^[0-9a-f]{8}-?[0-9a-f]{4}", re.I)
SUBCOMMAND_TOOLS = {"git", "npm", "npx", "pnpm", "yarn", "bun", "gradle", "./gradlew", "gradlew", "docker",
                    "vercel", "adb", "adb.exe", "pip", "uv", "cargo", "go", "make", "kubectl", "gh", "python", "python3", "node"}


def load_config():
    cfg = {}
    for path in CONFIG_FILES:
        try:
            with open(path) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        cfg.setdefault(k.strip(), v.strip().strip('"').strip("'"))
        except OSError:
            pass
    for k, v in os.environ.items():
        if k.startswith("TV_STATUS_") and v:
            cfg[k] = v
    return cfg


def log(msg):
    try:
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        if os.path.exists(LOG) and os.path.getsize(LOG) > 200_000:
            os.replace(LOG, LOG + ".1")
        with open(LOG, "a") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    except OSError:
        pass


def to_wsl_path(path):
    """Windows-side Claude Code reports cwd as \\\\wsl.localhost\\Ubuntu\\var\\www\\x or C:\\Users\\x — map to this WSL's paths."""
    p = str(path or "")
    m = re.match(r"^\\\\wsl(?:\.localhost|\$)\\[^\\]+(\\.*)?$", p, re.I)
    if m:
        return (m.group(1) or "/").replace("\\", "/")
    m = re.match(r"^([A-Za-z]):[\\/](.*)$", p)
    if m:
        return f"/mnt/{m.group(1).lower()}/" + m.group(2).replace("\\", "/")
    return p


def read_git_head(cwd, fallback_top):
    """Branch without running git: handles worktrees made by Windows git, whose .git file points at
    //wsl.localhost/<distro>/... (unreadable for WSL git). Returns (worktree top, branch or '')."""
    d = cwd
    for _ in range(12):
        dot = os.path.join(d, ".git")
        try:
            if os.path.isdir(dot):
                gitdir = dot
            elif os.path.isfile(dot):
                with open(dot) as f:
                    ref = f.read().strip()
                gitdir = to_wsl_path(ref[len("gitdir:"):].strip().replace("/", "\\")) if ref.startswith("gitdir:") else ""
                if gitdir and not os.path.isabs(gitdir):
                    gitdir = os.path.normpath(os.path.join(d, gitdir))
            else:
                gitdir = ""
            if gitdir:
                with open(os.path.join(gitdir, "HEAD")) as f:
                    head = f.read().strip()
                branch = head[len("ref: refs/heads/"):] if head.startswith("ref: refs/heads/") else ""
                return d, branch
        except OSError:
            return fallback_top, ""
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return fallback_top, ""


def project_and_branch(cwd):
    """(project folder name, git branch or '') — one git call."""
    cwd = to_wsl_path(cwd)
    if not cwd:
        return "unknown", ""
    top, branch = cwd, ""
    try:
        out = subprocess.run(["git", "-C", cwd, "rev-parse", "--show-toplevel", "--abbrev-ref", "HEAD"],
                             capture_output=True, text=True, timeout=1.5)
        lines = out.stdout.strip().splitlines()
        if out.returncode == 0 and len(lines) >= 2:
            top, branch = lines[0], ("" if lines[1] == "HEAD" else lines[1])
    except (OSError, subprocess.SubprocessError):
        pass
    if not branch:
        top, branch = read_git_head(cwd, top)
    # Claude worktrees live in <repo>/.claude/worktrees/<name>; report the repo.
    if "/.claude/worktrees/" in top:
        top = top.split("/.claude/worktrees/")[0]
    return (os.path.basename(top.rstrip("/")) or "unknown"), branch[:64]


def base(path):
    return re.split(r"[\\/]", str(path or "").rstrip("/\\"))[-1] or "a file"


def describe_command(cmd):
    """Only the program (and a subcommand for well-known CLIs) — never args, which may hold secrets."""
    cmd = (cmd or "").strip()
    first = re.split(r"\s*(?:&&|\|\||;|\|)\s*", cmd, maxsplit=1)[0]
    try:
        toks = shlex.split(first)
    except ValueError:
        toks = first.split()
    toks = [t for t in toks if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", t)]
    while toks and toks[0] in ("sudo", "time", "nohup", "exec", "env", "timeout"):
        toks = toks[1:]
        while toks and (toks[0].startswith("-") or toks[0].isdigit()):
            toks = toks[1:]
    if not toks:
        return "Running a command"
    prog = os.path.basename(toks[0])
    if prog == "cd" and len(toks) == 2:
        return "Running a command"
    label = prog
    if (prog in SUBCOMMAND_TOOLS or toks[0] in SUBCOMMAND_TOOLS) and len(toks) > 1 and re.match(r"^[a-z][a-z0-9:_-]{0,20}$", toks[1]):
        label = f"{prog} {toks[1]}"
        if toks[1] in ("run", "exec", "x", "dlx") and len(toks) > 2 and re.match(r"^[a-z][a-z0-9:_-]{0,20}$", toks[2]):
            label += f" {toks[2]}"
    return f"Running {label}"


def describe_tool(name, inp):
    inp = inp if isinstance(inp, dict) else {}
    if name in ("Edit", "MultiEdit", "Write"):
        return f"Editing {base(inp.get('file_path'))}", True
    if name == "NotebookEdit":
        return f"Editing {base(inp.get('notebook_path'))}", True
    if name == "Read":
        return f"Reading {base(inp.get('file_path'))}", False
    if name in ("Bash", "PowerShell"):
        return describe_command(inp.get("command")), False
    if name in ("Grep", "Glob"):
        return "Searching the code", False
    if name in ("Agent", "Task"):
        return "Delegating to a subagent", False
    if name in ("WebFetch", "WebSearch"):
        return "Researching on the web", False
    if name in ("TodoWrite", "TaskCreate", "TaskUpdate"):
        return "Planning next steps", False
    if name == "Skill":
        return f"Using the {str(inp.get('skill', '')).split(':')[-1] or 'a'} skill", False
    if name == "AskUserQuestion":
        return "Asking you a question", False
    if name.startswith("mcp__"):
        server = name.split("__")[1] if name.count("__") >= 2 else ""
        if not server or UUIDISH.match(server):
            return "Using a connector", False
        return f"Using {server.replace('-', ' ')}", False
    return f"Using {name}", False


SHIP_WORDS = r"(deploy|ship|push|merge|release|publish|open (?:a |the )?(?:pr|pull request)|create (?:a |the )?(?:pr|pull request))"
SHIP_PATTERNS = [
    re.compile(r"\b(?:ready to|shall i|should i|want me to|would you like me to|okay to|ok to|can i|go ahead and)\b[^.?!\n]{0,60}?\b" + SHIP_WORDS, re.I),
    re.compile(r"\b(?:say the word|give me the go-?ahead|let me know|once you approve|when you'?re ready)\b[^.\n]{0,60}?\b" + SHIP_WORDS, re.I),
]


def ship_intent(message):
    """Does Claude's closing message offer to deploy/push/merge? Returns a category or ''."""
    tail = message[-700:]
    for pat in SHIP_PATTERNS:
        m = pat.search(tail)
        if m:
            w = m.group(1).lower()
            if "pr" in w.split() or "pull request" in w:
                return "pr"
            return {"ship": "deploy"}.get(w, w)
    return ""


def shipped_label(tool, tool_input, tool_response, branch):
    """Label for a successful push / deploy / PR / publish command, else ''."""
    if tool not in ("Bash", "PowerShell") or not isinstance(tool_input, dict):
        return ""
    cmd = " ".join(str(tool_input.get("command", "")).split())
    resp = tool_response if isinstance(tool_response, dict) else {}
    err = str(resp.get("stderr", "")) + str(resp.get("error", ""))
    if resp.get("interrupted") or re.search(r"\b(rejected|fatal:|error:|failed)\b", err, re.I):
        return ""
    if re.search(r"(^|[;&|]\s*)git\s+push\b", cmd) and "--dry-run" not in cmd:
        return f"Pushed {branch}" if branch else "Pushed to remote"
    if re.search(r"\bvercel\b.*(--prod\b|\bdeploy\b.*--prod)", cmd):
        return "Deployed to production"
    if re.search(r"\bvercel\s+(deploy\b|--yes\b|$)", cmd):
        return "Deployed a preview"
    if re.search(r"\bgh\s+pr\s+create\b", cmd):
        return "Opened a pull request"
    if re.search(r"\bgh\s+pr\s+merge\b", cmd):
        return "Merged a pull request"
    if re.search(r"\b(npm|pnpm|yarn)\s+publish\b", cmd) and "--dry-run" not in cmd:
        return "Published a package"
    return ""


def session_title(h):
    """The session's name as shown in Claude Code / the desktop app ("custom-title" entries in the
    transcript, e.g. set by /rename or the auto title). Reads only the tail of the transcript."""
    t = h.get("session_title") or h.get("session_name")
    if t:
        return str(t).strip()[:80]
    path = to_wsl_path(h.get("transcript_path") or "")
    if not path or not os.path.isfile(path):
        return ""
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 400_000))
            tail = f.read().decode("utf-8", "replace")
    except OSError:
        return ""
    found = ""
    for m in re.finditer(r'"type":"custom-title","customTitle":"((?:[^"\\]|\\.)*)"', tail):
        found = m.group(1)
    if not found:
        return ""
    try:
        return json.loads(f'"{found}"').strip()[:80]
    except ValueError:
        return found[:80]


def env_of(h):
    """Which Claude Code install sent this: Windows-side sessions report Windows/UNC paths."""
    p = str(h.get("transcript_path") or h.get("cwd") or "")
    return "windows" if re.match(r"^([A-Za-z]:[\\/]|\\\\)", p) else "wsl"


def build_event(h, cfg):
    ev = h.get("hook_event_name", "")
    out = {"event": ev, "session_id": h.get("session_id", "")}
    if ev == "PreToolUse":
        out["tool"] = h.get("tool_name", "")
        out["activity"], out["edit"] = describe_tool(out["tool"], h.get("tool_input"))
    elif ev == "UserPromptSubmit":
        if cfg.get("TV_STATUS_SHARE_PROMPTS") == "1":
            p = re.sub(r"<pasted_content[^>]*>.*?</pasted_content[^>]*>", "[pasted]", h.get("prompt", ""), flags=re.S)
            p = " ".join(p.split())
            out["activity"] = (p[:70] + "…") if len(p) > 70 else p
        else:
            out["activity"] = "Thinking…"
    elif ev == "Notification":
        kind = h.get("notification_type", "")
        mapping = {"permission_prompt": "Needs your permission", "idle_prompt": "Waiting for your reply",
                   "elicitation_dialog": "Has a question for you"}
        if kind not in mapping:
            return None  # auth_success etc. are not status changes
        out["activity"] = mapping[kind]
    elif ev == "SubagentStart":
        t = h.get("agent_type") or "a"
        out["activity"] = f"Running {t} subagent"
    elif ev == "SessionStart":
        if h.get("model"):
            out["model"] = str(h["model"])
    elif ev == "Stop":
        ship = ship_intent(h.get("last_assistant_message") or "")
        if ship:
            out["ship"] = ship  # only the category leaves this machine, never the message
    elif ev == "PostToolUse":
        project, branch = project_and_branch(h.get("cwd"))
        shipped = shipped_label(h.get("tool_name", ""), h.get("tool_input"), h.get("tool_response"), branch)
        if not shipped:
            return None
        out.update(event="Shipped", activity=shipped, project=project, branch=branch,
                   host=cfg.get("TV_STATUS_HOST") or socket.gethostname(), sessionTitle=session_title(h))
        return out
    elif ev != "SessionEnd":
        return None
    out["project"], out["branch"] = project_and_branch(h.get("cwd"))
    out["host"] = cfg.get("TV_STATUS_HOST") or socket.gethostname()
    out["sessionTitle"] = session_title(h)
    out["env"] = env_of(h)
    return out


def main():
    try:
        h = json.load(sys.stdin)
    except (ValueError, OSError):
        return
    cfg = load_config()
    if not cfg.get("TV_STATUS_URL") or not cfg.get("TV_STATUS_TOKEN"):
        return
    event = build_event(h, cfg)
    if not event or not event["session_id"]:
        return
    if os.environ.get("TV_STATUS_DRY_RUN") == "1":
        print(json.dumps(event))
        return
    req = urllib.request.Request(
        cfg["TV_STATUS_URL"], data=json.dumps(event).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {cfg['TV_STATUS_TOKEN']}",
                 "User-Agent": "attic-tv-status-hook/1"})
    try:
        with urllib.request.urlopen(req, timeout=4) as r:
            r.read()
    except Exception as e:  # noqa: BLE001 — never fail the hook
        log(f"{event['event']} post failed: {e}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        log(f"hook crashed: {e}")
    sys.exit(0)
