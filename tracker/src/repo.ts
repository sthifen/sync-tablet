// Dónde se guardan las cuentas. La API sólo conoce esta interfaz: cambiar de
// D1 a Postgres (o a lo que sea) es escribir otra implementación.

export interface User {
  id: number;
  name: string;
  key_hash: string;
  salt: string;
  fails: number;
  locked_until: number;
}

export interface Device {
  id: string;
  name: string;
  kind: string;
  lan: string[];
  server: boolean;
  last_seen: number;
}

export interface Session {
  user_id: number;
  user: string;
  device_id: string;
}

export interface Ticket {
  user_id: number;
  from_dev: string;
  for_dev: string;
  expires: number;
}

export interface Repo {
  userByName(name: string): Promise<User | null>;
  /** null si el nombre ya existe. */
  createUser(name: string, keyHash: string, salt: string, now: number): Promise<number | null>;
  setFails(userId: number, fails: number, lockedUntil: number): Promise<void>;

  upsertDevice(userId: number, d: Device): Promise<void>;
  touchDevice(userId: number, id: string, patch: Partial<Pick<Device, "name" | "lan" | "server">>, now: number): Promise<void>;
  device(userId: number, id: string): Promise<Device | null>;
  devices(userId: number): Promise<Device[]>;
  deleteDevice(userId: number, id: string): Promise<void>;

  createSession(hash: string, userId: number, deviceId: string, now: number): Promise<void>;
  session(hash: string, now: number): Promise<Session | null>;
  deleteSession(hash: string): Promise<void>;

  createTicket(hash: string, t: Ticket): Promise<void>;
  /** Lo borra al leerlo: un boleto vale una sola vez. */
  takeTicket(hash: string): Promise<Ticket | null>;
}

// ------------------------------------------------------------------- D1

export class D1Repo implements Repo {
  private db: D1Database;
  constructor(db: D1Database) {
    this.db = db;
  }

  async userByName(name: string) {
    return this.db.prepare("SELECT id, name, key_hash, salt, fails, locked_until FROM users WHERE name = ?").bind(name).first<User>();
  }

  async createUser(name: string, keyHash: string, salt: string, now: number) {
    const r = await this.db
      .prepare("INSERT INTO users (name, key_hash, salt, created) VALUES (?, ?, ?, ?) ON CONFLICT(name) DO NOTHING RETURNING id")
      .bind(name, keyHash, salt, now)
      .first<{ id: number }>();
    return r ? r.id : null;
  }

  async setFails(userId: number, fails: number, lockedUntil: number) {
    await this.db.prepare("UPDATE users SET fails = ?, locked_until = ? WHERE id = ?").bind(fails, lockedUntil, userId).run();
  }

  async upsertDevice(userId: number, d: Device) {
    await this.db
      .prepare(
        `INSERT INTO devices (user_id, id, name, kind, lan, server, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, id) DO UPDATE SET name = excluded.name, kind = excluded.kind, last_seen = excluded.last_seen`,
      )
      .bind(userId, d.id, d.name, d.kind, JSON.stringify(d.lan), d.server ? 1 : 0, d.last_seen)
      .run();
  }

  async touchDevice(userId: number, id: string, patch: Partial<Pick<Device, "name" | "lan" | "server">>, now: number) {
    await this.db
      .prepare(
        `UPDATE devices SET last_seen = ?, name = COALESCE(?, name), lan = COALESCE(?, lan), server = COALESCE(?, server)
         WHERE user_id = ? AND id = ?`,
      )
      .bind(
        now,
        patch.name ?? null,
        patch.lan ? JSON.stringify(patch.lan) : null,
        patch.server === undefined ? null : patch.server ? 1 : 0,
        userId,
        id,
      )
      .run();
  }

  async device(userId: number, id: string) {
    const r = await this.db.prepare("SELECT * FROM devices WHERE user_id = ? AND id = ?").bind(userId, id).first<Record<string, unknown>>();
    return r ? toDevice(r) : null;
  }

  async devices(userId: number) {
    const { results } = await this.db.prepare("SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen DESC").bind(userId).all<Record<string, unknown>>();
    return results.map(toDevice);
  }

  async deleteDevice(userId: number, id: string) {
    await this.db.batch([
      this.db.prepare("DELETE FROM sessions WHERE user_id = ? AND device_id = ?").bind(userId, id),
      this.db.prepare("DELETE FROM devices WHERE user_id = ? AND id = ?").bind(userId, id),
    ]);
  }

  async createSession(hash: string, userId: number, deviceId: string, now: number) {
    await this.db
      .prepare("INSERT INTO sessions (hash, user_id, device_id, created, last_used) VALUES (?, ?, ?, ?, ?)")
      .bind(hash, userId, deviceId, now, now)
      .run();
  }

  async session(hash: string, now: number) {
    const r = await this.db
      .prepare("SELECT s.user_id, u.name AS user, s.device_id, s.last_used FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.hash = ?")
      .bind(hash)
      .first<Session & { last_used: number }>();
    if (!r) return null;
    // Escribir en cada petición gasta; basta con apuntarlo una vez por hora.
    if (now - r.last_used > 3600_000) await this.db.prepare("UPDATE sessions SET last_used = ? WHERE hash = ?").bind(now, hash).run();
    return { user_id: r.user_id, user: r.user, device_id: r.device_id };
  }

  async deleteSession(hash: string) {
    await this.db.prepare("DELETE FROM sessions WHERE hash = ?").bind(hash).run();
  }

  async createTicket(hash: string, t: Ticket) {
    await this.db.batch([
      this.db.prepare("DELETE FROM tickets WHERE expires < ?").bind(Date.now()),
      this.db
        .prepare("INSERT INTO tickets (hash, user_id, from_dev, for_dev, expires) VALUES (?, ?, ?, ?, ?)")
        .bind(hash, t.user_id, t.from_dev, t.for_dev, t.expires),
    ]);
  }

  async takeTicket(hash: string) {
    return this.db.prepare("DELETE FROM tickets WHERE hash = ? RETURNING user_id, from_dev, for_dev, expires").bind(hash).first<Ticket>();
  }
}

function toDevice(r: Record<string, unknown>): Device {
  let lan: string[] = [];
  try {
    lan = JSON.parse(String(r.lan ?? "[]"));
  } catch {
    // Dato viejo o roto: sin direcciones.
  }
  return {
    id: String(r.id),
    name: String(r.name),
    kind: String(r.kind),
    lan,
    server: Number(r.server) === 1,
    last_seen: Number(r.last_seen),
  };
}

// ---------------------------------------------------------------- memoria

/** Para los tests (y para probar sin Cloudflare). */
export class MemoryRepo implements Repo {
  users: User[] = [];
  devs = new Map<string, Device & { user_id: number }>();
  sessions = new Map<string, { user_id: number; device_id: string }>();
  tickets = new Map<string, Ticket>();

  async userByName(name: string) {
    return this.users.find((u) => u.name === name) ?? null;
  }
  async createUser(name: string, keyHash: string, salt: string) {
    if (this.users.some((u) => u.name === name)) return null;
    const id = this.users.length + 1;
    this.users.push({ id, name, key_hash: keyHash, salt, fails: 0, locked_until: 0 });
    return id;
  }
  async setFails(userId: number, fails: number, lockedUntil: number) {
    const u = this.users.find((x) => x.id === userId)!;
    u.fails = fails;
    u.locked_until = lockedUntil;
  }
  async upsertDevice(userId: number, d: Device) {
    const k = `${userId}/${d.id}`;
    const old = this.devs.get(k);
    this.devs.set(k, old ? { ...old, name: d.name, kind: d.kind, last_seen: d.last_seen } : { ...d, user_id: userId });
  }
  async touchDevice(userId: number, id: string, patch: Partial<Pick<Device, "name" | "lan" | "server">>, now: number) {
    const d = this.devs.get(`${userId}/${id}`);
    if (d) Object.assign(d, Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)), { last_seen: now });
  }
  async device(userId: number, id: string) {
    const d = this.devs.get(`${userId}/${id}`);
    return d ? strip(d) : null;
  }
  async devices(userId: number) {
    return [...this.devs.values()].filter((d) => d.user_id === userId).sort((a, b) => b.last_seen - a.last_seen).map(strip);
  }
  async deleteDevice(userId: number, id: string) {
    this.devs.delete(`${userId}/${id}`);
    for (const [k, s] of this.sessions) if (s.user_id === userId && s.device_id === id) this.sessions.delete(k);
  }
  async createSession(hash: string, userId: number, deviceId: string) {
    this.sessions.set(hash, { user_id: userId, device_id: deviceId });
  }
  async session(hash: string) {
    const s = this.sessions.get(hash);
    if (!s) return null;
    return { ...s, user: this.users.find((u) => u.id === s.user_id)!.name };
  }
  async deleteSession(hash: string) {
    this.sessions.delete(hash);
  }
  async createTicket(hash: string, t: Ticket) {
    this.tickets.set(hash, t);
  }
  async takeTicket(hash: string) {
    const t = this.tickets.get(hash) ?? null;
    this.tickets.delete(hash);
    return t;
  }
}

function strip(d: Device & { user_id: number }): Device {
  const { user_id: _, ...rest } = d;
  return rest;
}
