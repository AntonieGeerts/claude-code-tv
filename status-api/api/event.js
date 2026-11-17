// POST /api/event — receives sanitized Claude Code hook events (see hooks/tv_status_hook.py).
// Auth: Authorization: Bearer $INGEST_TOKEN
import { createRedis } from "../lib/redis.js";
import { applyEvent, parseEvent } from "../lib/status.js";
import { authorized, json } from "../lib/auth.js";

const redis = createRedis();

export async function POST(request) {
  if (!authorized(request, "INGEST_TOKEN")) return json({ error: "unauthorized" }, 401);
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }
  const evt = parseEvent(body);
  if (!evt) return json({ error: "invalid event" }, 422);
  await applyEvent(redis, evt);
  return json({ ok: true });
}
