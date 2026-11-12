-- Cuentas, aparatos, sesiones y boletos del tracker de Sync Tablet.
-- Aquí no se guarda ningún apunte: sólo quién es quién y dónde está.

CREATE TABLE users (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT NOT NULL UNIQUE,
  -- sha256(salt || clave derivada); la clave la deriva el aparato con PBKDF2.
  key_hash     TEXT NOT NULL,
  salt         TEXT NOT NULL,
  created      INTEGER NOT NULL,
  fails        INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE devices (
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  id        TEXT NOT NULL,
  name      TEXT NOT NULL,
  kind      TEXT NOT NULL,
  -- JSON: ["192.168.1.20:8788", …] tal como lo cuenta el aparato.
  lan       TEXT NOT NULL DEFAULT '[]',
  -- El aparato atiende a los demás (el servidor del PC).
  server    INTEGER NOT NULL DEFAULT 0,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (user_id, id)
);

CREATE TABLE sessions (
  -- sha256 del token: si se filtra la base, los tokens no sirven.
  hash      TEXT PRIMARY KEY,
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  created   INTEGER NOT NULL,
  last_used INTEGER NOT NULL
);
CREATE INDEX sessions_device ON sessions(user_id, device_id);

-- Un boleto deja que un aparato entre en otro de la misma cuenta sin darle su sesión.
CREATE TABLE tickets (
  hash      TEXT PRIMARY KEY,
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  from_dev  TEXT NOT NULL,
  for_dev   TEXT NOT NULL,
  expires   INTEGER NOT NULL
);
