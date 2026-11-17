import { timingSafeEqual } from "node:crypto";

function safeEq(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && timingSafeEqual(x, y);
}

/** Bearer token from the Authorization header, or `?key=` for the browser preview. */
export function tokenFrom(request) {
  const h = request.headers.get("authorization") || "";
  if (h.toLowerCase().startsWith("bearer ")) return h.slice(7).trim();
  return new URL(request.url).searchParams.get("key") || "";
}

export function authorized(request, ...envNames) {
  const got = tokenFrom(request);
  if (!got) return false;
  return envNames.some((n) => process.env[n] && safeEq(got, process.env[n]));
}

export const json = (data, status = 200, extra = {}) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });
