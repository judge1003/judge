import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as wait } from "node:timers/promises";

async function startServer(port, env = {}) {
  const proc = spawn(process.execPath, ["server/index.js"], {
    env: { ...process.env, ANTHROPIC_API_KEY: "", MOCK: "1", PORT: String(port), HOST: "127.0.0.1", ...env },
    stdio: "pipe",
  });
  for (let i = 0; i < 50; i++) {
    try {
      await fetch(`http://127.0.0.1:${port}/api/health`);
      return proc;
    } catch {
      await wait(100);
    }
  }
  proc.kill();
  throw new Error("server did not start");
}

const chat = (port, body, headers = {}) =>
  fetch(`http://127.0.0.1:${port}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

test("server: static, health, chat, security", async (t) => {
  const port = 18081;
  const proc = await startServer(port, { RATE_PER_MIN: "5" });
  t.after(() => proc.kill());

  const home = await fetch(`http://127.0.0.1:${port}/`);
  assert.equal(home.status, 200);
  assert.match(home.headers.get("content-security-policy"), /default-src 'self'/);
  assert.match(await home.text(), /English Buddy/);

  const trav = await fetch(`http://127.0.0.1:${port}/..%2f..%2fpackage.json`);
  assert.ok([403, 404].includes(trav.status), `traversal returned ${trav.status}`);

  const health = await (await fetch(`http://127.0.0.1:${port}/api/health`)).json();
  assert.equal(health.mock, true);

  const r = await chat(port, { history: [{ role: "user", text: "I like star" }], profile: { name: "Liam" } });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.ok(j.say && "try_saying" in j && j.star === true);

  assert.equal((await chat(port, "{not json")).status, 400);
  assert.equal((await chat(port, "x".repeat(40_000))).status, 413);

  // 4 requests used above (one 413 may close early but still counts) -> keep going until limited
  let limited = false;
  for (let i = 0; i < 6; i++) if ((await chat(port, { history: [] })).status === 429) limited = true;
  assert.equal(limited, true);
});

test("server: access code is enforced", async (t) => {
  const port = 18082;
  const proc = await startServer(port, { ACCESS_CODE: "family123" });
  t.after(() => proc.kill());
  assert.equal((await chat(port, { history: [] })).status, 401);
  assert.equal((await chat(port, { history: [] }, { "X-Access-Code": "wrong" })).status, 401);
  assert.equal((await chat(port, { history: [] }, { "X-Access-Code": "family123" })).status, 200);
  const health = await (await fetch(`http://127.0.0.1:${port}/api/health`)).json();
  assert.equal(health.needsCode, true);
});
