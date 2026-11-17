#!/usr/bin/env python3
"""Send a custom alert to the TV: tv_notify.py "Title" ["Body"] [--kind ship|done|shipped|permission|question|info]

Handy from scripts or for Claude to call, e.g. after a long build:
  python3 hooks/tv_notify.py "Build finished" "APK is ready to install" --kind done
"""
import argparse
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tv_status_hook import load_config, project_and_branch  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("title")
    ap.add_argument("body", nargs="?", default="")
    ap.add_argument("--kind", default="info", choices=["info", "ship", "done", "shipped", "permission", "question", "reply"])
    a = ap.parse_args()
    cfg = load_config()
    if not cfg.get("TV_STATUS_URL") or not cfg.get("TV_STATUS_TOKEN"):
        sys.exit("TV_STATUS_URL / TV_STATUS_TOKEN not configured (hooks/tv_status.env)")
    project, _ = project_and_branch(os.getcwd())
    ev = {"event": "Notify", "session_id": os.environ.get("CLAUDE_SESSION_ID", "manual-notify"),
          "project": project, "kind": a.kind, "title": a.title, "body": a.body}
    req = urllib.request.Request(cfg["TV_STATUS_URL"], data=json.dumps(ev).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {cfg['TV_STATUS_TOKEN']}"})
    with urllib.request.urlopen(req, timeout=10) as r:
        print(r.read().decode())


if __name__ == "__main__":
    main()
