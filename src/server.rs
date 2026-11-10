//! HTTP del PC:
//!
//! * `/ws/ink`: la tablet. Al conectar manda `hello` con la versión de cada
//!   página; el PC le pide (`want`) las que tiene atrasadas y después recibe
//!   cada trazo y borrado según ocurren.
//! * `/ws/live`: los navegadores con el visor; reciben lo mismo en vivo.
//! * `/api/…`: cuadernos, páginas en JSON y en SVG, para el visor.
//! * `/api/notes…`: las notas escritas en el PC (ver `notes.rs`). Los cambios
//!   se avisan por `/ws/live` con `notes` (lista) y `note` (una nota).

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::Router;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, Path, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use serde::Deserialize;
use serde_json::json;
use tokio::sync::broadcast;

use crate::auth::{self, Auth};
use crate::ink::{Applied, Msg};
use crate::notes::{Notes, Update};
use crate::store::Store;

pub struct AppState {
    pub store: Mutex<Store>,
    pub notes: Mutex<Notes>,
    pub live: broadcast::Sender<String>,
    /// Tablets conectadas ahora, para el visor que se abre a mitad.
    pub devices: Mutex<Vec<String>>,
    pub auth: Auth,
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/n/{id}", get(index))
        .route("/fonts/inter.woff2", get(font))
        .route("/api/notes", get(list_notes).post(create_note))
        .route("/api/notes/{id}", get(get_note).put(update_note).delete(delete_note))
        .route("/api/notebooks", get(notebooks))
        .route("/api/page/{id}", get(page_json))
        .route("/api/page/{id}/svg", get(page_svg))
        .route("/api/asset/{file}", get(asset))
        .route("/api/pc/stroke", post(pc_stroke))
        .route("/ws/ink", get(ws_ink))
        .route("/ws/live", get(ws_live))
        .route("/api/pair", get(pair_usb).post(pair_code))
        .route("/api/pair/code", get(pair_show))
        .layer(axum::middleware::from_fn_with_state(state.clone(), auth::guard))
        .with_state(state)
}

async fn index() -> Response {
    ([(header::CACHE_CONTROL, "no-cache")], Html(include_str!("../web/index.html"))).into_response()
}

async fn font() -> Response {
    const INTER: &[u8] = include_bytes!("../web/fonts/InterVariable.woff2");
    ([(header::CONTENT_TYPE, "font/woff2"), (header::CACHE_CONTROL, "max-age=31536000, immutable")], INTER).into_response()
}

// ------------------------------------------------------------- emparejado

/// La tablet por USB (llega como este mismo PC) recibe el token sin más.
async fn pair_usb(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if !auth::is_local(&peer) {
        return StatusCode::FORBIDDEN.into_response();
    }
    axum::Json(json!({ "token": s.auth.token() })).into_response()
}

#[derive(Deserialize)]
struct PairRequest {
    code: String,
}

/// Por WiFi, el código que enseña el visor del PC se canjea por el token.
async fn pair_code(State(s): State<Arc<AppState>>, axum::Json(req): axum::Json<PairRequest>) -> Response {
    match s.auth.redeem(&req.code) {
        Some(token) => axum::Json(json!({ "token": token })).into_response(),
        None => StatusCode::FORBIDDEN.into_response(),
    }
}

/// El código, sólo para el visor abierto en este PC.
async fn pair_show(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if !auth::is_local(&peer) {
        return StatusCode::FORBIDDEN.into_response();
    }
    axum::Json(json!({ "code": s.auth.code(), "ip": lan_ip() })).into_response()
}

/// La IP de este PC en su red local. Se pregunta por el camino hacia la puerta
/// de enlace y no hacia internet, que puede ir por una VPN a la que la tablet no
/// llega. Conectar un socket UDP no envía nada.
fn lan_ip() -> Option<String> {
    let routes = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
    let target = default_gateway(&routes).unwrap_or(std::net::Ipv4Addr::new(192, 0, 2, 1));
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect((target, 9)).ok()?;
    Some(socket.local_addr().ok()?.ip().to_string())
}

