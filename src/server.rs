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
use anyhow::Context as _;
use tokio::sync::broadcast;

use crate::auth::{self, Auth};
use crate::ink::{Applied, Msg};
use crate::notes::{Notes, Update};
use crate::store::Store;

pub struct AppState {
    pub store: Mutex<Store>,
    pub notes: Mutex<Notes>,
    pub live: broadcast::Sender<String>,
    /// Entre aparatos (tablet ↔ app del PC): lo que hizo uno, con su número de conexión.
    pub ink: broadcast::Sender<(u64, String)>,
    pub next_conn: std::sync::atomic::AtomicU64,
    /// El portapapeles del PC (None si no hay X11/XWayland).
    pub clipboard: Option<Mutex<crate::clipboard::Clipboard>>,
    /// Tablets conectadas ahora, para el visor que se abre a mitad.
    pub devices: Mutex<Vec<String>>,
    pub auth: Auth,
    pub account: crate::account::Account,
    /// Puerto HTTP (el que se anuncia al tracker).
    pub port: u16,
    /// Carpeta de los apuntes.
    pub root: std::path::PathBuf,
    pub tracker_setup: crate::tracker_setup::Shared,
    /// Cuadernos compartidos con otras personas (nodo P2P).
    pub shared: Arc<crate::shared::Shared>,
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
        .route("/api/pair/qr.svg", get(pair_qr))
        .route("/api/pair/qr.png", get(pair_qr_png))
        .route("/api/pair/account", post(pair_account))
        .route("/api/account", get(account_info))
        .route("/api/tracker/setup", get(setup_status).post(setup_start))
        .route("/api/shared", get(shared_list))
        .route("/api/shared/share", post(shared_share))
        .route("/api/shared/join", post(shared_join))
        .route("/api/shared/{doc}/links", get(shared_links))
        .route("/api/shared/{doc}/qr.png", get(shared_qr))
        .route("/api/shared/{doc}/keep", post(shared_keep))
        .route("/api/shared/{doc}/leave", post(shared_leave))
        .route("/api/account/login", post(account_login))
        .route("/api/account/logout", post(account_logout))
        .route("/api/account/devices", get(account_devices))
        .route("/api/account/devices/{id}", axum::routing::delete(account_remove))
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
    let (code, left) = s.auth.current();
    let ip = lan_ip();
    axum::Json(json!({ "code": code, "ip": ip, "expires_in": left, "link": pair_link(&s, ip.as_deref(), &code) })).into_response()
}

/// Lo que lleva el QR: todo lo que la app necesita, sin que nadie escriba nada.
fn pair_link(s: &AppState, ip: Option<&str>, code: &str) -> String {
    fn enc(v: &str) -> String {
        v.bytes()
            .map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
            .collect()
    }
    let name = s.account.info()["name"].as_str().unwrap_or("PC").to_string();
    format!(
        "synctablet://pair?h={}&p={}&c={}&n={}&t={}",
        enc(ip.unwrap_or("")),
        s.port,
        enc(code),
        enc(&name),
        enc(&s.account.tracker())
    )
}

/// El QR en PNG (para la app de escritorio, que no dibuja SVG).
async fn pair_qr_png(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if !auth::is_local(&peer) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let (code, _) = s.auth.current();
    let link = pair_link(&s, lan_ip().as_deref(), &code);
    match qr_png(&link, 10) {
        Some(png) => ([(header::CONTENT_TYPE, "image/png"), (header::CACHE_CONTROL, "no-store")], png).into_response(),
        None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

/// Cada módulo del QR, `scale` píxeles; con margen blanco de 4 módulos.
fn qr_png(text: &str, scale: u32) -> Option<Vec<u8>> {
    let qr = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M).ok()?;
    let n = qr.width() as u32;
    let side = (n + 8) * scale;
    let colors = qr.to_colors();
    let img = image::GrayImage::from_fn(side, side, |x, y| {
        let (mx, my) = ((x / scale) as i64 - 4, (y / scale) as i64 - 4);
        let dark = mx >= 0 && my >= 0 && (mx as u32) < n && (my as u32) < n && colors[(my as u32 * n + mx as u32) as usize] == qrcode::Color::Dark;
        image::Luma([if dark { 0 } else { 255 }])
    });
    let mut out = std::io::Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).ok()?;
    Some(out.into_inner())
}

