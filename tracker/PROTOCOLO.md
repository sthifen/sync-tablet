# Tracker de Sync Tablet — protocolo v1

El tracker sólo sabe **quién es quién y dónde está**: cuentas, aparatos y
boletos. Los apuntes nunca pasan por aquí. Cualquier servidor que cumpla este
contrato sirve (hoy: Cloudflare Workers + D1; mañana: un VPS, Postgres…). La
lógica vive en `src/api.ts` y sólo depende de la interfaz `Repo` (`src/repo.ts`).

Todo es JSON. Errores: `{"error": "mensaje para enseñar"}` con su código HTTP.
Las rutas con 🔒 llevan `Authorization: Bearer <session>`.

## Contraseñas

La contraseña **no sale del aparato**. Cada cliente manda una clave derivada:

    key = hex(PBKDF2-HMAC-SHA256(password, "synctablet:" + user, 200000, 32 bytes))

(`user` en minúsculas). El tracker guarda `sha256(salt + key)` con una sal
aleatoria. Vector de prueba: `("ana", "secreto123")` →
`c247987cde07c49e2649d6aabd24afb4948ccd98fa5e8456f74fef4b52d4089f`.

## Rutas

| Ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /v1/health` | | `{ok, version}` |
| `POST /v1/register` | `{user, key, device}` | 201 `{user, device_id, session}`; 409 si existe |
| `POST /v1/login` | `{user, key, device}` | `{user, device_id, session}`; 5 fallos → 429 durante 5 min |
| 🔒 `GET /v1/me` | | `{user, device}` |
| 🔒 `POST /v1/logout` | | `{ok}` |
| 🔒 `POST /v1/heartbeat` | `{name?, lan?: ["ip:puerto"], server?}` | `{ok, online_ms}` |
| 🔒 `GET /v1/devices` | | `{devices: [Device]}` |
| 🔒 `DELETE /v1/devices/{id}` | | `{ok}` (y cierra sus sesiones) |
| 🔒 `POST /v1/tickets` | `{for: device_id}` | `{ticket, expires}` (2 min, un uso) |
| 🔒 `POST /v1/tickets/redeem` | `{ticket}` | `{user, device}` si el boleto es para quien lo canjea |

`device` (entrada): `{id: [A-Za-z0-9_-]{8,64}, name, kind: pc|desktop|tablet|phone}`.
El id lo inventa el aparato una vez y lo guarda.

`Device` (salida): `{id, name, kind, lan, server, last_seen, online, me}`.
`online` = avisó hace menos de 3 minutos. Los aparatos avisan cada minuto.

## Cómo entra un aparato en el PC de su cuenta

1. Busca el PC: sondeo UDP `SYNCTABLET?` al puerto del servidor (8788) en la
   WiFi, que contesta `{t:"synctablet", name, port, device, user}`; o la `lan`
   de los aparatos `server` en línea de `/v1/devices`.
2. Pide un boleto `POST /v1/tickets {for: <id del PC>}`.
3. Lo manda al PC: `POST http://<pc>:8788/api/pair/account {ticket}`.
4. El PC lo canjea con **su** sesión (`/v1/tickets/redeem`). Si vale, le da el
   token local, como por USB.

Un aparato que se haga pasar por el PC en la WiFi sólo recibe un boleto que no
puede canjear: la sesión de nadie viaja por la red local.

## Cambiar de proveedor

Implementa `Repo` para la base nueva y llama a `handle(request, repo)` desde el
servidor HTTP que sea (Node, Deno, Bun…). Los clientes sólo necesitan la URL
nueva en «Servidor de cuentas».
