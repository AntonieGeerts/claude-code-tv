// Minimal Upstash Redis REST client (no dependencies).
// Works with the env vars injected by the Vercel Marketplace Upstash integration
// (KV_REST_API_URL/KV_REST_API_TOKEN) or plain Upstash (UPSTASH_REDIS_REST_URL/TOKEN).
// Falls back to an in-memory store when neither is set (local tests only).

const URL_ = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL;
const TOKEN = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN;

let shared;
export function createRedis() {
  if (URL_ && TOKEN) return restClient(URL_, TOKEN);
  return (shared ??= memoryClient()); // one in-memory store per process (dev server / tests)
}

function restClient(base, token) {
  async function pipeline(cmds) {
    if (!cmds.length) return [];
    const res = await fetch(`${base}/pipeline`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify(cmds.map((c) => c.map(String))),
    });
    if (!res.ok) throw new Error(`redis pipeline ${res.status}: ${await res.text()}`);
    const out = await res.json();
    return out.map((r) => {
      if (r.error) throw new Error(`redis: ${r.error}`);
      return r.result;
    });
  }
  return { pipeline, kind: "upstash" };
}

// In-memory subset of the commands we use: HSET HGETALL HINCRBY HDEL EXPIRE ZADD ZREM ZRANGEBYSCORE ZREMRANGEBYSCORE GET SET DEL
export function memoryClient() {
  const kv = new Map();
  const hash = (k) => (kv.has(k) ? kv.get(k) : kv.set(k, new Map()).get(k));
  const run = ([cmd, key, ...a]) => {
    switch (cmd.toUpperCase()) {
      case "HSET": { const h = hash(key); for (let i = 0; i < a.length; i += 2) h.set(String(a[i]), String(a[i + 1])); return a.length / 2; }
      case "HGET": { const h = kv.get(key); const v = h ? h.get(String(a[0])) : undefined; return v === undefined ? null : v; }
      case "HGETALL": { const h = kv.get(key); if (!h) return []; return [...h.entries()].flat(); }
      case "HINCRBY": { const h = hash(key); const v = Number(h.get(String(a[0])) || 0) + Number(a[1]); h.set(String(a[0]), String(v)); return v; }
      case "HDEL": { const h = kv.get(key); return h ? a.filter((f) => h.delete(String(f))).length : 0; }
      case "EXPIRE": return 1;
      case "ZADD": { const z = hash(key); z.set(String(a[1]), Number(a[0])); return 1; }
      case "ZREM": { const z = kv.get(key); return z ? a.filter((m) => z.delete(String(m))).length : 0; }
      case "ZRANGEBYSCORE": { const z = kv.get(key); if (!z) return []; const lo = Number(a[0]), hi = a[1] === "+inf" ? Infinity : Number(a[1]); return [...z.entries()].filter(([, s]) => s >= lo && s <= hi).sort((x, y) => x[1] - y[1]).map(([m]) => m); }
      case "ZREMRANGEBYSCORE": { const z = kv.get(key); if (!z) return 0; const lo = a[0] === "-inf" ? -Infinity : Number(a[0]), hi = Number(a[1]); let n = 0; for (const [m, s] of z) if (s >= lo && s <= hi) { z.delete(m); n++; } return n; }
      case "LPUSH": { const l = kv.get(key) || []; l.unshift(...a.map(String).reverse()); kv.set(key, l); return l.length; }
      case "LTRIM": { const l = kv.get(key) || []; const s = Number(a[0]), e = Number(a[1]); kv.set(key, l.slice(s, e < 0 ? l.length + e + 1 : e + 1)); return "OK"; }
      case "LRANGE": { const l = kv.get(key) || []; const s = Number(a[0]), e = Number(a[1]); return l.slice(s, e < 0 ? l.length + e + 1 : e + 1); }
      case "GET": { const v = kv.get(key); return v === undefined ? null : v; }
      case "SET": kv.set(key, String(a[0])); return "OK";
      case "DEL": return kv.delete(key) ? 1 : 0;
      default: throw new Error(`memory redis: unsupported ${cmd}`);
    }
  };
  return { pipeline: async (cmds) => cmds.map(run), kind: "memory" };
}

export const toObject = (flat) => {
  const o = {};
  for (let i = 0; i < (flat || []).length; i += 2) o[flat[i]] = flat[i + 1];
  return o;
};
