// Local stand-in for Vercel: serves api/*.js handlers + public/ with in-memory Redis.
// Usage: INGEST_TOKEN=dev READ_TOKEN=devread node dev-server.js [port] [--demo]
//   --demo  pre-fills made-up sessions, usage and alerts (try the TV app without Claude Code)
import http from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join } from "node:path";
import * as event from "./api/event.js";
import * as status from "./api/status.js";
import * as control from "./api/control.js";
import { createRedis } from "./lib/redis.js";
import { seedDemo } from "./demo-data.js";

const routes = { "/api/event": event, "/api/status": status, "/api/control": control };
const port = Number(process.argv.find((a) => /^\d+$/.test(a)) || 8790);
if (process.argv.includes("--demo")) await seedDemo(createRedis());
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const mod = routes[url.pathname];
  try {
    if (mod) {
      const handler = mod[req.method];
      if (!handler) return void res.writeHead(405).end();
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const body = ["GET", "HEAD"].includes(req.method) ? undefined : Buffer.concat(chunks);
      const r = await handler(new Request(url, { method: req.method, headers: req.headers, body }));
      res.writeHead(r.status, Object.fromEntries(r.headers));
      return void res.end(Buffer.from(await r.arrayBuffer()));
    }
    const file = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
    if (file.includes("..")) return void res.writeHead(400).end();
    const data = await readFile(join(import.meta.dirname, "public", file));
    res.writeHead(200, { "content-type": types[extname(file)] || "application/octet-stream" }).end(data);
  } catch (e) {
    res.writeHead(e.code === "ENOENT" ? 404 : 500).end(String(e.message));
  }
}).listen(port, () => console.log(`dev server on http://localhost:${port}`));
