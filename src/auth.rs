//! Quién puede hablar con el servidor, que escucha en toda la red.
//!
//! * Desde este PC (también la tablet por USB, que llega por `adb reverse`) no
//!   hace falta nada: basta el `Host` y el `Origin` correctos, que son lo que
//!   frena a una web ajena abierta en el navegador.
//! * Desde otro equipo hace falta el token de `.data/token`. La tablet lo recibe
//!   sola por USB; por WiFi lo canjea una vez por el código que enseña el PC.
//! * Un navegador de otro equipo lo recibe abriendo `/?token=…`, que lo guarda
//!   en una cookie.

use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::sync::Mutex;

use anyhow::{Context, Result};
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use std::sync::Arc;

use crate::server::AppState;

/// Fallos seguidos antes de cambiar el código.
const MAX_TRIES: u32 = 5;

pub struct Auth {
    token: String,
    /// Nombre de este PC, que también vale como `Host`.
    hostname: String,
    pairing: Mutex<Pairing>,
}

struct Pairing {
    code: String,
    fails: u32,
}

fn random_bytes(n: usize) -> Result<Vec<u8>> {
    use std::io::Read;
    let mut buf = vec![0; n];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut buf)?;
    Ok(buf)
}

fn new_code() -> String {
    let b = random_bytes(4).unwrap_or_default();
    let n = b.iter().fold(0u32, |acc, x| acc.wrapping_mul(256).wrapping_add(*x as u32));
    format!("{:06}", n % 1_000_000)
}

impl Auth {
    /// Lee el token de la carpeta de datos o crea uno.
    pub fn open(root: &Path) -> Result<Self> {
        let path = root.join(".data/token");
        let token = match std::fs::read_to_string(&path) {
            Ok(t) if t.trim().len() >= 32 => t.trim().to_string(),
            _ => {
                let t: String = random_bytes(32)?.iter().map(|b| format!("{b:02x}")).collect();
                std::fs::write(&path, &t).with_context(|| format!("no se pudo escribir {}", path.display()))?;
                restrict(&path);
                t
            }
        };
        let hostname = std::fs::read_to_string("/etc/hostname").unwrap_or_default().trim().to_lowercase();
        Ok(Self { token, hostname, pairing: Mutex::new(Pairing { code: new_code(), fails: 0 }) })
    }

    pub fn token(&self) -> &str {
        &self.token
    }

    pub fn code(&self) -> String {
        self.pairing.lock().unwrap().code.clone()
    }

    /// Canjea el código por el token. Tras varios fallos el código cambia.
    pub fn redeem(&self, code: &str) -> Option<&str> {
        let mut p = self.pairing.lock().unwrap();
        if same(code.trim(), &p.code) {
            *p = Pairing { code: new_code(), fails: 0 };
            return Some(&self.token);
        }
        p.fails += 1;
        if p.fails >= MAX_TRIES {
            *p = Pairing { code: new_code(), fails: 0 };
        }
        None
    }

    fn host_allowed(&self, host: &str) -> bool {
        let name = strip_port(host).to_lowercase();
        name == "localhost"
            || name.parse::<IpAddr>().is_ok()
            || name.trim_matches(['[', ']']).parse::<IpAddr>().is_ok()
            || name.ends_with(".local")
            || (!self.hostname.is_empty() && name == self.hostname)
    }

    fn presented(&self, headers: &HeaderMap, query: Option<&str>) -> bool {
        let bearer = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer "));
        let cookie = headers.get(header::COOKIE).and_then(|v| v.to_str().ok()).and_then(cookie_token);
        [bearer, cookie, query.and_then(query_token)].into_iter().flatten().any(|t| same(t, &self.token))
    }
}

#[cfg(unix)]
fn restrict(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}

/// Comparación que no se delata por el tiempo que tarda.
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn strip_port(host: &str) -> &str {
    if host.starts_with('[') {
        return host.split_once(']').map_or(host, |(ip, _)| &host[..ip.len() + 1]);
    }
    host.rsplit_once(':').map_or(host, |(name, _)| name)
}

fn cookie_token(cookies: &str) -> Option<&str> {
    cookies.split(';').find_map(|c| c.trim().strip_prefix("st_token="))
}

fn query_token(query: &str) -> Option<&str> {
    query.split('&').find_map(|kv| kv.strip_prefix("token="))
}

