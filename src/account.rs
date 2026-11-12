//! La cuenta del PC en el tracker (ver `tracker/PROTOCOLO.md`).
//!
//! * El PC avisa cada minuto de que está vivo y de su IP en la red local, así
//!   los aparatos de la misma cuenta lo encuentran sin escribir nada.
//! * Un aparato de la cuenta entra con un boleto de un solo uso que sólo este
//!   PC puede canjear; nunca le da su sesión.
//! * También contesta al sondeo UDP de la WiFi (`SYNCTABLET?`), que funciona
//!   aunque no haya internet.
//!
//! Se guarda en `.data/cuenta.json` (permisos 600).

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// El tracker publicado. Se puede cambiar al iniciar sesión (por ejemplo, uno local
/// con `npm run dev` en `tracker/`).
pub const DEFAULT_TRACKER: &str = "https://synctablet-tracker.sthifen.workers.dev";
const PROBE: &[u8] = b"SYNCTABLET?";

#[derive(Serialize, Deserialize, Default, Clone)]
struct Saved {
    device_id: String,
    #[serde(default)]
    tracker: String,
    #[serde(default)]
    user: String,
    #[serde(default)]
    session: String,
}

pub struct Account {
    path: PathBuf,
    hostname: String,
    saved: Mutex<Saved>,
}

fn random_hex(n: usize) -> String {
    use std::io::Read;
    let mut b = vec![0u8; n];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        let _ = f.read_exact(&mut b);
    }
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Lo que contesta el tracker: código HTTP y cuerpo.
fn call(method: &str, url: &str, session: Option<&str>, body: Option<Value>) -> Result<(u16, Value)> {
    let mut req = ureq::request(method, url).timeout(Duration::from_secs(10));
    if let Some(s) = session {
        req = req.set("Authorization", &format!("Bearer {s}"));
    }
    let res = match body {
        Some(b) => req.send_json(b),
        None => req.call(),
    };
    match res {
        Ok(r) => Ok((r.status(), r.into_json().unwrap_or(Value::Null))),
        Err(ureq::Error::Status(code, r)) => Ok((code, r.into_json().unwrap_or(Value::Null))),
        Err(e) => Err(anyhow!("sin conexión con el tracker: {e}")),
    }
}

fn error_of(v: &Value, code: u16) -> anyhow::Error {
    anyhow!("{}", v.get("error").and_then(Value::as_str).map(str::to_owned).unwrap_or_else(|| format!("error {code}")))
}

impl Account {
    pub fn open(root: &Path) -> Self {
        let path = root.join(".data/cuenta.json");
        let mut saved: Saved = std::fs::read_to_string(&path).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let fresh = saved.device_id.len() < 8;
        if fresh {
            saved.device_id = format!("pc-{}", random_hex(12));
        }
        let hostname = ["/etc/hostname", "/proc/sys/kernel/hostname"]
            .iter()
            .filter_map(|f| std::fs::read_to_string(f).ok())
            .map(|h| h.trim().to_string())
            .find(|h| !h.is_empty())
            .unwrap_or_default();
        let a = Self { path, hostname: if hostname.is_empty() { "PC".into() } else { hostname }, saved: Mutex::new(saved) };
        if fresh {
            a.save();
        }
        a
    }

    fn save(&self) {
        let s = self.saved.lock().unwrap().clone();
        if let Ok(text) = serde_json::to_string_pretty(&s) {
            let tmp = self.path.with_extension("tmp");
            if std::fs::write(&tmp, text).is_ok() {
                restrict(&tmp);
                let _ = std::fs::rename(&tmp, &self.path);
            }
        }
    }

    fn snapshot(&self) -> Saved {
        self.saved.lock().unwrap().clone()
    }

    pub fn user(&self) -> Option<String> {
        let s = self.saved.lock().unwrap();
        (!s.session.is_empty()).then(|| s.user.clone())
    }

    pub fn device_id(&self) -> String {
        self.saved.lock().unwrap().device_id.clone()
    }

    pub fn info(&self) -> Value {
        let s = self.snapshot();
        json!({
            "user": (!s.session.is_empty()).then_some(s.user),
            "tracker": if s.tracker.is_empty() { DEFAULT_TRACKER.to_string() } else { s.tracker },
            "device_id": s.device_id,
            "name": self.hostname,
        })
    }

    /// Entra (o crea la cuenta). `key` es la clave derivada que calculó el visor.
    pub fn login(&self, tracker: &str, user: &str, key: &str, register: bool) -> Result<String> {
        let saved = self.tracker();
        let tracker = tracker.trim().trim_end_matches('/');
        let tracker = if tracker.is_empty() { saved.as_str() } else { tracker };
        if !(tracker.starts_with("https://") || tracker.starts_with("http://")) {
            return Err(anyhow!("la dirección del tracker debe empezar por https://"));
        }
        let device = json!({ "id": self.device_id(), "name": self.hostname, "kind": "pc" });
        let path = if register { "/v1/register" } else { "/v1/login" };
        let (code, v) = call("POST", &format!("{tracker}{path}"), None, Some(json!({ "user": user, "key": key, "device": device })))?;
        if !(200..300).contains(&code) {
            return Err(error_of(&v, code));
        }
        let session = v["session"].as_str().ok_or_else(|| anyhow!("respuesta rara del tracker"))?.to_string();
        let user = v["user"].as_str().unwrap_or(user).to_string();
        {
            let mut s = self.saved.lock().unwrap();
            s.tracker = tracker.to_string();
            s.user = user.clone();
            s.session = session;
        }
        self.save();
        Ok(user)
    }

