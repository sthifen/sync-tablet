//! «Crear mi servidor de cuentas»: publica el tracker en la cuenta de
//! Cloudflare de quien pulsa el botón, sin terminal ni GitHub.
//!
//! El código del tracker va dentro del programa; se escribe en
//! `.data/tracker/` y se publica con `npx wrangler` (necesita Node.js). Cada
//! paso y todo lo que imprime se enseña en vivo en el visor.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};

use anyhow::{Result, anyhow, bail};
use serde::Serialize;
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::server::AppState;

const WRANGLER: &str = "wrangler@4.45.0";
const DB_NAME: &str = "synctablet";
const MAX_LOG: usize = 200_000;

const FILES: &[(&str, &str)] = &[
    ("wrangler.toml", include_str!("../tracker/wrangler.toml")),
    ("src/index.ts", include_str!("../tracker/src/index.ts")),
    ("src/api.ts", include_str!("../tracker/src/api.ts")),
    ("src/repo.ts", include_str!("../tracker/src/repo.ts")),
    ("src/crypto.ts", include_str!("../tracker/src/crypto.ts")),
    ("migrations/0001_cuentas.sql", include_str!("../tracker/migrations/0001_cuentas.sql")),
];

const STEPS: &[&str] = &[
    "Comprobar Node.js",
    "Preparar el servidor de cuentas",
    "Entrar en Cloudflare (se abre el navegador)",
    "Crear la base de datos",
    "Crear las tablas",
    "Publicar en internet",
    "Comprobar que responde",
];

#[derive(Serialize, Clone, Default)]
pub struct Step {
    label: &'static str,
    /// pending, running, ok o error
    state: &'static str,
}

#[derive(Serialize, Clone, Default)]
pub struct Setup {
    running: bool,
    steps: Vec<Step>,
    log: String,
    /// Enlace para autorizar, por si el navegador no se abrió solo.
    login_url: Option<String>,
    url: Option<String>,
    error: Option<String>,
    hint: Option<String>,
}

pub type Shared = Mutex<Setup>;

pub fn new() -> Shared {
    Mutex::new(Setup { steps: fresh_steps(), ..Default::default() })
}

fn fresh_steps() -> Vec<Step> {
    STEPS.iter().map(|l| Step { label: l, state: "pending" }).collect()
}

pub fn snapshot(s: &AppState) -> Setup {
    s.tracker_setup.lock().unwrap().clone()
}

/// Empieza (si no estaba ya en marcha).
pub fn start(state: Arc<AppState>, root: PathBuf) -> bool {
    {
        let mut s = state.tracker_setup.lock().unwrap();
        if s.running {
            return false;
        }
        *s = Setup { running: true, steps: fresh_steps(), ..Default::default() };
    }
    tokio::spawn(async move {
        let result = run(&state, &root).await;
        let mut s = state.tracker_setup.lock().unwrap();
        s.running = false;
        if let Err(e) = result {
            for st in s.steps.iter_mut().filter(|st| st.state == "running") {
                st.state = "error";
            }
            let msg = format!("{e:#}");
            s.hint = hint_for(&s.log, &msg);
            s.log.push_str(&format!("\n✗ {msg}\n"));
            s.error = Some(msg);
        }
    });
    true
}

fn step(state: &AppState, i: usize, st: &'static str) {
    let mut s = state.tracker_setup.lock().unwrap();
    if let Some(x) = s.steps.get_mut(i) {
        x.state = st;
    }
    if st == "running" {
        let label = STEPS[i];
        s.log.push_str(&format!("\n▸ {label}\n"));
    }
}

fn log(state: &AppState, line: &str) {
    let clean = strip_ansi(line);
    let mut s = state.tracker_setup.lock().unwrap();
    if s.login_url.is_none() {
        if let Some(i) = clean.find("https://dash.cloudflare.com/oauth2/auth") {
            s.login_url = Some(clean[i..].split_whitespace().next().unwrap_or_default().to_string());
        }
    }
    s.log.push_str(&clean);
    s.log.push('\n');
    if s.log.len() > MAX_LOG {
        let cut = s.log.len() - MAX_LOG;
        let cut = (cut..s.log.len()).find(|&i| s.log.is_char_boundary(i)).unwrap_or(0);
        s.log.drain(..cut);
    }
}

fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            if chars.peek() == Some(&'[') {
                chars.next();
                for n in chars.by_ref() {
                    if n.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        if c != '\r' {
            out.push(c);
        }
    }
    out
}

/// Ejecuta `npx wrangler …` en `dir`, enseñando todo lo que imprime. Devuelve la salida.
async fn wrangler(state: &AppState, dir: &Path, args: &[&str]) -> Result<(bool, String)> {
    log(state, &format!("$ wrangler {}", args.join(" ")));
    let mut child = tokio::process::Command::new("npx")
        .arg("--yes")
        .arg(WRANGLER)
        .args(args)
        .current_dir(dir)
        .env("FORCE_COLOR", "0")
        .env("WRANGLER_SEND_METRICS", "false")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| anyhow!("no se pudo ejecutar npx: {e}"))?;
    let stdout = child.stdout.take().ok_or_else(|| anyhow!("sin salida"))?;
    let stderr = child.stderr.take().ok_or_else(|| anyhow!("sin salida"))?;
    let out = Mutex::new(String::new());
    // Las dos tuberías a la vez, línea a línea, para verlo en vivo.
    let pump = |pipe: Box<dyn tokio::io::AsyncRead + Unpin + Send>| {
        let out = &out;
        async move {
            let mut lines = BufReader::new(pipe).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                log(state, &l);
                let mut o = out.lock().unwrap();
                o.push_str(&l);
                o.push('\n');
            }
        }
    };
    tokio::join!(pump(Box::new(stdout)), pump(Box::new(stderr)));
    let status = child.wait().await?;
    let text = out.into_inner().unwrap();
    Ok((status.success(), text))
}

