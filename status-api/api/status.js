// GET /api/status — snapshot polled by the TV launcher card and the preview page.
// Auth: Authorization: Bearer $READ_TOKEN (or ?key=$READ_TOKEN). INGEST_TOKEN also works.
import { createRedis } from "../lib/redis.js";
import { readStatus } from "../lib/status.js";
import { authorized, json } from "../lib/auth.js";

const redis = createRedis();

export async function GET(request) {
  if (!authorized(request, "READ_TOKEN", "INGEST_TOKEN")) return json({ error: "unauthorized" }, 401);
  return json(await readStatus(redis));
}
