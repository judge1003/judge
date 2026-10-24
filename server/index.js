import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { getReply, mockReply, MODEL } from "./tutor.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(here, "../public");

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "0.0.0.0";
const MOCK = process.env.MOCK === "1" || !process.env.ANTHROPIC_API_KEY;
// Shared family code so a leaked URL can't spend your API credit.
const ACCESS_CODE = process.env.ACCESS_CODE || "";
const RATE_PER_MIN = Number(process.env.RATE_PER_MIN || 20);
const MAX_BODY = 32 * 1024;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

const SECURITY_HEADERS = {
  "Content-Security-Policy":
    "default-src 'self'; img-src 'self' data:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "microphone=(self), camera=(self), geolocation=()",
};

function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...SECURITY_HEADERS, ...headers });
  res.end(body);
}
const sendJson = (res, status, obj) =>
  send(res, status, JSON.stringify(obj), { "Content-Type": TYPES[".json"], "Cache-Control": "no-store" });

// Fixed-window limiter per client IP.
const hits = new Map();
export function rateLimited(ip, now = Date.now()) {
  const minute = Math.floor(now / 60_000);
  const rec = hits.get(ip);
  if (!rec || rec.minute !== minute) {
    hits.set(ip, { minute, count: 1 });
    if (hits.size > 5000) hits.clear();
    return false;
  }
  rec.count += 1;
  return rec.count > RATE_PER_MIN;
}

function codeOk(given) {
  if (!ACCESS_CODE) return true;
  const a = Buffer.from(String(given || ""));
  const b = Buffer.from(ACCESS_CODE);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        req.removeAllListeners("data");
        req.resume(); // discard the rest; the 413 reply closes the connection
        reject(Object.assign(new Error("too large"), { status: 413 }));
      } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

async function handleChat(req, res) {
  if (!codeOk(req.headers["x-access-code"])) return sendJson(res, 401, { error: "access_code" });
  if (rateLimited(req.socket.remoteAddress || "?")) return sendJson(res, 429, { error: "slow_down" });
  let payload;
  try {
    payload = JSON.parse(await readBody(req));
  } catch (e) {
    if (e.status === 413) res.setHeader("Connection", "close");
    return sendJson(res, e.status || 400, { error: "bad_request" });
  }
  try {
    const reply = MOCK ? mockReply(payload) : await getReply(payload);
    sendJson(res, 200, reply);
  } catch (e) {
    console.error("chat error:", e?.status ?? "", e?.message);
    sendJson(res, 502, { error: "upstream" });
  }
}

function serveStatic(req, res) {
  const urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);
  const rel = urlPath === "/" ? "index.html" : urlPath.replace(/^\/+/, "");
  const file = path.resolve(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return send(res, 403, "forbidden");
  fs.readFile(file, (err, data) => {
    if (err) return send(res, 404, "not found");
    send(res, 200, data, {
      "Content-Type": TYPES[path.extname(file)] || "application/octet-stream",
      "Cache-Control": "no-cache",
    });
  });
}

export function handler(req, res) {
  if (req.method === "POST" && req.url === "/api/chat") return void handleChat(req, res);
  if (req.method === "GET" && req.url === "/api/health")
    return sendJson(res, 200, { ok: true, mock: MOCK, needsCode: Boolean(ACCESS_CODE) });
  if (req.method === "GET" || req.method === "HEAD") return serveStatic(req, res);
  send(res, 405, "method not allowed");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const tls =
    process.env.TLS_CERT && process.env.TLS_KEY
      ? { cert: fs.readFileSync(process.env.TLS_CERT), key: fs.readFileSync(process.env.TLS_KEY) }
      : null;
  const server = tls ? https.createServer(tls, handler) : http.createServer(handler);
  server.listen(PORT, HOST, () => {
    console.log(`English Buddy on ${tls ? "https" : "http"}://${HOST}:${PORT}  (${MOCK ? "MOCK replies" : MODEL})`);
    if (!ACCESS_CODE) console.log("Tip: set ACCESS_CODE to stop strangers from using your API key.");
  });
}