/// La puerta de enlace de la ruta por defecto con menor métrica.
fn default_gateway(routes: &str) -> Option<std::net::Ipv4Addr> {
    routes
        .lines()
        .skip(1)
        .filter_map(|line| {
            let f: Vec<&str> = line.split_whitespace().collect();
            let (destination, gateway, metric) = (*f.get(1)?, *f.get(2)?, *f.get(6)?);
            if destination != "00000000" || gateway == "00000000" {
                return None;
            }
            let ip = u32::from_str_radix(gateway, 16).ok()?;
            Some((metric.parse::<u32>().ok()?, std::net::Ipv4Addr::from(ip.to_le_bytes())))
        })
        .min_by_key(|(metric, _)| *metric)
        .map(|(_, ip)| ip)
}

// ------------------------------------------------------------------ notas

/// Quién hizo el cambio, para que su propia pestaña no se recargue.
#[derive(Deserialize, Default)]
struct Who {
    #[serde(default)]
    client: String,
    #[serde(default)]
    title: String,
}

#[derive(Deserialize)]
struct NoteUpdate {
    #[serde(flatten)]
    up: Update,
    #[serde(default)]
    client: String,
}

fn failed(e: anyhow::Error) -> Response {
    tracing::warn!("no se pudo guardar la nota: {e:#}");
    (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response()
}

async fn list_notes(State(s): State<Arc<AppState>>) -> Response {
    axum::Json(s.notes.lock().unwrap().list()).into_response()
}

async fn create_note(State(s): State<Arc<AppState>>, body: Option<axum::Json<Who>>) -> Response {
    let who = body.map(|b| b.0).unwrap_or_default();
    let created = s.notes.lock().unwrap().create(who.title);
    match created {
        Ok(note) => {
            let _ = s.live.send(json!({ "t": "notes", "client": who.client }).to_string());
            axum::Json(note).into_response()
        }
        Err(e) => failed(e),
    }
}

async fn get_note(State(s): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    match s.notes.lock().unwrap().get(&id) {
        Some(n) => axum::Json(n).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn update_note(State(s): State<Arc<AppState>>, Path(id): Path<String>, axum::Json(req): axum::Json<NoteUpdate>) -> Response {
    let res = s.notes.lock().unwrap().update(&id, req.up);
    match res {
        Ok((renamed, updated)) => {
            let _ = s.live.send(json!({ "t": "note", "id": id, "client": req.client }).to_string());
            if renamed {
                let _ = s.live.send(json!({ "t": "notes", "client": req.client }).to_string());
            }
            axum::Json(json!({ "updated": updated })).into_response()
        }
        Err(e) => failed(e),
    }
}

async fn delete_note(State(s): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    let res = s.notes.lock().unwrap().delete(&id);
    match res {
        Ok(true) => {
            let _ = s.live.send(json!({ "t": "notes", "deleted": id }).to_string());
            StatusCode::NO_CONTENT.into_response()
        }
        Ok(false) => StatusCode::NOT_FOUND.into_response(),
        Err(e) => failed(e),
    }
}

// ------------------------------------------------------------------ apuntes a mano

async fn notebooks(State(s): State<Arc<AppState>>) -> Response {
    let store = s.store.lock().unwrap();
    let versions = store.manifest();
    let device = s.devices.lock().unwrap().first().cloned();
    axum::Json(json!({ "notebooks": store.notebooks(), "versions": versions, "dir": store.root(), "device": device }))
        .into_response()
}

async fn page_json(State(s): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    match s.store.lock().unwrap().page(&id) {
        Some(p) => axum::Json(p).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn page_svg(State(s): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    match s.store.lock().unwrap().page(&id) {
        Some(p) => ([(header::CONTENT_TYPE, "image/svg+xml")], crate::svg::page_svg(p, "/api/asset/")).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn asset(State(s): State<Arc<AppState>>, Path(file): Path<String>) -> Response {
    let id = file.strip_suffix(".jpg").unwrap_or(&file);
    let path = s.store.lock().unwrap().asset_path(id);
    match path.map(std::fs::read) {
        Some(Ok(bytes)) => ([(header::CONTENT_TYPE, "image/jpeg"), (header::CACHE_CONTROL, "max-age=31536000, immutable")], bytes)
            .into_response(),
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Un trazo dibujado en el visor del PC: va a la tablet, que lo hace suyo y lo
/// devuelve como un trazo normal (así las versiones de la página no chocan).
#[derive(Deserialize)]
struct PcStroke {
    page: String,
    stroke: crate::ink::Stroke,
}

async fn pc_stroke(State(s): State<Arc<AppState>>, axum::Json(req): axum::Json<PcStroke>) -> Response {
    if s.devices.lock().unwrap().is_empty() {
        return (StatusCode::CONFLICT, "Conecta la tablet para escribir desde el PC").into_response();
    }
    if req.stroke.points.len() < 3 || req.stroke.points.len() > 30_000 {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let _ = s.live.send(json!({ "t": "pc_stroke", "page": req.page, "stroke": req.stroke }).to_string());
    StatusCode::NO_CONTENT.into_response()
}

async fn ws_live(ws: WebSocketUpgrade, State(s): State<Arc<AppState>>) -> Response {
    ws.on_upgrade(move |mut socket| async move {
        let mut rx = s.live.subscribe();
        loop {
            tokio::select! {
                msg = rx.recv() => match msg {
                    Ok(text) => {
                        if socket.send(Message::Text(text.into())).await.is_err() {
                            break;
                        }
                    }
                    // Un navegador lento se perdió mensajes: que recargue la página.
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        let _ = socket.send(Message::Text(json!({ "t": "reload" }).to_string().into())).await;
                    }
                    Err(_) => break,
                },
                incoming = socket.recv() => if !matches!(incoming, Some(Ok(_))) { break },
            }
        }
    })
}

async fn ws_ink(
    ws: WebSocketUpgrade,
    State(s): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
) -> Response {
    ws.on_upgrade(move |socket| tablet(socket, s, peer))
}

/// Avisos del visor que le interesan a la tablet: notas cambiadas en el PC
/// (no las que cambió ella misma).
fn for_tablet(text: &str) -> bool {
    let Ok(v) = serde_json::from_str::<serde_json::Value>(text) else { return false };
    matches!(v["t"].as_str(), Some("note" | "notes" | "pc_stroke")) && v["client"].as_str() != Some("tablet")
}

async fn tablet(mut socket: WebSocket, s: Arc<AppState>, peer: SocketAddr) {
    let mut device = String::new();
    let mut live = s.live.subscribe();
    'session: loop {
        let msg = tokio::select! {
            m = socket.recv() => match m {
                Some(Ok(m)) => m,
                _ => break,
            },
            note = live.recv() => {
                match note {
                    Ok(text) if for_tablet(&text) => {
                        if socket.send(Message::Text(text.into())).await.is_err() {
                            break;
                        }
                    }
                    // Se perdió algún aviso: que la tablet revise todas.
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        let _ = socket.send(Message::Text(json!({ "t": "notes" }).to_string().into())).await;
                    }
                    _ => {}
                }
                continue;
            }
        };
        let Message::Text(text) = msg else { continue };
        let parsed: Msg = match serde_json::from_str(&text) {
            Ok(m) => m,
            Err(e) => {
                tracing::debug!("mensaje de la tablet inválido: {e}");
                continue;
            }
        };
        let reply = match handle(&s, &parsed, &text) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("no se pudo guardar: {e:#}");
                None
            }
        };
        if let Msg::Hello { device: d, .. } = &parsed {
            device = if d.is_empty() { "Tablet".into() } else { d.clone() };
            s.devices.lock().unwrap().push(device.clone());
            tracing::info!("{device} conectada ({peer})");
            let _ = s.live.send(json!({ "t": "device", "name": device, "connected": true }).to_string());
        }
        if let Some(r) = reply {
            for line in r.lines() {
                if socket.send(Message::Text(line.to_owned().into())).await.is_err() {
                    break 'session;
                }
            }
        }
    }
    if device.is_empty() {
        return;
    }
    tracing::info!("{device} desconectada");
    let mut devices = s.devices.lock().unwrap();
    if let Some(i) = devices.iter().position(|d| *d == device) {
        devices.remove(i);
    }
    let (connected, name) = match devices.first() {
        Some(other) => (true, other.clone()),
        None => (false, device),
    };
    let _ = s.live.send(json!({ "t": "device", "name": name, "connected": connected }).to_string());
}

/// Aplica un mensaje de la tablet; devuelve la respuesta para ella, si hay.
fn handle(s: &AppState, msg: &Msg, raw: &str) -> anyhow::Result<Option<String>> {
    let mut store = s.store.lock().unwrap();
    let want = |ids: Vec<&str>| Some(json!({ "t": "want", "pages": ids }).to_string());
    let applied = match msg {
        Msg::Hello { pages, .. } => {
            let have = store.manifest();
            let mut missing: Vec<&str> =
                pages.iter().filter(|(id, v)| have.get(*id).is_none_or(|h| h < v)).map(|(id, _)| id.as_str()).collect();
            missing.sort();
            // Imágenes que no llegaron (una subida cortada, un PC nuevo…).
            let mut assets: Vec<String> = Vec::new();
            for id in have.keys() {
                if let Some(p) = store.page(id) {
                    assets.extend(store.missing_assets(p));
                }
            }
            assets.sort();
            assets.dedup();
            let mut out = want(missing).unwrap();
            if !assets.is_empty() {
                out.push('\n');
                out.push_str(&json!({ "t": "want_assets", "ids": assets }).to_string());
            }
            return Ok(Some(out));
        }
        Msg::Notebook { notebook } => {
            store.put_notebook(notebook.clone())?;
            Applied::Ok
        }
        Msg::Page { page } => {
            store.put_page(page.clone())?;
            let missing = store.missing_assets(page);
            if !missing.is_empty() {
                let _ = s.live.send(raw.to_owned());
                return Ok(Some(json!({ "t": "want_assets", "ids": missing }).to_string()));
            }
            Applied::Ok
        }
        Msg::Asset { id, data, .. } => {
            store.put_asset(id, data)?;
            // Al visor sólo el aviso, no la imagen entera.
            let _ = s.live.send(json!({ "t": "asset", "id": id }).to_string());
            return Ok(None);
        }
        Msg::Stroke { page, version, stroke } => store.add_stroke(page, *version, stroke.clone())?,
        Msg::Erase { page, version, ids } => store.erase(page, *version, ids)?,
        Msg::Laser { .. } => {
            let _ = s.live.send(raw.to_owned());
            return Ok(None);
        }
        Msg::DeletePage { page } => {
            store.delete_page(page)?;
            Applied::Ok
        }
        Msg::DeleteNotebook { notebook } => {
            store.delete_notebook(notebook)?;
            Applied::Ok
        }
    };
    match applied {
        Applied::Ok => {
            let _ = s.live.send(raw.to_owned());
            Ok(None)
        }
        Applied::Stale => Ok(None),
        Applied::Gap => {
            let page = match msg {
                Msg::Stroke { page, .. } | Msg::Erase { page, .. } => page.as_str(),
                _ => return Ok(None),
            };
            Ok(want(vec![page]))
        }
    }
}

#[cfg(test)]
mod lan_tests {
    use super::default_gateway;

    #[test]
    fn elige_la_puerta_de_enlace_de_menor_metrica() {
        let routes = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\n\
                      CloudflareWARP\t00000000\t00000000\t0001\t0\t0\t0\t00000000\n\
                      wlp4s0\t00000000\t0200A8C0\t0003\t0\t0\t600\t00000000\n\
                      eno1\t00000000\t0100A8C0\t0003\t0\t0\t100\t00000000\n\
                      eno1\t0000A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\n";
        assert_eq!(default_gateway(routes), Some(std::net::Ipv4Addr::new(192, 168, 0, 1)));
        assert_eq!(default_gateway(""), None);
    }
}
