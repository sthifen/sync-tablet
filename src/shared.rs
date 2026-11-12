//! Cuadernos compartidos entre personas, sin servidor de nadie (estilo Hamachi).
//!
//! Cada PC (y cada tablet, ver `Shared.java`) lleva un nodo que guarda una copia
//! completa de los cuadernos que se compartieron con él. Los nodos se conectan
//! entre sí por TCP (puerto del servidor + 2) y se pasan **operaciones firmadas**:
//! un trazo, un borrado, una página, el cuaderno, una imagen. Cada operación
//! tiene un id; al conectarse dos nodos se cuentan qué ids tienen y se mandan lo
//! que le falta al otro, y lo nuevo se reenvía por toda la red (como un torrent).
//!
//! * Quien tiene el enlace de **lector** puede entrar (conoce el secreto `s`),
//!   ver y reenviar, pero no escribir: las operaciones van firmadas con la clave
//!   de **editor** (ECDSA P-256) y todos las comprueban.
//! * **Mantener vivo**: el aparato hace de host del documento. Anuncia sus
//!   direcciones (la de la red local y, si el router deja abrir un puerto por
//!   UPnP, la pública) y los demás se conectan a él. Si se apaga, siguen con otro
//!   que también lo mantenga vivo.
//!
//! Sólo se cargan los cuadernos compartidos; los demás no salen del aparato.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use p256::ecdsa::signature::{Signer, Verifier};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

use crate::ink::{Msg, Notebook, Page};
use crate::server::AppState;

/// Marca de los cambios que llegan de la red en el canal de la tinta: así el
/// nodo no los vuelve a publicar como si fueran de este PC.
pub const NET_CONN: u64 = u64::MAX - 7;
/// Un aparato está «en línea» si dio señales hace menos de esto.
const ONLINE_MS: u64 = 75_000;
const HERE_EVERY: Duration = Duration::from_secs(30);
/// Una línea no puede pasar de esto (una imagen grande cabe de sobra).
const MAX_LINE: usize = 48 << 20;

// ------------------------------------------------------------------ datos

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Peer {
    pub node: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub addrs: Vec<String>,
    #[serde(default)]
    pub keep: bool,
    /// Última señal (ms desde 1970); no se guarda.
    #[serde(skip)]
    pub last: u64,
    #[serde(skip)]
    pub here_ts: u64,
}

/// Lo que este nodo sabe de un documento compartido (`.data/compartidos/<doc>.json`).
#[derive(Serialize, Deserialize, Clone, Debug)]
struct Meta {
    doc: String,
    notebook: String,
    title: String,
    /// admin (lo creó), editor o lector.
    role: String,
    /// Secreto para entrar (hex). Lo saben todos los que tienen un enlace.
    secret: String,
    /// Clave pública de editor (DER, base64url): con ella se comprueba cada cambio.
    public: String,
    /// Clave privada de editor (PKCS#8, base64url): sólo admin y editores.
    #[serde(default)]
    signer: Option<String>,
    #[serde(default)]
    keep: bool,
    #[serde(default)]
    peers: Vec<Peer>,
    #[serde(default)]
    dead_strokes: HashSet<String>,
    #[serde(default)]
    dead_pages: HashSet<String>,
}

/// Una operación firmada. `body` es un mensaje de la tinta tal cual (como texto,
/// para que la firma no dependa de cómo se ordena el JSON).
#[derive(Serialize, Deserialize, Clone, Debug)]
struct Op {
    id: String,
    by: String,
    body: String,
    sig: String,
}

struct DocState {
    meta: Meta,
    ops: Vec<Op>,
    ids: HashSet<String>,
    /// Trazos o borrados de una página que todavía no llegó.
    pending: Vec<Op>,
    /// Imágenes ya publicadas en este documento.
    assets: HashSet<String>,
    verify: VerifyingKey,
    sign: Option<SigningKey>,
}

struct Conn {
    doc: String,
    node: String,
    tx: mpsc::UnboundedSender<String>,
}

#[derive(Default)]
struct State {
    docs: HashMap<String, DocState>,
    /// Página → documento (para saber si un cambio local es de algo compartido).
    page_doc: HashMap<String, String>,
    conns: HashMap<u64, Conn>,
    /// Direcciones de este mismo nodo (para no conectarse a sí mismo).
    me_addrs: HashSet<String>,
    public: Option<String>,
}

pub struct Shared {
    dir: PathBuf,
    pub node: String,
    pub name: String,
    pub port: u16,
    state: Mutex<State>,
    next: AtomicU64,
}

// ------------------------------------------------------------------ utilidades

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn random_hex(n: usize) -> String {
    use std::io::Read;
    let mut b = vec![0u8; n];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        let _ = f.read_exact(&mut b);
    }
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn hmac_hex(key: &str, data: &str) -> String {
    use hmac::{Hmac, Mac};
    let mut m = <Hmac<sha2::Sha256> as Mac>::new_from_slice(key.as_bytes()).expect("hmac acepta cualquier clave");
    m.update(data.as_bytes());
    m.finalize().into_bytes().iter().map(|x| format!("{x:02x}")).collect()
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn signed_text(doc: &str, id: &str, body: &str) -> String {
    format!("{doc}\n{id}\n{body}")
}

fn enc(v: &str) -> String {
    v.bytes()
        .map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
        .collect()
}

fn dec(v: &str) -> String {
    let b = v.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 3 <= b.len() {
            if let Ok(x) = u8::from_str_radix(&v[i + 1..i + 3], 16) {
                out.push(x);
                i += 3;
                continue;
            }
        }
        out.push(if b[i] == b'+' { b' ' } else { b[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Un enlace para entrar a un documento.
#[derive(Debug, Clone, PartialEq)]
pub struct Invite {
    pub doc: String,
    pub title: String,
    pub notebook: String,
    pub secret: String,
    pub public: String,
    pub signer: Option<String>,
    pub from: String,
    pub addrs: Vec<String>,
}

impl Invite {
    pub fn link(&self) -> String {
        let mut s = format!(
            "synctablet://doc?d={}&b={}&n={}&s={}&k={}&o={}&h={}",
            enc(&self.doc),
            enc(&self.notebook),
            enc(&self.title),
            enc(&self.secret),
            enc(&self.public),
            enc(&self.from),
            enc(&self.addrs.join(","))
        );
        if let Some(e) = &self.signer {
            s.push_str(&format!("&e={}", enc(e)));
        }
        s
    }

    pub fn parse(link: &str) -> Result<Self> {
        let q = link.trim().strip_prefix("synctablet://doc?").ok_or_else(|| anyhow!("no es un enlace de Sync Tablet"))?;
        let mut m: HashMap<&str, String> = HashMap::new();
        for kv in q.split('&') {
            if let Some((k, v)) = kv.split_once('=') {
                m.insert(k, dec(v));
            }
        }
        let get = |k: &str| m.get(k).cloned().filter(|v| !v.is_empty()).ok_or_else(|| anyhow!("al enlace le falta «{k}»"));
        let inv = Invite {
            doc: get("d")?,
            notebook: get("b")?,
            title: m.get("n").cloned().unwrap_or_default(),
            secret: get("s")?,
            public: get("k")?,
            signer: m.get("e").cloned().filter(|v| !v.is_empty()),
            from: m.get("o").cloned().unwrap_or_default(),
            addrs: m.get("h").map(|h| h.split(',').filter(|a| !a.is_empty()).map(str::to_owned).collect()).unwrap_or_default(),
        };
        if !crate::store::valid_id(&inv.notebook) || !inv.doc.chars().all(|c| c.is_ascii_hexdigit()) {
            bail!("enlace dañado");
        }
        Ok(inv)
    }
}

fn keys(public: &str, signer: Option<&str>) -> Result<(VerifyingKey, Option<SigningKey>)> {
    let pk = URL_SAFE_NO_PAD.decode(public).context("clave pública")?;
    let verify = VerifyingKey::from_public_key_der(&pk).map_err(|e| anyhow!("clave pública: {e}"))?;
    let sign = match signer {
        Some(s) => {
            let der = URL_SAFE_NO_PAD.decode(s).context("clave de editor")?;
            Some(SigningKey::from_pkcs8_der(&der).map_err(|e| anyhow!("clave de editor: {e}"))?)
        }
        None => None,
    };
    Ok((verify, sign))
}

fn verify_op(doc: &str, key: &VerifyingKey, op: &Op) -> bool {
    let Ok(der) = STANDARD.decode(&op.sig) else { return false };
    let Ok(sig) = Signature::from_der(&der) else { return false };
    let sig = sig.normalize_s().unwrap_or(sig);
    key.verify(signed_text(doc, &op.id, &op.body).as_bytes(), &sig).is_ok()
}

fn sign_op(doc: &str, key: &SigningKey, by: &str, body: String) -> Op {
    let id = random_hex(12);
    let sig: Signature = key.sign(signed_text(doc, &id, &body).as_bytes());
    Op { id, by: by.to_string(), body, sig: STANDARD.encode(sig.to_der().as_bytes()) }
}

// ------------------------------------------------------------------ nodo

impl Shared {
    pub fn open(root: &Path, node: String, name: String, port: u16) -> Self {
        let dir = root.join(".data/compartidos");
        let _ = std::fs::create_dir_all(&dir);
        let me = Shared { dir, node, name, port, state: Mutex::new(State::default()), next: AtomicU64::new(1) };
        let mut st = State::default();
        if let Ok(rd) = std::fs::read_dir(&me.dir) {
            for e in rd.flatten() {
                let p = e.path();
                if p.extension().and_then(|x| x.to_str()) != Some("json") {
                    continue;
                }
                match me.load(&p) {
                    Ok(d) => {
                        st.docs.insert(d.meta.doc.clone(), d);
                    }
                    Err(e) => tracing::warn!("documento compartido {}: {e:#}", p.display()),
                }
            }
        }
        if !st.docs.is_empty() {
            tracing::info!("{} cuadernos compartidos", st.docs.len());
        }
        *me.state.lock().unwrap() = st;
        me
    }

    fn load(&self, meta_path: &Path) -> Result<DocState> {
        let meta: Meta = serde_json::from_str(&std::fs::read_to_string(meta_path)?)?;
        let (verify, sign) = keys(&meta.public, meta.signer.as_deref())?;
        let mut ops = Vec::new();
        let mut ids = HashSet::new();
        if let Ok(text) = std::fs::read_to_string(self.ops_path(&meta.doc)) {
            for line in text.lines() {
                if let Ok(op) = serde_json::from_str::<Op>(line) {
                    if ids.insert(op.id.clone()) {
                        ops.push(op);
                    }
                }
            }
        }
        Ok(DocState { meta, ops, ids, pending: Vec::new(), assets: HashSet::new(), verify, sign })
    }

    fn meta_path(&self, doc: &str) -> PathBuf {
        self.dir.join(format!("{doc}.json"))
    }

    fn ops_path(&self, doc: &str) -> PathBuf {
        self.dir.join(format!("{doc}.ops.jsonl"))
    }

    fn save_meta(&self, m: &Meta) {
        if let Ok(text) = serde_json::to_string_pretty(m) {
            let p = self.meta_path(&m.doc);
            let tmp = p.with_extension("tmp");
            if std::fs::write(&tmp, text).is_ok() {
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
                }
                let _ = std::fs::rename(&tmp, &p);
            }
        }
    }

    fn append_ops(&self, doc: &str, ops: &[Op]) {
        use std::io::Write;
        if ops.is_empty() {
            return;
        }
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(self.ops_path(doc)) {
            for op in ops {
                if let Ok(line) = serde_json::to_string(op) {
                    let _ = writeln!(f, "{line}");
                }
            }
        }
    }

    pub fn peer_port(&self) -> u16 {
        self.port.wrapping_add(2)
    }

    /// Las direcciones por las que este nodo atiende (si mantiene vivo algo).
    fn my_addrs(&self, st: &State) -> Vec<String> {
        let mut v = Vec::new();
        if let Some(ip) = crate::server::lan_ip() {
            v.push(format!("{ip}:{}", self.peer_port()));
        }
        if let Some(p) = &st.public {
            v.push(p.clone());
        }
        v
    }

    fn rebuild_page_index(&self, s: &AppState) {
        let store = s.store.lock().unwrap();
        let mut st = self.state.lock().unwrap();
        let mut idx = HashMap::new();
        for (doc, d) in &st.docs {
            if let Some(nb) = store.notebooks().iter().find(|n| n.id == d.meta.notebook) {
                for p in &nb.pages {
                    idx.insert(p.clone(), doc.clone());
                }
            }
        }
        st.page_doc = idx;
    }

    // ------------------------------------------------------------ API local

    /// Lo que enseña la app: documentos, quién está y quién hace de host.
    pub fn list(&self) -> Value {
        let st = self.state.lock().unwrap();
        let now = now_ms();
        let mut docs: Vec<Value> = Vec::new();
        for d in st.docs.values() {
            let connected: HashSet<&str> = st.conns.values().filter(|c| c.doc == d.meta.doc).map(|c| c.node.as_str()).collect();
            let mut peers: Vec<Value> = d
                .meta
                .peers
                .iter()
                .filter(|p| p.node != self.node)
                .map(|p| {
                    let online = connected.contains(p.node.as_str()) || now.saturating_sub(p.last) < ONLINE_MS;
                    json!({ "node": p.node, "name": if p.name.is_empty() { &p.node } else { &p.name }, "keep": p.keep, "online": online })
                })
                .collect();
            peers.sort_by_key(|p| (!p["online"].as_bool().unwrap_or(false), p["name"].as_str().unwrap_or("").to_lowercase()));
            let host = if d.meta.keep {
                Some(self.name.clone())
            } else {
                peers.iter().find(|p| p["keep"] == true && p["online"] == true).and_then(|p| p["name"].as_str().map(str::to_owned))
            };
            docs.push(json!({
                "doc": d.meta.doc,
                "notebook": d.meta.notebook,
                "title": d.meta.title,
                "role": d.meta.role,
                "keep": d.meta.keep,
                "host": host,
                "connected": connected.len(),
                "peers": peers,
                "ops": d.ops.len(),
            }));
        }
        docs.sort_by_key(|d| d["title"].as_str().unwrap_or("").to_lowercase());
        json!({ "node": self.node, "name": self.name, "public": st.public, "docs": docs })
    }

    fn invite(&self, st: &State, d: &DocState, editor: bool) -> Invite {
        let mut addrs = if d.meta.keep { self.my_addrs(st) } else { Vec::new() };
        for p in &d.meta.peers {
            if p.keep && p.node != self.node {
                addrs.extend(p.addrs.iter().cloned());
            }
        }
        addrs.dedup();
        addrs.truncate(8);
        Invite {
            doc: d.meta.doc.clone(),
            title: d.meta.title.clone(),
            notebook: d.meta.notebook.clone(),
            secret: d.meta.secret.clone(),
            public: d.meta.public.clone(),
            signer: if editor { d.meta.signer.clone() } else { None },
            from: self.node.clone(),
            addrs,
        }
    }

    /// Enlaces para invitar (el de editor sólo si este nodo puede escribir).
    pub fn links(&self, doc: &str) -> Result<Value> {
        let st = self.state.lock().unwrap();
        let d = st.docs.get(doc).ok_or_else(|| anyhow!("ese documento no está compartido aquí"))?;
        let editor = d.meta.signer.is_some().then(|| self.invite(&st, d, true).link());
        Ok(json!({ "doc": doc, "editor": editor, "lector": self.invite(&st, d, false).link(), "keep": d.meta.keep }))
    }

    /// Empieza a compartir un cuaderno de este PC (quien lo comparte es admin y host).
    pub fn share(&self, s: &AppState, notebook: &str) -> Result<Value> {
        // Ya compartido: sus enlaces. (El candado se suelta antes de pedirlos.)
        let existing = self.state.lock().unwrap().docs.values().find(|d| d.meta.notebook == notebook).map(|d| d.meta.doc.clone());
        if let Some(doc) = existing {
            return self.links(&doc);
        }
        let (nb, pages, assets) = {
            let store = s.store.lock().unwrap();
            let nb = store.notebooks().iter().find(|n| n.id == notebook).cloned().ok_or_else(|| anyhow!("no existe ese cuaderno"))?;
            let pages: Vec<Page> = nb.pages.iter().filter_map(|p| store.page(p).cloned()).collect();
            let mut assets: Vec<(String, String)> = Vec::new();
            for p in &pages {
                for a in page_assets(p) {
                    if let Some(path) = store.asset_path(&a) {
                        if let Ok(bytes) = std::fs::read(&path) {
                            assets.push((a, STANDARD.encode(bytes)));
                        }
                    }
                }
            }
            (nb, pages, assets)
        };
        let sk = SigningKey::random(&mut rand_core::OsRng);
        let public = URL_SAFE_NO_PAD.encode(VerifyingKey::from(&sk).to_public_key_der().map_err(|e| anyhow!("{e}"))?.as_bytes());
        let signer = URL_SAFE_NO_PAD.encode(sk.to_pkcs8_der().map_err(|e| anyhow!("{e}"))?.as_bytes());
        let doc = random_hex(16);
        let meta = Meta {
            doc: doc.clone(),
            notebook: nb.id.clone(),
            title: nb.title.clone(),
            role: "admin".into(),
            secret: random_hex(16),
            public,
            signer: Some(signer),
            keep: true,
            peers: Vec::new(),
            dead_strokes: HashSet::new(),
            dead_pages: HashSet::new(),
        };
        // Todo lo que ya tiene el cuaderno, como operaciones: imágenes, cuaderno y páginas.
        let mut ops = Vec::new();
        let mut published = HashSet::new();
        for (id, data) in assets {
            if published.insert(id.clone()) {
                ops.push(sign_op(&doc, &sk, &self.node, json!({ "t": "asset", "id": id, "mime": "image/jpeg", "data": data }).to_string()));
            }
        }
        ops.push(sign_op(&doc, &sk, &self.node, json!({ "t": "notebook", "notebook": nb }).to_string()));
        for p in &pages {
            ops.push(sign_op(&doc, &sk, &self.node, json!({ "t": "page", "page": p }).to_string()));
        }
        self.save_meta(&meta);
        self.append_ops(&doc, &ops);
        let verify = VerifyingKey::from(&sk);
        {
            let mut st = self.state.lock().unwrap();
            for p in &nb.pages {
                st.page_doc.insert(p.clone(), doc.clone());
            }
            let ids = ops.iter().map(|o| o.id.clone()).collect();
            st.docs.insert(doc.clone(), DocState { meta, ops, ids, pending: Vec::new(), assets: published, verify, sign: Some(sk) });
        }
        tracing::info!("«{}» compartido (documento {doc})", nb.title);
        self.links(&doc)
    }

    /// Entra a un documento con un enlace. El cuaderno llega de los demás nodos.
    pub fn join(&self, link: &str) -> Result<Value> {
        let inv = Invite::parse(link)?;
        let (verify, sign) = keys(&inv.public, inv.signer.as_deref())?;
        let mut st = self.state.lock().unwrap();
        let host = Peer { node: inv.from.clone(), name: String::new(), addrs: inv.addrs.clone(), keep: true, last: 0, here_ts: 0 };
        if let Some(d) = st.docs.get_mut(&inv.doc) {
            // Ya estaba: puede que ahora con permiso de escribir, o con otras direcciones.
            if d.sign.is_none() && sign.is_some() {
                d.sign = sign;
                d.meta.signer = inv.signer.clone();
                d.meta.role = "editor".into();
            }
            merge_peer(&mut d.meta.peers, host);
            let m = d.meta.clone();
            drop(st);
            self.save_meta(&m);
            return Ok(json!({ "doc": inv.doc, "title": inv.title }));
        }
        let meta = Meta {
            doc: inv.doc.clone(),
            notebook: inv.notebook.clone(),
            title: inv.title.clone(),
            role: if sign.is_some() { "editor".into() } else { "lector".into() },
            secret: inv.secret.clone(),
            public: inv.public.clone(),
            signer: inv.signer.clone(),
            keep: false,
            peers: vec![host],
            dead_strokes: HashSet::new(),
            dead_pages: HashSet::new(),
        };
        self.save_meta(&meta);
        st.docs.insert(inv.doc.clone(), DocState { meta, ops: Vec::new(), ids: HashSet::new(), pending: Vec::new(), assets: HashSet::new(), verify, sign });
        tracing::info!("entré al documento «{}»", inv.title);
        Ok(json!({ "doc": inv.doc, "title": inv.title }))
    }

    pub fn set_keep(&self, doc: &str, keep: bool) -> Result<Value> {
        let m = {
            let mut st = self.state.lock().unwrap();
            let d = st.docs.get_mut(doc).ok_or_else(|| anyhow!("no está compartido aquí"))?;
            d.meta.keep = keep;
            d.meta.clone()
        };
        self.save_meta(&m);
        self.announce(doc);
        self.links(doc)
    }

    /// Deja el documento (el cuaderno se queda en este aparato como uno normal).
    pub fn leave(&self, doc: &str) -> Result<()> {
        let mut st = self.state.lock().unwrap();
        st.docs.remove(doc).ok_or_else(|| anyhow!("no está compartido aquí"))?;
        st.conns.retain(|_, c| c.doc != doc);
        st.page_doc.retain(|_, d| d != doc);
        let _ = std::fs::remove_file(self.meta_path(doc));
        let _ = std::fs::remove_file(self.ops_path(doc));
        Ok(())
    }

    pub fn wants_upnp(&self) -> bool {
        self.state.lock().unwrap().docs.values().any(|d| d.meta.keep)
    }

    // ------------------------------------------------------------ operaciones

    /// Un cambio de un aparato de este PC: si es de un cuaderno compartido y se
    /// puede escribir, se firma y se manda a la red.
    fn publish_local(&self, s: &AppState, text: &str) {
        let Ok(v) = serde_json::from_str::<Value>(text) else { return };
        let t = v["t"].as_str().unwrap_or("");
        let doc = {
            let st = self.state.lock().unwrap();
            if st.docs.is_empty() {
                return;
            }
            let find_page = |p: &str| st.page_doc.get(p).cloned();
            match t {
                "stroke" | "erase" | "delete_page" => v["page"].as_str().and_then(find_page),
                "page" => v["page"]["notebook"]
                    .as_str()
                    .and_then(|nb| st.docs.values().find(|d| d.meta.notebook == nb).map(|d| d.meta.doc.clone())),
                "notebook" => v["notebook"]["id"]
                    .as_str()
                    .and_then(|nb| st.docs.values().find(|d| d.meta.notebook == nb).map(|d| d.meta.doc.clone())),
                _ => None,
            }
        };
        let Some(doc) = doc else { return };
        // Imágenes que la página usa y la red aún no tiene.
        let mut bodies: Vec<String> = Vec::new();
        if t == "page" {
            if let Ok(p) = serde_json::from_value::<Page>(v["page"].clone()) {
                let need: Vec<String> = {
                    let st = self.state.lock().unwrap();
                    let d = &st.docs[&doc];
                    page_assets(&p).into_iter().filter(|a| !d.assets.contains(a)).collect()
                };
                let store = s.store.lock().unwrap();
                for a in need {
                    if let Some(bytes) = store.asset_path(&a).and_then(|path| std::fs::read(path).ok()) {
                        bodies.push(json!({ "t": "asset", "id": a, "mime": "image/jpeg", "data": STANDARD.encode(bytes) }).to_string());
                    }
                }
            }
        }
        bodies.push(text.to_string());
        let mut st = self.state.lock().unwrap();
        if t == "page" || t == "notebook" {
            // Páginas nuevas del cuaderno.
            if let Some(nb) = v["notebook"]["pages"].as_array() {
                for p in nb.iter().filter_map(Value::as_str) {
                    st.page_doc.insert(p.to_owned(), doc.clone());
                }
            }
            if let Some(p) = v["page"]["id"].as_str() {
                st.page_doc.insert(p.to_owned(), doc.clone());
            }
        }
        let Some(d) = st.docs.get_mut(&doc) else { return };
        let Some(key) = d.sign.clone() else {
            tracing::debug!("«{}»: sólo lectura, el cambio se queda en este PC", d.meta.title);
            return;
        };
        if t == "erase" {
            for id in v["ids"].as_array().into_iter().flatten().filter_map(Value::as_str) {
                d.meta.dead_strokes.insert(id.to_owned());
            }
        }
        if t == "delete_page" {
            if let Some(p) = v["page"].as_str() {
                d.meta.dead_pages.insert(p.to_owned());
            }
        }
        let mut new_ops = Vec::new();
        for body in bodies {
            if let Ok(b) = serde_json::from_str::<Value>(&body) {
                if b["t"] == "asset" {
                    if let Some(id) = b["id"].as_str() {
                        d.assets.insert(id.to_owned());
                    }
                }
            }
            let op = sign_op(&doc, &key, &self.node, body);
            d.ids.insert(op.id.clone());
            d.ops.push(op.clone());
            new_ops.push(op);
        }
        let meta = (t == "erase" || t == "delete_page").then(|| d.meta.clone());
        let line = json!({ "t": "ops", "ops": new_ops }).to_string();
        for c in st.conns.values().filter(|c| c.doc == doc) {
            let _ = c.tx.send(line.clone());
        }
        drop(st);
        self.append_ops(&doc, &new_ops);
        if let Some(m) = meta {
            self.save_meta(&m);
        }
    }

    /// Operaciones que llegan de otro nodo: se comprueban, se guardan, se aplican
    /// aquí y se reenvían a los demás (menos a quien las mandó).
    fn receive(&self, s: &AppState, doc: &str, from_conn: u64, ops: Vec<Op>) {
        let fresh: Vec<Op> = {
            let mut st = self.state.lock().unwrap();
            let Some(d) = st.docs.get_mut(doc) else { return };
            let mut fresh = Vec::new();
            for op in ops {
                if d.ids.contains(&op.id) {
                    continue;
                }
                if !verify_op(doc, &d.verify, &op) {
                    tracing::warn!("«{}»: cambio con firma inválida de {}, descartado", d.meta.title, op.by);
                    continue;
                }
                d.ids.insert(op.id.clone());
                d.ops.push(op.clone());
                fresh.push(op);
            }
            if fresh.is_empty() {
                return;
            }
            let line = json!({ "t": "ops", "ops": fresh }).to_string();
            for (id, c) in &st.conns {
                if *id != from_conn && c.doc == doc {
                    let _ = c.tx.send(line.clone());
                }
            }
            fresh
        };
        self.append_ops(doc, &fresh);
        for op in fresh {
            self.apply(s, doc, op);
        }
    }

    /// Aplica una operación al cuaderno de este PC y avisa a sus aparatos.
    fn apply(&self, s: &AppState, doc: &str, op: Op) {
        let Ok(v) = serde_json::from_str::<Value>(&op.body) else { return };
        let (dead_strokes, dead_pages, notebook) = {
            let st = self.state.lock().unwrap();
            let Some(d) = st.docs.get(doc) else { return };
            (d.meta.dead_strokes.clone(), d.meta.dead_pages.clone(), d.meta.notebook.clone())
        };
        let mut relays: Vec<String> = Vec::new();
        let mut retry = false;
        let r: Result<()> = (|| {
            match v["t"].as_str().unwrap_or("") {
                "asset" => {
                    let (Some(id), Some(data)) = (v["id"].as_str(), v["data"].as_str()) else { return Ok(()) };
                    let mut store = s.store.lock().unwrap();
                    if !store.has_asset(id) {
                        store.put_asset(id, data)?;
                    }
                    relays.push(json!({ "t": "asset", "id": id }).to_string());
                    if let Some(d) = self.state.lock().unwrap().docs.get_mut(doc) {
                        d.assets.insert(id.to_owned());
                    }
                }
                "notebook" => {
                    let mut nb: Notebook = serde_json::from_value(v["notebook"].clone())?;
                    if nb.id != notebook {
                        bail!("cuaderno que no es de este documento");
                    }
                    let mut store = s.store.lock().unwrap();
                    if let Some(cur) = store.notebooks().iter().find(|n| n.id == nb.id) {
                        // Lo de cada uno se queda: carpeta, favorito y color.
                        nb.folder = cur.folder.clone();
                        nb.favorite = cur.favorite;
                        for p in &cur.pages {
                            if !nb.pages.contains(p) {
                                nb.pages.push(p.clone());
                            }
                        }
                    } else {
                        nb.folder = "Compartidos".into();
                        nb.favorite = false;
                    }
                    nb.pages.retain(|p| !dead_pages.contains(p));
                    let mut st = self.state.lock().unwrap();
                    for p in &nb.pages {
                        st.page_doc.insert(p.clone(), doc.to_owned());
                    }
                    if let Some(d) = st.docs.get_mut(doc) {
                        d.meta.title = nb.title.clone();
                    }
                    drop(st);
                    if store.put_notebook(nb.clone())? {
                        relays.push(json!({ "t": "notebook", "notebook": nb }).to_string());
                    }
                    retry = true;
                }
                "page" => {
                    let mut p: Page = serde_json::from_value(v["page"].clone())?;
                    if p.notebook != notebook || dead_pages.contains(&p.id) {
                        return Ok(());
                    }
                    let mut store = s.store.lock().unwrap();
                    if let Some(cur) = store.page(&p.id).cloned() {
                        // Se juntan: lo de los dos, menos lo borrado.
                        let mut strokes = cur.strokes.clone();
                        for st in p.strokes.drain(..) {
                            if !strokes.iter().any(|x| x.id == st.id) {
                                strokes.push(st);
                            }
                        }
                        p.strokes = strokes;
                        let mut items = p.items.clone();
                        for it in &cur.items {
                            if !items.iter().any(|x| item_id(x) == item_id(it)) {
                                items.push(it.clone());
                            }
                        }
                        p.items = items;
                        p.version = cur.version + 1;
                    }
                    p.strokes.retain(|x| !dead_strokes.contains(&x.id));
                    store.put_page(p.clone())?;
                    if !store.notebooks().iter().any(|n| n.id == notebook && n.pages.contains(&p.id)) {
                        if let Some(mut nb) = store.notebooks().iter().find(|n| n.id == notebook).cloned() {
                            nb.pages.push(p.id.clone());
                            if store.put_notebook(nb.clone())? {
                                relays.push(json!({ "t": "notebook", "notebook": nb }).to_string());
                            }
                        }
                    }
                    let now = store.page(&p.id).cloned();
                    drop(store);
                    self.state.lock().unwrap().page_doc.insert(p.id.clone(), doc.to_owned());
                    if let Some(now) = now {
                        relays.push(json!({ "t": "page", "page": now }).to_string());
                    }
                    retry = true;
                }
                "stroke" => {
                    let page = v["page"].as_str().unwrap_or("");
                    let stroke: crate::ink::Stroke = serde_json::from_value(v["stroke"].clone())?;
                    if dead_strokes.contains(&stroke.id) {
                        return Ok(());
                    }
                    let version = match s.store.lock().unwrap().page(page) {
                        Some(p) => p.version + 1,
                        None => {
                            self.park(doc, op.clone());
                            return Ok(());
                        }
                    };
                    let msg = Msg::Stroke { page: page.to_owned(), version, stroke };
                    relays.extend(apply_msg(s, &msg)?);
                }
                "erase" => {
                    let page = v["page"].as_str().unwrap_or("");
                    let ids: Vec<String> = serde_json::from_value(v["ids"].clone())?;
                    {
                        let mut st = self.state.lock().unwrap();
                        if let Some(d) = st.docs.get_mut(doc) {
                            d.meta.dead_strokes.extend(ids.iter().cloned());
                        }
                    }
                    let version = match s.store.lock().unwrap().page(page) {
                        Some(p) => p.version + 1,
                        None => return Ok(()),
                    };
                    relays.extend(apply_msg(s, &Msg::Erase { page: page.to_owned(), version, ids })?);
                }
                "delete_page" => {
                    let page = v["page"].as_str().unwrap_or("").to_owned();
                    {
                        let mut st = self.state.lock().unwrap();
                        if let Some(d) = st.docs.get_mut(doc) {
                            d.meta.dead_pages.insert(page.clone());
                        }
                    }
                    relays.extend(apply_msg(s, &Msg::DeletePage { page })?);
                }
                _ => {}
            }
            Ok(())
        })();
        if let Err(e) = r {
            tracing::debug!("cambio de la red no aplicado: {e:#}");
        }
        for r in relays {
            let _ = s.ink.send((NET_CONN, r));
        }
        if retry {
            let parked: Vec<Op> = {
                let mut st = self.state.lock().unwrap();
                st.docs.get_mut(doc).map(|d| std::mem::take(&mut d.pending)).unwrap_or_default()
            };
            for op in parked {
                self.apply(s, doc, op);
            }
        }
    }

    fn park(&self, doc: &str, op: Op) {
        if let Some(d) = self.state.lock().unwrap().docs.get_mut(doc) {
            if d.pending.len() < 10_000 {
                d.pending.push(op);
            }
        }
    }

    // ------------------------------------------------------------ presencia

    /// Cuenta a la red que este nodo está (y si mantiene vivo el documento).
    fn announce(&self, doc: &str) {
        let st = self.state.lock().unwrap();
        let Some(d) = st.docs.get(doc) else { return };
        let addrs = if d.meta.keep { self.my_addrs(&st) } else { Vec::new() };
        let line = json!({ "t": "here", "node": self.node, "name": self.name, "keep": d.meta.keep, "addrs": addrs, "ts": now_ms() }).to_string();
        for c in st.conns.values().filter(|c| c.doc == doc) {
            let _ = c.tx.send(line.clone());
        }
    }

    fn heard(&self, doc: &str, from_conn: u64, v: &Value) {
        let node = v["node"].as_str().unwrap_or("").to_owned();
        let ts = v["ts"].as_u64().unwrap_or(0);
        if node.is_empty() || node == self.node {
            return;
        }
        let (meta, forward) = {
            let mut st = self.state.lock().unwrap();
            let Some(d) = st.docs.get_mut(doc) else { return };
            let p = match d.meta.peers.iter_mut().find(|p| p.node == node) {
                Some(p) => p,
                None => {
                    d.meta.peers.push(Peer { node: node.clone(), ..Default::default() });
                    d.meta.peers.last_mut().unwrap()
                }
            };
            if ts <= p.here_ts {
                return;
            }
            p.here_ts = ts;
            p.last = now_ms();
            p.name = v["name"].as_str().unwrap_or("").to_owned();
            p.keep = v["keep"].as_bool().unwrap_or(false);
            let addrs: Vec<String> = v["addrs"].as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_owned).collect();
            if !addrs.is_empty() || !p.keep {
                p.addrs = addrs;
            }
            let line = v.to_string();
            let forward: Vec<mpsc::UnboundedSender<String>> =
                st.conns.iter().filter(|(id, c)| **id != from_conn && c.doc == doc).map(|(_, c)| c.tx.clone()).collect();
            let meta = st.docs[doc].meta.clone();
            for tx in &forward {
                let _ = tx.send(line.clone());
            }
            (meta, forward.len())
        };
        let _ = forward;
        self.save_meta(&meta);
    }

    // ------------------------------------------------------------ conexiones

    fn register(&self, doc: &str, node: &str, tx: mpsc::UnboundedSender<String>) -> u64 {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let mut st = self.state.lock().unwrap();
        st.conns.insert(id, Conn { doc: doc.to_owned(), node: node.to_owned(), tx: tx.clone() });
        if let Some(d) = st.docs.get_mut(doc) {
            if let Some(p) = d.meta.peers.iter_mut().find(|p| p.node == node) {
                p.last = now_ms();
            }
            let ids: Vec<&String> = d.ops.iter().map(|o| &o.id).collect();
            let _ = tx.send(json!({ "t": "inv", "ids": ids }).to_string());
        }
        id
    }

    fn unregister(&self, id: u64) {
        self.state.lock().unwrap().conns.remove(&id);
    }

    /// Le mandan la lista de lo que tienen: se les manda lo que les falta.
    fn inventory(&self, id: u64, doc: &str, theirs: HashSet<String>) {
        let st = self.state.lock().unwrap();
        let (Some(d), Some(c)) = (st.docs.get(doc), st.conns.get(&id)) else { return };
        let missing: Vec<&Op> = d.ops.iter().filter(|o| !theirs.contains(&o.id)).collect();
        // En tandas, para no mandar una línea enorme.
        let mut batch: Vec<&Op> = Vec::new();
        let mut size = 0;
        for op in missing {
            size += op.body.len();
            batch.push(op);
            if size > 4 << 20 || batch.len() >= 500 {
                let _ = c.tx.send(json!({ "t": "ops", "ops": batch }).to_string());
                batch.clear();
                size = 0;
            }
        }
        if !batch.is_empty() {
            let _ = c.tx.send(json!({ "t": "ops", "ops": batch }).to_string());
        }
    }

    fn connected_to(&self, doc: &str, node: &str) -> bool {
        self.state.lock().unwrap().conns.values().any(|c| c.doc == doc && c.node == node)
    }

    fn secret(&self, doc: &str) -> Option<String> {
        self.state.lock().unwrap().docs.get(doc).map(|d| d.meta.secret.clone())
    }

    fn hello_fields(&self, doc: &str) -> Value {
        let st = self.state.lock().unwrap();
        let keep = st.docs.get(doc).map(|d| d.meta.keep).unwrap_or(false);
        json!({ "node": self.node, "name": self.name, "keep": keep, "addrs": if keep { self.my_addrs(&st) } else { Vec::new() } })
    }
}

fn merge_peer(peers: &mut Vec<Peer>, p: Peer) {
    match peers.iter_mut().find(|x| x.node == p.node && !p.node.is_empty()) {
        Some(x) => {
            for a in p.addrs {
                if !x.addrs.contains(&a) {
                    x.addrs.push(a);
                }
            }
            x.keep |= p.keep;
        }
        None => peers.push(p),
    }
}

fn item_id(i: &crate::ink::Item) -> &str {
    match i {
        crate::ink::Item::Image { id, .. } | crate::ink::Item::Text { id, .. } | crate::ink::Item::Table { id, .. } => id,
    }
}

fn page_assets(p: &Page) -> Vec<String> {
    let mut v: Vec<String> = p.bg_asset.iter().cloned().collect();
    for i in &p.items {
        if let crate::ink::Item::Image { asset, .. } = i {
            v.push(asset.clone());
        }
    }
    v
}

/// Aplica un mensaje como si viniera de un aparato más («red») y devuelve lo que
/// hay que reenviar a los aparatos de este PC.
fn apply_msg(s: &AppState, msg: &Msg) -> Result<Vec<String>> {
    let raw = serde_json::to_string(msg)?;
    let out = crate::server::handle_from_network(s, msg, &raw)?;
    Ok(out.into_iter().collect())
}

// ------------------------------------------------------------------ red

async fn read_line(r: &mut BufReader<tokio::net::tcp::OwnedReadHalf>, buf: &mut String) -> Result<bool> {
    buf.clear();
    let n = tokio::time::timeout(Duration::from_secs(90), r.read_line(buf)).await.map_err(|_| anyhow!("sin señales"))??;
    if buf.len() > MAX_LINE {
        bail!("mensaje demasiado grande");
    }
    Ok(n > 0)
}

/// Una conexión con otro nodo, ya sea que llegó o que la abrimos.
async fn session(s: Arc<AppState>, stream: TcpStream, outgoing: Option<String>) -> Result<()> {
    let sh = s.shared.clone();
    let _ = stream.set_nodelay(true);
    let (rd, mut wr) = stream.into_split();
    let mut rd = BufReader::new(rd);
    let mut line = String::new();
    // Saludo: cada lado demuestra que conoce el secreto del documento sin decirlo.
    let (doc, peer) = match outgoing {
        None => {
            let nonce = random_hex(16);
            wr.write_all(format!("{}\n", json!({ "t": "hi", "v": 1, "node": sh.node, "nonce": nonce })).as_bytes()).await?;
            if !read_line(&mut rd, &mut line).await? {
                return Ok(());
            }
            let j: Value = serde_json::from_str(&line)?;
            let doc = j["doc"].as_str().unwrap_or("").to_owned();
            let secret = sh.secret(&doc).ok_or_else(|| anyhow!("documento desconocido"))?;
            if !same(j["proof"].as_str().unwrap_or(""), &hmac_hex(&secret, &format!("join\n{nonce}"))) {
                bail!("no conoce el secreto del documento");
            }
            let mut ok = sh.hello_fields(&doc);
            ok["t"] = json!("ok");
            ok["proof"] = json!(hmac_hex(&secret, &format!("ok\n{}", j["nonce"].as_str().unwrap_or(""))));
            wr.write_all(format!("{ok}\n").as_bytes()).await?;
            (doc, j)
        }
        Some(doc) => {
            let secret = sh.secret(&doc).ok_or_else(|| anyhow!("documento desconocido"))?;
            if !read_line(&mut rd, &mut line).await? {
                return Ok(());
            }
            let hi: Value = serde_json::from_str(&line)?;
            if hi["node"].as_str() == Some(sh.node.as_str()) {
                bail!("es este mismo nodo");
            }
            let nonce = random_hex(16);
            let mut join = sh.hello_fields(&doc);
            join["t"] = json!("join");
            join["doc"] = json!(doc);
            join["nonce"] = json!(nonce);
            join["proof"] = json!(hmac_hex(&secret, &format!("join\n{}", hi["nonce"].as_str().unwrap_or(""))));
            wr.write_all(format!("{join}\n").as_bytes()).await?;
            if !read_line(&mut rd, &mut line).await? {
                return Ok(());
            }
            let ok: Value = serde_json::from_str(&line)?;
            if !same(ok["proof"].as_str().unwrap_or(""), &hmac_hex(&secret, &format!("ok\n{nonce}"))) {
                bail!("el otro nodo no conoce el secreto");
            }
            (doc, ok)
        }
    };
    let node = peer["node"].as_str().unwrap_or("").to_owned();
    if node.is_empty() || node == sh.node {
        return Ok(());
    }
    let mut here = peer.clone();
    here["ts"] = json!(now_ms());
    sh.heard(&doc, 0, &here);
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    let id = sh.register(&doc, &node, tx.clone());
    tracing::info!("compartido: conectado con {} ({})", peer["name"].as_str().unwrap_or(&node), if outgoing_kind(&peer) { "host" } else { "invitado" });
    sh.announce(&doc);
    let writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(Duration::from_secs(30));
        loop {
            tokio::select! {
                m = rx.recv() => match m {
                    Some(m) => if wr.write_all(m.as_bytes()).await.is_err() || wr.write_all(b"\n").await.is_err() { break },
                    None => break,
                },
                _ = ping.tick() => if wr.write_all(b"{\"t\":\"ping\"}\n").await.is_err() { break },
            }
        }
    });
    let result: Result<()> = async {
        while read_line(&mut rd, &mut line).await? {
            let j: Value = match serde_json::from_str(&line) {
                Ok(j) => j,
                Err(_) => continue,
            };
            match j["t"].as_str().unwrap_or("") {
                "inv" => {
                    let theirs = j["ids"].as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_owned).collect();
                    sh.inventory(id, &doc, theirs);
                }
                "ops" => {
                    let ops: Vec<Op> = serde_json::from_value(j["ops"].clone()).unwrap_or_default();
                    let (s2, doc2) = (s.clone(), doc.clone());
                    // Aplicar toca el disco: fuera del hilo de la red.
                    tokio::task::spawn_blocking(move || s2.shared.receive(&s2, &doc2, id, ops)).await.ok();
                }
                "here" => sh.heard(&doc, id, &j),
                _ => {}
            }
        }
        Ok(())
    }
    .await;
    sh.unregister(id);
    writer.abort();
    tracing::info!("compartido: se fue {}", peer["name"].as_str().unwrap_or(&node));
    result
}