    /// El tracker que se acaba de publicar pasa a ser el de este PC (y el que
    /// se ofrece a los aparatos que lo encuentran por WiFi).
    pub fn set_tracker(&self, url: &str) {
        self.saved.lock().unwrap().tracker = url.trim_end_matches('/').to_string();
        self.save();
    }

    pub fn tracker(&self) -> String {
        let t = self.saved.lock().unwrap().tracker.clone();
        if t.is_empty() { DEFAULT_TRACKER.to_string() } else { t }
    }

    pub fn logout(&self) {
        let s = self.snapshot();
        if !s.session.is_empty() {
            let _ = call("POST", &format!("{}/v1/logout", s.tracker), Some(&s.session), Some(json!({})));
        }
        self.forget();
    }

    fn forget(&self) {
        {
            let mut s = self.saved.lock().unwrap();
            s.session.clear();
            s.user.clear();
        }
        self.save();
    }

    /// Una llamada con la sesión del PC. Si el tracker la rechaza, se olvida.
    fn authed(&self, method: &str, path: &str, body: Option<Value>) -> Result<Value> {
        let s = self.snapshot();
        if s.session.is_empty() {
            return Err(anyhow!("este PC no ha iniciado sesión"));
        }
        let (code, v) = call(method, &format!("{}{path}", s.tracker), Some(&s.session), body)?;
        if code == 401 {
            tracing::warn!("el tracker cerró la sesión de este PC");
            self.forget();
        }
        if !(200..300).contains(&code) {
            return Err(error_of(&v, code));
        }
        Ok(v)
    }

    pub fn heartbeat(&self, lan: Option<String>, port: u16) -> Result<()> {
        let lan: Vec<String> = lan.into_iter().map(|ip| format!("{ip}:{port}")).collect();
        self.authed("POST", "/v1/heartbeat", Some(json!({ "name": self.hostname, "lan": lan, "server": true })))?;
        Ok(())
    }

    pub fn devices(&self) -> Result<Value> {
        self.authed("GET", "/v1/devices", None)
    }

    pub fn remove_device(&self, id: &str) -> Result<Value> {
        if !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
            return Err(anyhow!("aparato no válido"));
        }
        self.authed("DELETE", &format!("/v1/devices/{id}"), None)
    }

    /// Canjea el boleto de un aparato: si es de esta cuenta y para este PC, vale.
    pub fn redeem(&self, ticket: &str) -> Result<Value> {
        self.authed("POST", "/v1/tickets/redeem", Some(json!({ "ticket": ticket })))
    }

    /// Lo que se contesta al sondeo UDP.
    pub fn probe_reply(&self, port: u16) -> Vec<u8> {
        json!({ "t": "synctablet", "v": 1, "name": self.hostname, "port": port, "device": self.device_id(), "user": self.user(), "tracker": self.tracker() })
            .to_string()
            .into_bytes()
    }
}

pub fn is_probe(msg: &[u8]) -> bool {
    msg.starts_with(PROBE)
}

#[cfg(unix)]
fn restrict(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}

/// Contesta en la WiFi a quien pregunte por un PC con Sync Tablet.
pub async fn answer_probes(account: std::sync::Arc<crate::server::AppState>, port: u16) {
    let socket = match tokio::net::UdpSocket::bind(("0.0.0.0", port)).await {
        Ok(s) => s,
        Err(e) => {
            tracing::warn!("sin búsqueda por WiFi (UDP {port}): {e}");
            return;
        }
    };
    let mut buf = [0u8; 64];
    loop {
        let Ok((n, peer)) = socket.recv_from(&mut buf).await else { continue };
        if is_probe(&buf[..n]) {
            let _ = socket.send_to(&account.account.probe_reply(port), peer).await;
        }
    }
}

/// Avisa al tracker cada minuto (sólo si hay sesión).
pub async fn heartbeats(state: std::sync::Arc<crate::server::AppState>, port: u16) {
    let mut every = tokio::time::interval(Duration::from_secs(60));
    loop {
        every.tick().await;
        if state.account.user().is_none() {
            continue;
        }
        let s = state.clone();
        let r = tokio::task::spawn_blocking(move || s.account.heartbeat(crate::server::lan_ip(), port)).await;
        if let Ok(Err(e)) = r {
            tracing::debug!("aviso al tracker: {e:#}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn el_id_del_pc_se_guarda_y_no_cambia() {
        let dir = std::env::temp_dir().join(format!("st-cuenta-{}", random_hex(4)));
        std::fs::create_dir_all(dir.join(".data")).unwrap();
        let a = Account::open(&dir);
        let id = a.device_id();
        assert!(id.starts_with("pc-") && id.len() == 27);
        assert_eq!(Account::open(&dir).device_id(), id);
        assert!(a.user().is_none());
        let reply: Value = serde_json::from_slice(&a.probe_reply(8788)).unwrap();
        assert_eq!(reply["t"], "synctablet");
        assert_eq!(reply["device"], id.as_str());
        assert!(reply["user"].is_null());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn solo_contesta_al_sondeo() {
        assert!(is_probe(b"SYNCTABLET? v1"));
        assert!(!is_probe(b"hola"));
    }
}
