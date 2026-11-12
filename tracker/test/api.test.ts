import assert from "node:assert/strict";
import { test } from "node:test";

import { ONLINE_MS, handle } from "../src/api.ts";
import { deriveKey } from "../src/crypto.ts";
import { MemoryRepo } from "../src/repo.ts";

const T0 = 1_800_000_000_000;

async function call(repo: MemoryRepo, method: string, path: string, opts: { body?: unknown; session?: string; now?: number } = {}) {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (opts.session) headers.authorization = `Bearer ${opts.session}`;
  const res = await handle(
    new Request(`https://t.example${path}`, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) }),
    repo,
    opts.now ?? T0,
  );
  return { status: res.status, body: (await res.json()) as Record<string, any> };
}

const tablet = { id: "tablet-0001", name: "Tab S8+", kind: "tablet" };
const pc = { id: "pc-00000001", name: "fedora", kind: "pc" };

async function account(repo: MemoryRepo) {
  const key = await deriveKey("steven", "una contraseña");
  const reg = await call(repo, "POST", "/v1/register", { body: { user: "Steven", key, device: tablet } });
  assert.equal(reg.status, 201);
  const log = await call(repo, "POST", "/v1/login", { body: { user: "steven", key, device: pc } });
  assert.equal(log.status, 200);
  return { key, tab: reg.body.session as string, pc: log.body.session as string };
}

test("la clave derivada es la misma que en los demás clientes", async () => {
  // Mismo vector en Account.java y en el visor.
  assert.equal(await deriveKey("ana", "secreto123"), "c247987cde07c49e2649d6aabd24afb4948ccd98fa5e8456f74fef4b52d4089f");
  assert.notEqual(await deriveKey("ana", "secreto123"), await deriveKey("bea", "secreto123"));
  assert.match(await deriveKey("ana", "secreto123"), /^[0-9a-f]{64}$/);
});

test("registrar, entrar y no repetir nombre", async () => {
  const repo = new MemoryRepo();
  const { key } = await account(repo);
  assert.equal(repo.users[0].name, "steven", "en minúsculas");
  assert.notEqual(repo.users[0].key_hash, key, "no se guarda la clave tal cual");
  const again = await call(repo, "POST", "/v1/register", { body: { user: "steven", key, device: tablet } });
  assert.equal(again.status, 409);
  const bad = await call(repo, "POST", "/v1/register", { body: { user: "x", key, device: tablet } });
  assert.equal(bad.status, 400);
});

test("contraseña mala bloquea tras varios intentos", async () => {
  const repo = new MemoryRepo();
  const { key } = await account(repo);
  const wrong = "0".repeat(64);
  for (let i = 0; i < 5; i++) assert.equal((await call(repo, "POST", "/v1/login", { body: { user: "steven", key: wrong, device: pc } })).status, 401);
  assert.equal((await call(repo, "POST", "/v1/login", { body: { user: "steven", key, device: pc } })).status, 429, "bloqueada aunque sea buena");
  const later = await call(repo, "POST", "/v1/login", { body: { user: "steven", key, device: pc }, now: T0 + 6 * 60_000 });
  assert.equal(later.status, 200);
});

test("los aparatos de la cuenta, con su IP y si están en línea", async () => {
  const repo = new MemoryRepo();
  const s = await account(repo);
  const hb = await call(repo, "POST", "/v1/heartbeat", { session: s.pc, body: { lan: ["172.24.67.251:8788", "malo; rm -rf"], server: true } });
  assert.equal(hb.status, 200);
  const list = await call(repo, "GET", "/v1/devices", { session: s.tab, now: T0 + 1000 });
  const byId = Object.fromEntries(list.body.devices.map((d: any) => [d.id, d]));
  assert.deepEqual(byId[pc.id].lan, ["172.24.67.251:8788"], "sólo direcciones válidas");
  assert.equal(byId[pc.id].server, true);
  assert.equal(byId[tablet.id].me, true);
  const stale = await call(repo, "GET", "/v1/devices", { session: s.tab, now: T0 + ONLINE_MS + 1 });
  assert.equal(stale.body.devices.find((d: any) => d.id === pc.id).online, false);
  assert.equal((await call(repo, "GET", "/v1/devices")).status, 401);
});

test("otra cuenta no ve mis aparatos", async () => {
  const repo = new MemoryRepo();
  const s = await account(repo);
  const key = await deriveKey("ana", "otra contraseña");
  const ana = await call(repo, "POST", "/v1/register", { body: { user: "ana", key, device: { id: "ana-phone-1", kind: "phone" } } });
  const list = await call(repo, "GET", "/v1/devices", { session: ana.body.session });
  assert.deepEqual(list.body.devices.map((d: any) => d.id), ["ana-phone-1"]);
  // Ni pedir boletos para ellos.
  const t = await call(repo, "POST", "/v1/tickets", { session: ana.body.session, body: { for: pc.id } });
  assert.equal(t.status, 404);
  assert.ok(s);
});

test("el boleto sólo lo canjea el aparato para el que es, una vez", async () => {
  const repo = new MemoryRepo();
  const s = await account(repo);
  const t = await call(repo, "POST", "/v1/tickets", { session: s.tab, body: { for: pc.id } });
  assert.equal(t.status, 200);
  // La tablet no puede canjear su propio boleto (un PC falso tampoco tendría sesión del PC).
  assert.equal((await call(repo, "POST", "/v1/tickets/redeem", { session: s.tab, body: { ticket: t.body.ticket } })).status, 403);
  const t2 = await call(repo, "POST", "/v1/tickets", { session: s.tab, body: { for: pc.id } });
  const ok = await call(repo, "POST", "/v1/tickets/redeem", { session: s.pc, body: { ticket: t2.body.ticket } });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.device.id, tablet.id);
  assert.equal((await call(repo, "POST", "/v1/tickets/redeem", { session: s.pc, body: { ticket: t2.body.ticket } })).status, 403, "una vez");
  const t3 = await call(repo, "POST", "/v1/tickets", { session: s.tab, body: { for: pc.id } });
  const late = await call(repo, "POST", "/v1/tickets/redeem", { session: s.pc, body: { ticket: t3.body.ticket }, now: T0 + 3 * 60_000 });
  assert.equal(late.status, 403, "caducado");
});

test("quitar un aparato cierra su sesión", async () => {
  const repo = new MemoryRepo();
  const s = await account(repo);
  assert.equal((await call(repo, "DELETE", `/v1/devices/${pc.id}`, { session: s.tab })).status, 200);
  assert.equal((await call(repo, "GET", "/v1/me", { session: s.pc })).status, 401);
  assert.equal((await call(repo, "POST", "/v1/logout", { session: s.tab })).status, 200);
  assert.equal((await call(repo, "GET", "/v1/me", { session: s.tab })).status, 401);
});