async fn run(state: &AppState, root: &Path) -> Result<()> {
    // 1. Node.js
    step(state, 0, "running");
    match tokio::process::Command::new("node").arg("--version").output().await {
        Ok(o) if o.status.success() => log(state, &format!("Node.js {}", String::from_utf8_lossy(&o.stdout).trim())),
        _ => bail!("falta Node.js"),
    }
    step(state, 0, "ok");

    // 2. Archivos
    step(state, 1, "running");
    let dir = root.join(".data/tracker");
    for (name, text) in FILES {
        let p = dir.join(name);
        std::fs::create_dir_all(p.parent().unwrap())?;
        if !p.exists() || *name != "wrangler.toml" {
            std::fs::write(&p, text)?;
        }
    }
    log(state, &format!("código en {}", dir.display()));
    step(state, 1, "ok");

    // 3. Cloudflare
    step(state, 2, "running");
    let (_, who) = wrangler(state, &dir, &["whoami"]).await?;
    if who.contains("not authenticated") || who.contains("You are not") {
        log(state, "Autoriza a Wrangler en la ventana del navegador que se acaba de abrir (si no tienes cuenta, puedes crearla ahí mismo).");
        let (ok, _) = wrangler(state, &dir, &["login"]).await?;
        if !ok {
            bail!("no se completó la autorización en Cloudflare");
        }
    }
    step(state, 2, "ok");

    // 4. Base de datos (la reutiliza si ya existe)
    step(state, 3, "running");
    let id = match find_db(state, &dir).await? {
        Some(id) => {
            log(state, &format!("ya existía la base «{DB_NAME}»"));
            id
        }
        None => {
            let (ok, _) = wrangler(state, &dir, &["d1", "create", DB_NAME]).await?;
            if !ok {
                bail!("Cloudflare no creó la base de datos");
            }
            find_db(state, &dir).await?.ok_or_else(|| anyhow!("no aparece la base recién creada"))?
        }
    };
    let toml_path = dir.join("wrangler.toml");
    let toml = std::fs::read_to_string(&toml_path)?;
    let toml: String = toml
        .lines()
        .map(|l| if l.trim_start().starts_with("database_id") { format!("database_id = \"{id}\"") } else { l.to_string() })
        .collect::<Vec<_>>()
        .join("\n");
    std::fs::write(&toml_path, toml + "\n")?;
    step(state, 3, "ok");

    // 5. Tablas
    step(state, 4, "running");
    let (ok, _) = wrangler(state, &dir, &["d1", "migrations", "apply", "DB", "--remote"]).await?;
    if !ok {
        bail!("no se pudieron crear las tablas");
    }
    step(state, 4, "ok");

    // 6. Publicar
    step(state, 5, "running");
    let (ok, out) = wrangler(state, &dir, &["deploy"]).await?;
    if !ok {
        bail!("Cloudflare no aceptó la publicación");
    }
    let url = workers_url(&out).ok_or_else(|| anyhow!("no se encontró la dirección publicada en la salida"))?;
    step(state, 5, "ok");

    // 7. Comprobar (a veces tarda unos segundos en estar en todo el mundo)
    step(state, 6, "running");
    let health = format!("{url}/v1/health");
    let mut up = false;
    for _ in 0..15 {
        let h = health.clone();
        if tokio::task::spawn_blocking(move || ureq::get(&h).timeout(std::time::Duration::from_secs(5)).call().is_ok()).await.unwrap_or(false) {
            up = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
    if !up {
        bail!("se publicó en {url} pero todavía no responde; prueba «Reintentar» en un minuto");
    }
    log(state, &format!("✓ responde en {url}"));
    state.account.set_tracker(&url);
    step(state, 6, "ok");
    state.tracker_setup.lock().unwrap().url = Some(url);
    Ok(())
}

async fn find_db(state: &AppState, dir: &Path) -> Result<Option<String>> {
    let (ok, out) = wrangler(state, dir, &["d1", "list", "--json"]).await?;
    if !ok {
        bail!("no se pudo leer la lista de bases de datos");
    }
    // La salida puede traer avisos antes del JSON.
    let json = out.find('[').map(|i| &out[i..]).unwrap_or("[]");
    let list: serde_json::Value = serde_json::from_str(json.trim()).unwrap_or_default();
    Ok(list.as_array().and_then(|a| {
        a.iter().find(|d| d["name"] == DB_NAME).and_then(|d| d["uuid"].as_str().map(str::to_owned))
    }))
}

fn workers_url(out: &str) -> Option<String> {
    out.split_whitespace()
        .map(|w| w.trim_matches(|c: char| !(c.is_ascii_alphanumeric() || c == ':' || c == '/' || c == '.' || c == '-')))
        .find(|w| w.starts_with("https://") && w.ends_with(".workers.dev"))
        .map(str::to_owned)
}

/// Qué hacer, en palabras, según el error.
fn hint_for(log: &str, err: &str) -> Option<String> {
    if err.contains("falta Node.js") || err.contains("npx") {
        return Some("Instala Node.js (en Fedora: sudo dnf install nodejs) y pulsa «Reintentar».".into());
    }
    if log.contains("workers.dev subdomain") || log.contains("register a workers.dev") {
        return Some("Tu cuenta de Cloudflare aún no tiene subdominio workers.dev: abre dash.cloudflare.com → Workers & Pages, elige uno y pulsa «Reintentar».".into());
    }
    if log.contains("More than one account") {
        return Some("Tu usuario tiene varias cuentas de Cloudflare; deja una sola o avísanos para elegirla.".into());
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encuentra_la_direccion_publicada() {
        let out = "Uploaded synctablet-tracker (3.1 sec)\nDeployed synctablet-tracker triggers (0.4 sec)\n  https://synctablet-tracker.ana-x.workers.dev\nCurrent Version ID: 1";
        assert_eq!(workers_url(out).as_deref(), Some("https://synctablet-tracker.ana-x.workers.dev"));
        assert_eq!(workers_url("nada"), None);
    }

    #[test]
    fn limpia_colores() {
        assert_eq!(strip_ansi("\x1b[32m✓\x1b[0m listo\r"), "✓ listo");
    }
}
