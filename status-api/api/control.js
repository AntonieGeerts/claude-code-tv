// TV ↔ session control.
//   GET  /api/control?session=<full id>&request=<id>   (INGEST_TOKEN, the waiting hook) → { away, pending, reply }
//   POST /api/control { action: "reply", session, requestId, option }   (READ_TOKEN, the TV)
//   POST /api/control { action: "away", on: true|false }                (READ_TOKEN, the TV)
// The TV only ever sends option ids; the text behind them lives on the PC (hooks/tv_replies.json).
import { createRedis } from "../lib/redis.js";
import { pollControl, postReply, setAway } from "../lib/status.js";
import { authorized, json } from "../lib/auth.js";

const redis = createRedis();

export async function GET(request) {
  if (!authorized(request, "INGEST_TOKEN")) return json({ error: "unauthorized" }, 401);
  const u = new URL(request.url);
  const session = u.searchParams.get("session") || "";
  const req = u.searchParams.get("request") || "";
  if (!/^[A-Za-z0-9_-]{4,100}$/.test(session)) return json({ error: "bad session" }, 400);
  return json(await pollControl(redis, session, req));
}

export async function POST(request) {
  if (!authorized(request, "READ_TOKEN", "INGEST_TOKEN")) return json({ error: "unauthorized" }, 401);
  let body;
  try { body = await request.json(); } catch { return json({ error: "invalid json" }, 400); }
  if (body?.action === "away") return json(await setAway(redis, body.on === true));
  if (body?.action === "reply") {
    const r = await postReply(redis, { session: String(body.session || ""), requestId: String(body.requestId || ""), option: String(body.option || "") });
    return json(r, r.ok ? 200 : 409);
  }
  return json({ error: "unknown action" }, 400);
}