/// El QR del código vigente, en SVG.
async fn pair_qr(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if !auth::is_local(&peer) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let (code, _) = s.auth.current();
    let link = pair_link(&s, lan_ip().as_deref(), &code);
    match qrcode::QrCode::with_error_correction_level(link.as_bytes(), qrcode::EcLevel::M) {
        Ok(qr) => {
            let svg = qr.render::<qrcode::render::svg::Color>().min_dimensions(320, 320).quiet_zone(true).build();
            ([(header::CONTENT_TYPE, "image/svg+xml"), (header::CACHE_CONTROL, "no-store")], svg).into_response()
        }
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
struct TicketRequest {
    ticket: String,
}

/// Un aparato de la misma cuenta trae un boleto del tracker: si este PC lo
/// puede canjear, recibe el token como si se hubiera conectado por USB.
async fn pair_account(State(s): State<Arc<AppState>>, axum::Json(req): axum::Json<TicketRequest>) -> Response {
    let st = s.clone();
    match tokio::task::spawn_blocking(move || st.account.redeem(&req.ticket)).await {
        Ok(Ok(v)) => {
            let who = v["device"]["name"].as_str().unwrap_or("un aparato");
            tracing::info!("{who} entró con la cuenta {}", v["user"].as_str().unwrap_or(""));
            axum::Json(json!({ "token": s.auth.token() })).into_response()
        }
        Ok(Err(e)) => (StatusCode::FORBIDDEN, axum::Json(json!({ "error": format!("{e:#}") }))).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

fn local_only(peer: &SocketAddr) -> Option<Response> {
    (!auth::is_local(peer)).then(|| StatusCode::FORBIDDEN.into_response())
}

/// Ejecuta una llamada al tracker sin frenar al servidor y la convierte en respuesta.
async fn tracker<F>(s: Arc<AppState>, f: F) -> Response
where
    F: FnOnce(&crate::account::Account) -> anyhow::Result<serde_json::Value> + Send + 'static,
{
    match tokio::task::spawn_blocking(move || f(&s.account)).await {
        Ok(Ok(v)) => axum::Json(v).into_response(),
        Ok(Err(e)) => (StatusCode::BAD_GATEWAY, axum::Json(json!({ "error": format!("{e:#}") }))).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn account_info(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    axum::Json(s.account.info()).into_response()
}

async fn setup_status(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    axum::Json(crate::tracker_setup::snapshot(&s)).into_response()
}

async fn setup_start(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    let root = s.root.clone();
    crate::tracker_setup::start(s.clone(), root);
    axum::Json(crate::tracker_setup::snapshot(&s)).into_response()
}

// ------------------------------------------------------------- compartidos
// Para la app del PC y para la tablet (con su token): el nodo de este PC.

fn shared_reply(r: anyhow::Result<serde_json::Value>) -> Response {
    match r {
        Ok(v) => axum::Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, axum::Json(json!({ "error": format!("{e:#}") }))).into_response(),
    }
}

async fn shared_list(State(s): State<Arc<AppState>>) -> Response {
    axum::Json(s.shared.list()).into_response()
}

#[derive(Deserialize)]
struct ShareRequest {
    notebook: String,
}

async fn shared_share(State(s): State<Arc<AppState>>, axum::Json(req): axum::Json<ShareRequest>) -> Response {
    let st = s.clone();
    shared_reply(tokio::task::spawn_blocking(move || st.shared.share(&st, &req.notebook)).await.unwrap_or_else(|e| Err(e.into())))
}

#[derive(Deserialize)]
struct JoinRequest {
    link: String,
}

async fn shared_join(State(s): State<Arc<AppState>>, axum::Json(req): axum::Json<JoinRequest>) -> Response {
    shared_reply(s.shared.join(&req.link))
}

async fn shared_links(State(s): State<Arc<AppState>>, Path(doc): Path<String>) -> Response {
    shared_reply(s.shared.links(&doc))
}

#[derive(Deserialize)]
struct QrQuery {
    #[serde(default)]
    role: String,
}

async fn shared_qr(State(s): State<Arc<AppState>>, Path(doc): Path<String>, axum::extract::Query(q): axum::extract::Query<QrQuery>) -> Response {
    let Ok(links) = s.shared.links(&doc) else { return StatusCode::NOT_FOUND.into_response() };
    let link = if q.role == "editor" { links["editor"].as_str() } else { links["lector"].as_str() };
    match link.and_then(|l| qr_png(l, 6)) {
        Some(png) => ([(header::CONTENT_TYPE, "image/png"), (header::CACHE_CONTROL, "no-store")], png).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[derive(Deserialize)]
struct KeepRequest {
    on: bool,
}

async fn shared_keep(State(s): State<Arc<AppState>>, Path(doc): Path<String>, axum::Json(req): axum::Json<KeepRequest>) -> Response {
    shared_reply(s.shared.set_keep(&doc, req.on))
}

async fn shared_leave(State(s): State<Arc<AppState>>, Path(doc): Path<String>) -> Response {
    shared_reply(s.shared.leave(&doc).map(|_| json!({ "ok": true })))
}

/// Aplica un cambio que llegó de otro nodo (ver `shared.rs`) y devuelve lo que
/// hay que reenviar a los aparatos de este PC.
pub(crate) fn handle_from_network(s: &AppState, msg: &Msg, raw: &str) -> anyhow::Result<Option<String>> {
    Ok(handle(s, msg, raw, "red")?.relay)
}

#[derive(Deserialize)]
struct LoginRequest {
    #[serde(default)]
    tracker: String,
    user: String,
    key: String,
    #[serde(default)]
    register: bool,
}

async fn account_login(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, axum::Json(req): axum::Json<LoginRequest>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    let port = s.port;
    tracker(s, move |a| {
        let user = a.login(&req.tracker, &req.user, &req.key, req.register)?;
        tracing::info!("este PC entró en la cuenta {user}");
        // Que los demás aparatos lo vean ya, sin esperar al próximo aviso.
        let _ = a.heartbeat(lan_ip(), port);
        Ok(a.info())
    })
    .await
}

async fn account_logout(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    tracker(s, |a| {
        a.logout();
        Ok(a.info())
    })
    .await
}

async fn account_devices(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    tracker(s, |a| a.devices()).await
}

async fn account_remove(State(s): State<Arc<AppState>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, Path(id): Path<String>) -> Response {
    if let Some(r) = local_only(&peer) {
        return r;
    }
    tracker(s, move |a| a.remove_device(&id)).await
}

/// La IP de este PC en su red local. Se pregunta por el camino hacia la puerta
/// de enlace y no hacia internet, que puede ir por una VPN a la que la tablet no
/// llega. Conectar un socket UDP no envía nada.
pub(crate) fn lan_ip() -> Option<String> {
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

/// Lo que un aparato le interesa a los demás aparatos (no a sí mismo).
fn relay_page_version(text: &str) -> Option<(String, u64)> {
    let v: serde_json::Value = serde_json::from_str(text).ok()?;
    match v["t"].as_str()? {
        "stroke" | "erase" => Some((v["page"].as_str()?.to_owned(), v["version"].as_u64()?)),
        "page" => Some((v["page"]["id"].as_str()?.to_owned(), v["page"]["version"].as_u64()?)),
        _ => None,
    }
}

fn relay_notebook(text: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(text).ok()?;
    (v["t"].as_str()? == "notebook").then(|| v["notebook"]["id"].as_str().map(str::to_owned)).flatten()
}

async fn tablet(mut socket: WebSocket, s: Arc<AppState>, peer: SocketAddr) {
    let conn = s.next_conn.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
    let mut device = String::new();
    let mut live = s.live.subscribe();
    let mut ink = s.ink.subscribe();
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
            other = ink.recv() => {
                match other {
                    // Lo de otro aparato, cuando éste ya saludó.
                    Ok((from, text)) if from != conn && !device.is_empty() => {
                        tracing::debug!("a {device}: {}", text.chars().take(80).collect::<String>());
                        {
                            let mut store = s.store.lock().unwrap();
                            if let Some((page, v)) = relay_page_version(&text) {
                                store.saw_page(&device, &page, v);
                            } else if let Some(nb) = relay_notebook(&text) {
                                store.saw_notebook(&device, &nb);
                            }
                        }
                        if socket.send(Message::Text(text.into())).await.is_err() {
                            break;
                        }
                    }
                    // Se perdió algo: que vuelva a saludar y se ponga al día.
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        let _ = socket.send(Message::Text(json!({ "t": "resync" }).to_string().into())).await;
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
        if let Msg::Hello { device: d, kind, .. } = &parsed {
            device = if d.is_empty() { "Tablet".into() } else { d.clone() };
            let label = if kind == "desktop" { format!("{device} (app del PC)") } else { device.clone() };
            s.devices.lock().unwrap().push(label.clone());
            tracing::info!("{label} conectada ({peer})");
            let _ = s.live.send(json!({ "t": "device", "name": label, "connected": true }).to_string());
        }
        let out = match handle(&s, &parsed, &text, &device) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("no se pudo guardar: {e:#}");
                Handled::default()
            }
        };
        if let Some(relay) = &out.relay {
            tracing::debug!("de {device}: reenvío {}", relay.chars().take(80).collect::<String>());
        }
        if let Some(relay) = out.relay {
            // Una página que se juntó vuelve también a quien la mandó.
            let _ = s.ink.send((if out.echo { 0 } else { conn }, relay));
        }
        for line in out.reply {
            if socket.send(Message::Text(line.into())).await.is_err() {
                break 'session;
            }
        }
    }
    if device.is_empty() {
        return;
    }
    s.store.lock().unwrap().persist_seen();
    tracing::info!("{device} desconectada");
    let mut devices = s.devices.lock().unwrap();
    if let Some(i) = devices.iter().position(|d| d.starts_with(device.as_str())) {
        devices.remove(i);
    }
    let (connected, name) = match devices.first() {
        Some(other) => (true, other.clone()),
        None => (false, device),
    };
    let _ = s.live.send(json!({ "t": "device", "name": name, "connected": connected }).to_string());
}

/// Lo que sale de un mensaje: respuestas para ese aparato y lo que va a los demás.
#[derive(Default)]
struct Handled {
    reply: Vec<String>,
    relay: Option<String>,
    /// El reenvío va también a quien lo mandó.
    echo: bool,
}

/// Aplica un mensaje de un aparato (tablet o app del PC).
fn handle(s: &AppState, msg: &Msg, raw: &str, device: &str) -> anyhow::Result<Handled> {
    let mut store = s.store.lock().unwrap();
    let mut out = Handled::default();
    let want = |ids: Vec<&str>| json!({ "t": "want", "pages": ids }).to_string();
    let applied = match msg {
        Msg::Hello { pages, kind, notebooks, .. } => {
            let plan = store.plan(device, kind != "desktop", pages, notebooks.as_deref());
            if !plan.notebooks.is_empty() || !plan.pages.is_empty() {
                tracing::info!("a {device}: {} cuadernos y {} páginas de otros aparatos", plan.notebooks.len(), plan.pages.len());
            }
            for n in &plan.notebooks {
                out.reply.push(json!({ "t": "notebook", "notebook": n }).to_string());
            }
            for p in &plan.pages {
                out.reply.push(json!({ "t": "page", "page": p }).to_string());
            }
            // Imágenes que no llegaron (una subida cortada, un PC nuevo…).
            let mut assets: Vec<String> = Vec::new();
            for id in pages.keys() {
                if let Some(p) = store.page(id) {
                    assets.extend(store.missing_assets(p));
                }
            }
            assets.sort();
            assets.dedup();
            out.reply.push(want(plan.want.iter().map(String::as_str).collect()));
            if !assets.is_empty() {
                out.reply.push(json!({ "t": "want_assets", "ids": assets }).to_string());
            }
            return Ok(out);
        }
        Msg::Notebook { notebook } => {
            let changed = store.put_notebook(notebook.clone())?;
            store.saw_notebook(device, &notebook.id);
            if !changed {
                return Ok(out);
            }
            out.relay = Some(raw.to_owned());
            Applied::Ok
        }
        Msg::Page { page } => {
            let merged = store.put_page(page.clone())?;
            let now = store.page(&page.id).cloned();
            if let Some(now) = &now {
                store.saw_page(device, &now.id, if merged { page.version } else { now.version });
                out.relay = Some(json!({ "t": "page", "page": now }).to_string());
                out.echo = merged;
            }
            let missing = store.missing_assets(page);
            let _ = s.live.send(raw.to_owned());
            if !missing.is_empty() {
                out.reply.push(json!({ "t": "want_assets", "ids": missing }).to_string());
            }
            return Ok(out);
        }
        Msg::Asset { id, data, .. } => {
            store.put_asset(id, data)?;
            // Al visor y a los demás aparatos sólo el aviso, no la imagen entera.
            let notice = json!({ "t": "asset", "id": id }).to_string();
            let _ = s.live.send(notice.clone());
            out.relay = Some(notice);
            return Ok(out);
        }
        Msg::Stroke { page, version, stroke } => {
            let r = store.add_stroke(page, *version, stroke.clone())?;
            if r == Applied::Ok {
                let v = store.page(page).map(|p| p.version).unwrap_or(*version);
                store.saw_page(device, page, v);
                out.relay = Some(json!({ "t": "stroke", "page": page, "version": v, "stroke": stroke }).to_string());
            }
            r
        }
        Msg::Erase { page, version, ids } => {
            let r = store.erase(page, *version, ids)?;
            if r == Applied::Ok {
                let v = store.page(page).map(|p| p.version).unwrap_or(*version);
                store.saw_page(device, page, v);
                out.relay = Some(json!({ "t": "erase", "page": page, "version": v, "ids": ids }).to_string());
            }
            r
        }
        Msg::Laser { .. } => {
            let _ = s.live.send(raw.to_owned());
            out.relay = Some(raw.to_owned());
            return Ok(out);
        }
        Msg::DeletePage { page } => {
            store.delete_page(page)?;
            out.relay = Some(raw.to_owned());
            Applied::Ok
        }
        Msg::DeleteNotebook { notebook } => {
            store.delete_notebook(notebook)?;
            out.relay = Some(raw.to_owned());
            Applied::Ok
        }
        Msg::File { kind, title, ext, data } => {
            let path = store.put_file(kind, title, ext, data)?;
            tracing::info!("documento de la tablet: {}", path.display());
            return Ok(out);
        }
        Msg::Clip { kind, data } => {
            use base64::Engine;
            let clip = if kind == "image" {
                crate::clipboard::Clip::Image(base64::engine::general_purpose::STANDARD.decode(data.trim()).context("imagen mal codificada")?)
            } else {
                crate::clipboard::Clip::Text(data.clone())
            };
            if let Some(c) = &s.clipboard {
                c.lock().unwrap().set(clip);
            }
            return Ok(out);
        }
    };
    match applied {
        Applied::Ok => {
            let _ = s.live.send(raw.to_owned());
        }
        Applied::Stale => {}
        Applied::Gap => {
            if let Msg::Stroke { page, .. } | Msg::Erase { page, .. } = msg {
                out.reply.push(want(vec![page.as_str()]));
            }
        }
    }
    Ok(out)
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