fn outgoing_kind(peer: &Value) -> bool {
    peer["keep"].as_bool().unwrap_or(false)
}

/// Atiende a los nodos que se conectan (siempre: el PC es un worker).
pub async fn listen(s: Arc<AppState>) {
    let port = s.shared.peer_port();
    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => {
            tracing::warn!("cuadernos compartidos: no se pudo escuchar en el puerto {port}: {e}");
            return;
        }
    };
    tracing::info!("cuadernos compartidos: nodo {} en el puerto {port}", s.shared.node);
    loop {
        let Ok((stream, addr)) = listener.accept().await else { continue };
        let s = s.clone();
        tokio::spawn(async move {
            if let Err(e) = session(s, stream, None).await {
                tracing::debug!("compartido: {addr}: {e:#}");
            }
        });
    }
}

/// Mantiene las conexiones: con los hosts conocidos de cada documento, cambios
/// locales hacia la red, y avisos de presencia.
pub async fn run(s: Arc<AppState>) {
    s.shared.rebuild_page_index(&s);
    // Cambios de los aparatos de este PC → red.
    {
        let s = s.clone();
        let mut rx = s.ink.subscribe();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok((from, text)) if from != NET_CONN => {
                        let s2 = s.clone();
                        let _ = tokio::task::spawn_blocking(move || s2.shared.publish_local(&s2, &text)).await;
                    }
                    Ok(_) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => tracing::warn!("compartido: se perdieron {n} cambios locales"),
                    Err(_) => return,
                }
            }
        });
    }
    // Abrir el puerto en el router (UPnP) cuando algo se mantiene vivo.
    tokio::spawn(upnp(s.clone()));
    let mut tick = tokio::time::interval(Duration::from_secs(10));
    let mut last_here = std::time::Instant::now() - HERE_EVERY;
    loop {
        tick.tick().await;
        let sh = &s.shared;
        let targets: Vec<(String, String, Vec<String>)> = {
            let st = sh.state.lock().unwrap();
            st.docs
                .values()
                .flat_map(|d| {
                    d.meta
                        .peers
                        .iter()
                        .filter(|p| p.keep && p.node != sh.node && !p.addrs.is_empty())
                        .map(|p| (d.meta.doc.clone(), p.node.clone(), p.addrs.clone()))
                        .collect::<Vec<_>>()
                })
                .collect()
        };
        for (doc, node, addrs) in targets {
            if !node.is_empty() && sh.connected_to(&doc, &node) {
                continue;
            }
            let s = s.clone();
            tokio::spawn(async move {
                for a in addrs {
                    if s.shared.state.lock().unwrap().me_addrs.contains(&a) {
                        continue;
                    }
                    let Ok(Ok(stream)) = tokio::time::timeout(Duration::from_secs(4), TcpStream::connect(&a)).await else { continue };
                    match session(s.clone(), stream, Some(doc.clone())).await {
                        Err(e) if e.to_string().contains("mismo nodo") => {
                            s.shared.state.lock().unwrap().me_addrs.insert(a.clone());
                        }
                        Err(e) => tracing::debug!("compartido: {a}: {e:#}"),
                        Ok(()) => {}
                    }
                    break;
                }
            });
        }
        if last_here.elapsed() >= HERE_EVERY {
            last_here = std::time::Instant::now();
            let docs: Vec<String> = sh.state.lock().unwrap().docs.keys().cloned().collect();
            for d in docs {
                sh.announce(&d);
            }
        }
    }
}