/// `Origin` sólo es válido si es este mismo servidor.
fn origin_ok(origin: &str, host: &str) -> bool {
    origin.split_once("://").is_some_and(|(_, rest)| rest.eq_ignore_ascii_case(host))
}

pub fn is_local(peer: &SocketAddr) -> bool {
    peer.ip().to_canonical().is_loopback()
}

/// Rutas que no enseñan datos: la página y su fuente, y el emparejado.
fn is_public(path: &str) -> bool {
    !(path.starts_with("/api/") || path.starts_with("/ws/")) || path == "/api/pair"
}

pub async fn guard(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, req: Request, next: Next) -> Response {
    let headers = req.headers();
    let host = headers.get(header::HOST).and_then(|v| v.to_str().ok()).unwrap_or("");
    if !s.auth.host_allowed(host) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if let Some(origin) = headers.get(header::ORIGIN) {
        if !origin.to_str().is_ok_and(|o| origin_ok(o, host)) {
            return StatusCode::FORBIDDEN.into_response();
        }
    }
    let path = req.uri().path();
    let query = req.uri().query();
    let authorized = s.auth.presented(headers, query);
    if !is_public(path) && !is_local(&peer) && !authorized {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let remember = authorized && !path.starts_with("/api/") && !path.starts_with("/ws/") && query.is_some_and(|q| query_token(q).is_some());
    let mut res = next.run(req).await;
    let out = res.headers_mut();
    out.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    if remember {
        let cookie = format!("st_token={}; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000", s.auth.token());
        if let Ok(v) = HeaderValue::from_str(&cookie) {
            out.insert(header::SET_COOKIE, v);
        }
    }
    res
}

#[cfg(test)]
mod tests {
    use super::*;

    fn auth() -> Auth {
        Auth {
            token: "t".repeat(64),
            hostname: "fedora".into(),
            pairing: Mutex::new(Pairing { code: "123456".into(), fails: 0 }),
        }
    }

    #[test]
    fn solo_hosts_propios() {
        let a = auth();
        for ok in ["localhost:8788", "127.0.0.1:8788", "192.168.1.20:8788", "[::1]:8788", "fedora:8788", "pc.local:8788", "LOCALHOST"] {
            assert!(a.host_allowed(ok), "{ok}");
        }
        // Un dominio que apunta aquí es un ataque de DNS rebinding.
        for bad in ["evil.com", "evil.com:8788", "localhost.evil.com:8788", ""] {
            assert!(!a.host_allowed(bad), "{bad}");
        }
    }

    #[test]
    fn origin_debe_ser_el_mismo() {
        assert!(origin_ok("http://localhost:8788", "localhost:8788"));
        assert!(!origin_ok("https://evil.com", "localhost:8788"));
        assert!(!origin_ok("null", "localhost:8788"));
        assert!(!origin_ok("http://localhost:9999", "localhost:8788"));
    }

    #[test]
    fn el_token_llega_por_cabecera_cookie_o_query() {
        let a = auth();
        let t = "t".repeat(64);
        let mut h = HeaderMap::new();
        assert!(!a.presented(&h, None));
        assert!(!a.presented(&h, Some("token=malo")));
        assert!(a.presented(&h, Some(&format!("x=1&token={t}"))));
        h.insert(header::COOKIE, format!("otra=1; st_token={t}").parse().unwrap());
        assert!(a.presented(&h, None));
        let mut h = HeaderMap::new();
        h.insert(header::AUTHORIZATION, format!("Bearer {t}").parse().unwrap());
        assert!(a.presented(&h, None));
    }

    #[test]
    fn el_codigo_se_usa_una_vez_y_cambia_tras_fallar() {
        let a = auth();
        assert!(a.redeem("000000").is_none());
        assert!(a.redeem("123456").is_some());
        assert!(a.redeem("123456").is_none(), "ya se usó");
        let before = a.code();
        for _ in 0..MAX_TRIES {
            a.redeem("xxxxxx");
        }
        assert_ne!(a.code(), before);
    }

    #[test]
    fn publico_solo_lo_que_no_enseña_datos() {
        for p in ["/", "/n/abc", "/fonts/inter.woff2", "/api/pair"] {
            assert!(is_public(p), "{p}");
        }
        for p in ["/api/notes", "/api/pair/code", "/api/asset/a.jpg", "/ws/ink", "/ws/live"] {
            assert!(!is_public(p), "{p}");
        }
    }
}
