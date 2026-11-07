// Serves the condensed data files stored in Cloudflare KV (binding: DATA).
// GET /api/at  ->  KV key "at.json"
const ALLOWED = new Set(["at", "de", "hu"]);

export async function onRequestGet({ params, env }) {
  const name = String(params.name || "");
  if (!ALLOWED.has(name)) return new Response("not found", { status: 404 });
  const body = await env.DATA.get(`${name}.json`, { type: "stream" });
  if (!body) return new Response("no data yet", { status: 404 });
  return new Response(body, {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "public, max-age=120",
    },
  });
}
