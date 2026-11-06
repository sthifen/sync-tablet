//! Sync Tablet: los apuntes a mano de la tablet Android aparecen en vivo en
//! el PC con Linux (`http://localhost:8788`) y se guardan en `~/SyncTablet`
//! como JSON (la fuente de verdad) y SVG (para abrirlos en cualquier lado).
//!
//! Por USB usa `adb reverse` (ver `adb.rs`); por WiFi, la app se conecta a
//! la IP del PC.

mod adb;
mod auth;
mod ink;
mod notes;
mod server;
mod store;
mod svg;

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result};
use clap::Parser;

#[derive(Parser)]
#[command(version, about = "Apuntes a mano en la tablet Android, en vivo en tu PC con Linux")]
struct Args {
    /// Puerto HTTP (la app de la tablet usa el 8788)
    #[arg(long, default_value_t = 8788)]
    port: u16,
    /// Dirección de escucha (0.0.0.0 para que la tablet llegue por WiFi)
    #[arg(long, default_value = "0.0.0.0")]
    bind: IpAddr,
    /// Carpeta de los apuntes
    #[arg(long)]
    dir: Option<PathBuf>,
    /// No configurar `adb reverse` para las tablets por USB
    #[arg(long)]
    no_usb: bool,
    /// No abrir el visor en el navegador al arrancar
    #[arg(long)]
    no_open: bool,
    /// Añadir Sync Tablet al menú de aplicaciones (con su icono) y salir
    #[arg(long, visible_alias = "install")]
    instalar: bool,
}

const ICON: &str = include_str!("../android/icon.svg");

/// Acceso directo en el menú: sin ventana propia, abre el visor en el navegador.
fn install() -> Result<()> {
    let exe = std::env::current_exe()?;
    let home = PathBuf::from(std::env::var_os("HOME").unwrap_or_default());
    let icons = home.join(".local/share/icons/hicolor/scalable/apps");
    let apps = home.join(".local/share/applications");
    std::fs::create_dir_all(&icons)?;
    std::fs::create_dir_all(&apps)?;
    std::fs::write(icons.join("synctablet.svg"), ICON)?;
    std::fs::write(
        apps.join("synctablet.desktop"),
        format!(
            "[Desktop Entry]\nType=Application\nName=Sync Tablet\nComment=Apuntes a mano de la tablet, en vivo en el PC\n\
             Exec=\"{}\"\nIcon=synctablet\nTerminal=false\nCategories=Office;\n",
            exe.display()
        ),
    )?;
    println!("Sync Tablet ya está en el menú de aplicaciones ({}).", apps.join("synctablet.desktop").display());
    Ok(())
}

fn open_viewer(port: u16) {
    let url = format!("http://localhost:{port}");
    if std::process::Command::new("xdg-open").arg(&url).spawn().is_err() {
        tracing::info!("abre {url} en el navegador");
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "error,synctablet=info".into()),
        )
        .with_target(false)
        .init();
    let args = Args::parse();
    if args.instalar {
        return install();
    }
    let dir = args
        .dir
        .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join("SyncTablet"));
    let store = store::Store::open(&dir)?;
    let notes = notes::Notes::open(&dir)?;
    let auth = auth::Auth::open(&dir)?;
    let (live, _) = tokio::sync::broadcast::channel(256);
    let state = Arc::new(server::AppState {
        store: Mutex::new(store),
        notes: Mutex::new(notes),
        live,
        devices: Mutex::new(Vec::new()),
        auth,
    });

    let addr = SocketAddr::new(args.bind, args.port);
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        // Ya estaba abierto (otro doble clic): sólo enseñar el visor.
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
            tracing::info!("Sync Tablet ya estaba en marcha");
            if !args.no_open {
                open_viewer(args.port);
            }
            return Ok(());
        }
        Err(e) => return Err(e).with_context(|| format!("no se pudo escuchar en {addr}")),
    };
    if !args.no_open {
        open_viewer(args.port);
    }
    if !args.no_usb {
        tokio::spawn(adb::watch(args.port));
    }
    tracing::info!("apuntes en {}; visor en http://localhost:{}", dir.display(), args.port);
    axum::serve(listener, server::router(state).into_make_service_with_connect_info::<SocketAddr>())
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}