async fn upnp(s: Arc<AppState>) {
    use igd_next::aio::tokio::search_gateway;
    let port = s.shared.peer_port();
    let mut warned = false;
    loop {
        if s.shared.wants_upnp() {
            let r: Result<String> = async {
                let gw = search_gateway(igd_next::SearchOptions { timeout: Some(Duration::from_secs(4)), ..Default::default() })
                    .await
                    .map_err(|e| anyhow!("{e}"))?;
                let ip: std::net::IpAddr = crate::server::lan_ip().ok_or_else(|| anyhow!("sin red"))?.parse()?;
                gw.add_port(igd_next::PortMappingProtocol::TCP, port, std::net::SocketAddr::new(ip, port), 3600, "Sync Tablet")
                    .await
                    .map_err(|e| anyhow!("{e}"))?;
                let ext = gw.get_external_ip().await.map_err(|e| anyhow!("{e}"))?;
                Ok(format!("{ext}:{port}"))
            }
            .await;
            match r {
                Ok(addr) => {
                    let mut st = s.shared.state.lock().unwrap();
                    if st.public.as_deref() != Some(&addr) {
                        tracing::info!("compartido: el router abrió el puerto; desde internet: {addr}");
                    }
                    st.me_addrs.insert(addr.clone());
                    st.public = Some(addr);
                    warned = false;
                }
                Err(e) => {
                    if !warned {
                        tracing::info!("compartido: el router no abrió el puerto (UPnP: {e:#}); sólo se podrá entrar desde esta red");
                        warned = true;
                    }
                    s.shared.state.lock().unwrap().public = None;
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(30 * 60)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn el_enlace_va_y_vuelve() {
        let inv = Invite {
            doc: "ab12".into(),
            title: "Cálculo & física".into(),
            notebook: "nb-1".into(),
            secret: "s3cr3t".into(),
            public: "PUB_key-x".into(),
            signer: Some("PRIV".into()),
            from: "pc-1".into(),
            addrs: vec!["192.168.1.2:8790".into(), "201.1.2.3:8790".into()],
        };
        assert_eq!(Invite::parse(&inv.link()).unwrap(), inv);
        let lector = Invite { signer: None, ..inv.clone() };
        assert_eq!(Invite::parse(&lector.link()).unwrap().signer, None);
        assert!(Invite::parse("https://evil").is_err());
    }

    #[test]
    fn solo_vale_lo_firmado_por_un_editor() {
        let sk = SigningKey::random(&mut rand_core::OsRng);
        let vk = VerifyingKey::from(&sk);
        let op = sign_op("doc", &sk, "pc", r#"{"t":"stroke"}"#.into());
        assert!(verify_op("doc", &vk, &op));
        assert!(!verify_op("otro", &vk, &op), "la firma va atada al documento");
        let mut forged = op.clone();
        forged.body = r#"{"t":"erase"}"#.into();
        assert!(!verify_op("doc", &vk, &forged));
        let other = SigningKey::random(&mut rand_core::OsRng);
        let foreign = sign_op("doc", &other, "pc", r#"{"t":"stroke"}"#.into());
        assert!(!verify_op("doc", &vk, &foreign), "un lector no puede inventar cambios");
    }

    #[test]
    fn las_claves_se_guardan_como_texto() {
        let sk = SigningKey::random(&mut rand_core::OsRng);
        let public = URL_SAFE_NO_PAD.encode(VerifyingKey::from(&sk).to_public_key_der().unwrap().as_bytes());
        let signer = URL_SAFE_NO_PAD.encode(sk.to_pkcs8_der().unwrap().as_bytes());
        let (vk, sk2) = keys(&public, Some(&signer)).unwrap();
        let op = sign_op("d", sk2.as_ref().unwrap(), "x", "{}".into());
        assert!(verify_op("d", &vk, &op));
    }

    #[test]
    fn el_reto_no_revela_el_secreto() {
        let a = hmac_hex("secreto", "join\nn1");
        assert_ne!(a, hmac_hex("secreto", "join\nn2"));
        assert_ne!(a, hmac_hex("otro", "join\nn1"));
        assert!(!a.contains("secreto"));
    }
}
