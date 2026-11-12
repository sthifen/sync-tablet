// La API del tracker (ver PROTOCOLO.md). No sabe nada de Cloudflare: recibe
// un Request y un Repo, y devuelve un Response.

import { randomHex, same, sha256 } from "./crypto.ts";
import type { Repo, Session } from "./repo.ts";

export const VERSION = 1;

/** Un aparato se ve «en línea» si avisó hace menos de esto. */
export const ONLINE_MS = 3 * 60_000;
const TICKET_MS = 2 * 60_000;
const MAX_FAILS = 5;
const LOCK_MS = 5 * 60_000;
const KINDS = ["pc", "desktop", "tablet", "phone"];

const USER_RE = /^[a-z0-9][a-z0-9._-]{2,31}$/;
const KEY_RE = /^[0-9a-f]{64}$/;
const ID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const LAN_RE = /^[0-9a-fA-F.:[\]]{3,45}:\d{1,5}$/;

class Fail extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function body(req: Request): Promise<Record<string, unknown>> {
  try {
    const b = await req.json();
    if (b && typeof b === "object" && !Array.isArray(b)) return b as Record<string, unknown>;
  } catch {
    // Cae al error de abajo.
  }
  throw new Fail(400, "el cuerpo debe ser un objeto JSON");
}

function str(v: unknown, max = 200): string {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

interface DeviceIn {
  id: string;
  name: string;
  kind: string;
}

function deviceIn(v: unknown): DeviceIn {
  const d = (v && typeof v === "object" ? v : {}) as Record<string, unknown>;
  const id = str(d.id, 64);
  if (!ID_RE.test(id)) throw new Fail(400, "device.id no es válido");
  const kind = KINDS.includes(str(d.kind)) ? str(d.kind) : "tablet";
  return { id, name: str(d.name, 60) || kind, kind };
}

function lanIn(v: unknown): string[] | undefined {
  if (!Array.isArray(v)) return undefined;
  return v.filter((x): x is string => typeof x === "string" && LAN_RE.test(x)).slice(0, 8);
}

async function newSession(repo: Repo, userId: number, deviceId: string, now: number): Promise<string> {
  const token = randomHex(32);
  await repo.createSession(await sha256(token), userId, deviceId, now);
  return token;
}

async function auth(repo: Repo, req: Request, now: number): Promise<Session> {
  const h = req.headers.get("authorization") ?? "";
  const token = h.startsWith("Bearer ") ? h.slice(7).trim() : "";
  const s = token ? await repo.session(await sha256(token), now) : null;
  if (!s) throw new Fail(401, "sesión no válida");
  return s;
}

function publicDevice(d: { id: string; name: string; kind: string; lan: string[]; server: boolean; last_seen: number }, now: number, me: string) {
  return { ...d, online: now - d.last_seen < ONLINE_MS, me: d.id === me };
}

export async function handle(req: Request, repo: Repo, now = Date.now()): Promise<Response> {
  const url = new URL(req.url);
  const route = `${req.method} ${url.pathname.replace(/\/+$/, "")}`;
  try {
    switch (route) {
      case "GET /v1/health":
        return json({ ok: true, version: VERSION });

      case "POST /v1/register": {
        const b = await body(req);
        const user = str(b.user, 32).toLowerCase();
        const key = str(b.key, 64);
        if (!USER_RE.test(user)) throw new Fail(400, "el usuario debe tener de 3 a 32 letras, números, «.», «_» o «-»");
        if (!KEY_RE.test(key)) throw new Fail(400, "clave no válida");
        const device = deviceIn(b.device);
        const salt = randomHex(16);
        const id = await repo.createUser(user, await sha256(salt + key), salt, now);
        if (id === null) throw new Fail(409, "ese usuario ya existe");
        await repo.upsertDevice(id, { ...device, lan: [], server: false, last_seen: now });
        return json({ user, device_id: device.id, session: await newSession(repo, id, device.id, now) }, 201);
      }

      case "POST /v1/login": {
        const b = await body(req);
        const user = str(b.user, 32).toLowerCase();
        const key = str(b.key, 64);
        const device = deviceIn(b.device);
        const u = await repo.userByName(user);
        if (!u) throw new Fail(401, "usuario o contraseña incorrectos");
        if (u.locked_until > now) throw new Fail(429, "demasiados intentos; espera unos minutos");
        if (!same(await sha256(u.salt + key), u.key_hash)) {
          const fails = u.fails + 1;
          await repo.setFails(u.id, fails >= MAX_FAILS ? 0 : fails, fails >= MAX_FAILS ? now + LOCK_MS : 0);
          throw new Fail(401, "usuario o contraseña incorrectos");
        }
        if (u.fails) await repo.setFails(u.id, 0, 0);
        await repo.upsertDevice(u.id, { ...device, lan: [], server: false, last_seen: now });
        return json({ user: u.name, device_id: device.id, session: await newSession(repo, u.id, device.id, now) });
      }

      case "GET /v1/me": {
        const s = await auth(repo, req, now);
        const d = await repo.device(s.user_id, s.device_id);
        return json({ user: s.user, device: d && publicDevice(d, now, s.device_id) });
      }

      case "POST /v1/logout": {
        await auth(repo, req, now);
        const token = (req.headers.get("authorization") ?? "").slice(7).trim();
        await repo.deleteSession(await sha256(token));
        return json({ ok: true });
      }

      case "POST /v1/heartbeat": {
        const s = await auth(repo, req, now);
        const b = await body(req);
        await repo.touchDevice(
          s.user_id,
          s.device_id,
          { name: str(b.name, 60) || undefined, lan: lanIn(b.lan), server: typeof b.server === "boolean" ? b.server : undefined },
          now,
        );
        return json({ ok: true, online_ms: ONLINE_MS });
      }

      case "GET /v1/devices": {
        const s = await auth(repo, req, now);
        const list = await repo.devices(s.user_id);
        return json({ devices: list.map((d) => publicDevice(d, now, s.device_id)) });
      }

      case "POST /v1/tickets": {
        const s = await auth(repo, req, now);
        const b = await body(req);
        const forDev = str(b.for, 64);
        if (!(await repo.device(s.user_id, forDev))) throw new Fail(404, "ese aparato no es de esta cuenta");
        const ticket = randomHex(24);
        const expires = now + TICKET_MS;
        await repo.createTicket(await sha256(ticket), { user_id: s.user_id, from_dev: s.device_id, for_dev: forDev, expires });
        return json({ ticket, expires });
      }

      case "POST /v1/tickets/redeem": {
        // Lo canjea el aparato al que va dirigido, con su propia sesión.
        const s = await auth(repo, req, now);
        const b = await body(req);
        const t = await repo.takeTicket(await sha256(str(b.ticket, 64)));
        if (!t || t.expires < now || t.user_id !== s.user_id || t.for_dev !== s.device_id) throw new Fail(403, "boleto no válido");
        const from = await repo.device(s.user_id, t.from_dev);
        return json({ user: s.user, device: from && publicDevice(from, now, s.device_id) });
      }
    }

    const del = url.pathname.match(/^\/v1\/devices\/([A-Za-z0-9_-]{8,64})$/);
    if (req.method === "DELETE" && del) {
      const s = await auth(repo, req, now);
      await repo.deleteDevice(s.user_id, del[1]);
      return json({ ok: true });
    }
    return json({ error: "no existe" }, 404);
  } catch (e) {
    if (e instanceof Fail) return json({ error: e.message }, e.status);
    console.error(e);
    return json({ error: "error interno" }, 500);
  }
}
