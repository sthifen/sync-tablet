//! Los apuntes en disco, en `~/SyncTablet`:
//!
//! ```text
//! .data/notebooks.json        cuadernos (orden de páginas incluido)
//! .data/pages/<id>.json       cada página con sus trazos: la fuente de verdad
//! .data/assets/<id>.jpg       imágenes y páginas de PDF
//! .data/seen.json            qué versión de cada página y cuaderno vio cada aparato
//! <Cuaderno>/Página 01.svg    copia para abrir en cualquier programa
//! ```
//!
//! Los SVG se rehacen a partir de los JSON; se pueden borrar sin perder nada.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};

use crate::ink::{Applied, Notebook, Page, Stroke};
use crate::svg::page_svg;

pub struct Store {
    root: PathBuf,
    notebooks: Vec<Notebook>,
    pages: HashMap<String, Page>,
    /// Carpeta en la que se exportó cada cuaderno, para renombrarla.
    exported: HashMap<String, String>,
    seen: Seen,
}

/// Lo que vio cada aparato (tablet, la app del PC…): con esto, al conectarse,
/// se le manda lo que cambiaron los demás, y lo que él borró sin conexión no
/// vuelve (lo tenía y ya no lo tiene).
#[derive(Default, serde::Serialize, serde::Deserialize)]
struct Seen {
    /// página → aparato → versión.
    #[serde(default)]
    pages: HashMap<String, HashMap<String, u64>>,
    /// cuaderno → aparato → revisión.
    #[serde(default)]
    notebooks: HashMap<String, HashMap<String, u64>>,
    /// Revisión de cada cuaderno (sube con cada cambio).
    #[serde(default)]
    rev: HashMap<String, u64>,
}

/// Lo que había antes de recordar aparatos: lo vio «la tablet», sea cual sea.
const LEGACY: &str = "*";

/// Lo que hay que mandarle a un aparato al conectarse.
#[derive(Default)]
pub struct Plan {
    pub notebooks: Vec<Notebook>,
    pub pages: Vec<Page>,
    /// Páginas que el aparato tiene más nuevas: que las mande.
    pub want: Vec<String>,
}

/// Los ids vienen de la tablet y acaban en nombres de archivo.
pub fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Nombre de carpeta a partir del título del cuaderno.
pub fn folder_name(title: &str) -> String {
    let clean: String = title
        .chars()
        .map(|c| if c == '/' || c == '\\' || c.is_control() { ' ' } else { c })
        .collect::<String>()
        .trim()
        .trim_start_matches(['.', ' '])
        .chars()
        .take(80)
        .collect();
    if clean.trim().is_empty() { "Sin título".into() } else { clean.trim().to_owned() }
}

fn write_atomic(path: &Path, data: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, data).with_context(|| format!("no se pudo escribir {}", tmp.display()))?;
    fs::rename(&tmp, path)?;
    Ok(())
}

impl Store {
    pub fn open(root: &Path) -> Result<Self> {
        let data = root.join(".data");
        fs::create_dir_all(data.join("pages")).with_context(|| format!("no se pudo crear {}", data.display()))?;
        fs::create_dir_all(data.join("assets"))?;
        let notebooks: Vec<Notebook> = fs::read(data.join("notebooks.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        let mut pages = HashMap::new();
        for entry in fs::read_dir(data.join("pages"))?.flatten() {
            if entry.path().extension().is_some_and(|e| e == "json") {
                match fs::read(entry.path()).ok().and_then(|b| serde_json::from_slice::<Page>(&b).ok()) {
                    Some(p) => {
                        pages.insert(p.id.clone(), p);
                    }
                    None => tracing::warn!("página ilegible: {}", entry.path().display()),
                }
            }
        }
        let exported = notebooks.iter().map(|n| (n.id.clone(), folder_name(&n.title))).collect();
        let seen = match fs::read(data.join("seen.json")).ok().and_then(|b| serde_json::from_slice::<Seen>(&b).ok()) {
            Some(s) => s,
            None => {
                let mut s = Seen::default();
                for p in pages.values() {
                    s.pages.entry(p.id.clone()).or_default().insert(LEGACY.into(), p.version);
                }
                for n in &notebooks {
                    s.notebooks.entry(n.id.clone()).or_default().insert(LEGACY.into(), 0);
                }
                s
            }
        };
        tracing::info!("{} cuadernos y {} páginas en {}", notebooks.len(), pages.len(), root.display());
        let store = Self { root: root.to_owned(), notebooks, pages, exported, seen };
        store.save_seen();
        Ok(store)
    }

    fn save_seen(&self) {
        if let Ok(b) = serde_json::to_vec(&self.seen) {
            let _ = write_atomic(&self.root.join(".data/seen.json"), &b);
        }
    }

    /// El aparato tiene esa versión de la página.
    pub fn saw_page(&mut self, device: &str, id: &str, version: u64) {
        let e = self.seen.pages.entry(id.to_owned()).or_default().entry(device.to_owned()).or_insert(0);
        *e = (*e).max(version);
    }

    /// El aparato tiene el cuaderno como está ahora.
    pub fn saw_notebook(&mut self, device: &str, id: &str) {
        let rev = self.seen.rev.get(id).copied().unwrap_or(0);
        self.seen.notebooks.entry(id.to_owned()).or_default().insert(device.to_owned(), rev);
    }

    pub fn persist_seen(&self) {
        self.save_seen();
    }

    /// Qué le falta a `device` (tablet o PC) según lo que dijo tener al conectarse.
    pub fn plan(&mut self, device: &str, tablet: bool, have: &HashMap<String, u64>, notebooks: Option<&[String]>) -> Plan {
        let mut plan = Plan::default();
        let had_before = |m: Option<&HashMap<String, u64>>| m.is_some_and(|m| m.contains_key(device) || (tablet && m.contains_key(LEGACY)));
        let theirs: Option<HashSet<&str>> = notebooks.map(|l| l.iter().map(String::as_str).collect());
        for n in &self.notebooks {
            let rev = self.seen.rev.get(&n.id).copied().unwrap_or(0);
            let seen = self.seen.notebooks.get(&n.id);
            let mine = seen.and_then(|m| m.get(device)).copied();
            let has = theirs.as_ref().map(|t| t.contains(n.id.as_str()));
            let send = match has {
                // Lo tiene: si cambió desde que lo vio.
                Some(true) => mine.is_none_or(|r| r < rev),
                // No lo tiene: si nunca lo tuvo (si lo tuvo, lo borró).
                Some(false) => !had_before(seen),
                // Tablet vieja que no dice qué tiene: sólo lo que cambió y ya conocía.
                None => mine.is_some_and(|r| r < rev),
            };
            if send {
                plan.notebooks.push(n.clone());
            }
        }
        for p in self.pages.values() {
            let send = match have.get(&p.id) {
                Some(v) => *v < p.version,
                None => !had_before(self.seen.pages.get(&p.id)),
            };
            if send {
                plan.pages.push(p.clone());
            }
        }
        for (id, v) in have {
            if self.pages.get(id).is_none_or(|p| p.version < *v) {
                plan.want.push(id.clone());
            }
            self.saw_page(device, id, *v);
        }
        for p in &plan.pages {
            let (id, v) = (p.id.clone(), p.version);
            self.saw_page(device, &id, v);
        }
        if let Some(list) = notebooks {
            for id in list {
                self.saw_notebook(device, id);
            }
        }
        for n in plan.notebooks.iter().map(|n| n.id.clone()).collect::<Vec<_>>() {
            self.saw_notebook(device, &n);
        }
        plan.want.sort();
        // Primero los cuadernos que tienen menos páginas: se ven antes.
        plan.pages.sort_by_key(|p| p.id.clone());
        self.save_seen();
        plan
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn asset_path(&self, id: &str) -> Option<PathBuf> {
        valid_id(id).then(|| self.root.join(format!(".data/assets/{id}.jpg")))
    }

    pub fn has_asset(&self, id: &str) -> bool {
        self.asset_path(id).is_some_and(|p| p.is_file())
    }

    /// Guarda una imagen (base64) que manda la tablet.
    pub fn put_asset(&mut self, id: &str, data: &str) -> Result<()> {
        use base64::Engine;
        let Some(path) = self.asset_path(id) else { bail!("imagen con id inválido") };
        let bytes = base64::engine::general_purpose::STANDARD.decode(data.trim()).context("imagen mal codificada")?;
        write_atomic(&path, &bytes)?;
        // Las páginas que la usan ya pueden exportarse con ella.
        let users: Vec<Page> = self.pages.values().filter(|p| p.assets().any(|a| a == id)).cloned().collect();
        for p in &users {
            self.export_page(p)?;
        }
        Ok(())
    }

    /// Guarda un documento de la tablet en su carpeta (Hojas de cálculo, Documentos…).
    pub fn put_file(&self, kind: &str, title: &str, ext: &str, data: &str) -> Result<PathBuf> {
        use base64::Engine;
        let folder = match kind {
            "sheet" => "Hojas de cálculo",
            "doc" => "Documentos",
            "deck" => "Presentaciones",
            _ => bail!("tipo de documento desconocido"),
        };
        if !matches!(ext, "xlsx" | "docx" | "pptx" | "pdf") {
            bail!("formato no permitido");
        }
        let bytes = base64::engine::general_purpose::STANDARD.decode(data.trim()).context("documento mal codificado")?;
        let dir = self.root.join(folder);
        fs::create_dir_all(&dir)?;
        let path = dir.join(format!("{}.{ext}", folder_name(title)));
        write_atomic(&path, &bytes)?;
        Ok(path)
    }

    /// Imágenes que usan las páginas y aún no están en el PC.
    pub fn missing_assets(&self, page: &Page) -> Vec<String> {
        page.assets().filter(|a| !self.has_asset(a)).map(str::to_owned).collect()
    }

    pub fn notebooks(&self) -> &[Notebook] {
        &self.notebooks
    }

    pub fn page(&self, id: &str) -> Option<&Page> {
        self.pages.get(id)
    }

    /// Versión de cada página que hay en el PC.
    pub fn manifest(&self) -> HashMap<String, u64> {
        self.pages.iter().map(|(id, p)| (id.clone(), p.version)).collect()
    }

    fn save_notebooks(&self) -> Result<()> {
        write_atomic(&self.root.join(".data/notebooks.json"), &serde_json::to_vec_pretty(&self.notebooks)?)
    }

    fn save_page(&self, page: &Page) -> Result<()> {
        write_atomic(&self.root.join(format!(".data/pages/{}.json", page.id)), &serde_json::to_vec(page)?)?;
        self.export_page(page)
    }

    /// `<Cuaderno>/Página NN.svg` de una página (si su cuaderno la lista).
    fn export_page(&self, page: &Page) -> Result<()> {
        let Some(nb) = self.notebooks.iter().find(|n| n.id == page.notebook) else { return Ok(()) };
        let Some(i) = nb.pages.iter().position(|p| *p == page.id) else { return Ok(()) };
        let dir = self.root.join(folder_name(&nb.title));
        fs::create_dir_all(&dir)?;
        write_atomic(&dir.join(format!("Página {:02}.svg", i + 1)), page_svg(page, "../.data/assets/").as_bytes())
    }

    /// Rehace los SVG de un cuaderno (páginas nuevas, quitadas o reordenadas).
    fn export_notebook(&self, nb: &Notebook) -> Result<()> {
        let dir = self.root.join(folder_name(&nb.title));
        fs::create_dir_all(&dir)?;
        for id in &nb.pages {
            if let Some(p) = self.pages.get(id) {
                self.export_page(p)?;
            }
        }
        // Sobran las de números más altos que el total.
        for entry in fs::read_dir(&dir)?.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let n = name.strip_prefix("Página ").and_then(|r| r.strip_suffix(".svg")).and_then(|n| n.parse::<usize>().ok());
            if n.is_some_and(|n| n > nb.pages.len()) {
                let _ = fs::remove_file(entry.path());
            }
        }
        Ok(())
    }

    /// Guarda un cuaderno; devuelve si cambió algo.
    pub fn put_notebook(&mut self, nb: Notebook) -> Result<bool> {
        if !valid_id(&nb.id) || !nb.pages.iter().all(|p| valid_id(p)) {
            bail!("cuaderno con id inválido");
        }
        if self.notebooks.iter().any(|n| *n == nb) {
            return Ok(false);
        }
        let folder = folder_name(&nb.title);
        if let Some(old) = self.exported.get(&nb.id).filter(|old| **old != folder) {
            let (from, to) = (self.root.join(old), self.root.join(&folder));
            if from.is_dir() && !to.exists() {
                fs::rename(&from, &to)?;
            }
        }
        self.exported.insert(nb.id.clone(), folder);
        *self.seen.rev.entry(nb.id.clone()).or_insert(0) += 1;
        match self.notebooks.iter_mut().find(|n| n.id == nb.id) {
            Some(n) => *n = nb.clone(),
            None => self.notebooks.push(nb.clone()),
        }
        self.save_notebooks()?;
        self.export_notebook(&nb)?;
        Ok(true)
    }

    /// Guarda una página entera. Si llega más vieja que la del PC (otro aparato
    /// escribió entretanto) se juntan; devuelve si se juntó (hay que reenviarla
    /// a todos, también a quien la mandó).
    pub fn put_page(&mut self, page: Page) -> Result<bool> {
        if !valid_id(&page.id) || !valid_id(&page.notebook) {
            bail!("página con id inválido");
        }
        let id = page.id.clone();
        let merged = match self.pages.get_mut(&page.id) {
            Some(cur) if page.version < cur.version => {
                cur.merge_older(page);
                true
            }
            _ => {
                self.pages.insert(page.id.clone(), page);
                false
            }
        };
        self.save_page(&self.pages[&id])?;
        Ok(merged)
    }

    pub fn add_stroke(&mut self, page: &str, version: u64, stroke: Stroke) -> Result<Applied> {
        let Some(p) = self.pages.get_mut(page) else { return Ok(Applied::Gap) };
        let r = p.add_stroke(version, stroke);
        if r == Applied::Ok {
            let p = &self.pages[page];
            self.save_page(p)?;
        }
        Ok(r)
    }

    pub fn erase(&mut self, page: &str, version: u64, ids: &[String]) -> Result<Applied> {
        let Some(p) = self.pages.get_mut(page) else { return Ok(Applied::Gap) };
        let r = p.erase(version, ids);
        if r == Applied::Ok {
            let p = &self.pages[page];
            self.save_page(p)?;
        }
        Ok(r)
    }

    pub fn delete_page(&mut self, id: &str) -> Result<()> {
        if self.pages.remove(id).is_some() {
            let _ = fs::remove_file(self.root.join(format!(".data/pages/{id}.json")));
        }
        Ok(())
    }

    pub fn delete_notebook(&mut self, id: &str) -> Result<()> {
        let Some(i) = self.notebooks.iter().position(|n| n.id == id) else { return Ok(()) };
        let nb = self.notebooks.remove(i);
        for p in &nb.pages {
            self.delete_page(p)?;
        }
        // La carpeta de SVG se queda (puede tener archivos del usuario); se
        // quitan sólo las páginas exportadas.
        if let Some(folder) = self.exported.remove(id) {
            let dir = self.root.join(folder);
            for n in 1..=nb.pages.len() {
                let _ = fs::remove_file(dir.join(format!("Página {n:02}.svg")));
            }
            let _ = fs::remove_dir(dir);
        }
        self.save_notebooks()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ink::{Background, Tool};

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("synctablet-test-{}-{}", std::process::id(), rand_suffix()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    fn rand_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    }

    #[test]
    fn documentos_en_su_carpeta() {
        use base64::Engine;
        let dir = std::env::temp_dir().join(format!("st-files-{}", std::process::id()));
        let store = Store::open(&dir).unwrap();
        let data = base64::engine::general_purpose::STANDARD.encode(b"PK");
        let p = store.put_file("sheet", "Notas/../del curso", "xlsx", &data).unwrap();
        assert!(p.starts_with(dir.join("Hojas de cálculo")));
        assert!(store.put_file("sheet", "x", "exe", &data).is_err());
        assert!(store.put_file("raro", "x", "xlsx", &data).is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    fn nb(title: &str, pages: &[&str]) -> Notebook {
        Notebook { id: "nb1".into(), title: title.into(), color: "#000000".into(), pages: pages.iter().map(|s| s.to_string()).collect(), folder: String::new(), favorite: false, updated: 0, right: Vec::new() }
    }

    fn page(id: &str) -> Page {
        Page { id: id.into(), notebook: "nb1".into(), version: 0, background: Background::Lines, strokes: vec![], items: vec![], bg_asset: None, bg_text: None, notes: vec![], marks: vec![] }
    }

    fn stroke() -> Stroke {
        Stroke { id: "s1".into(), tool: Tool::Pen, color: "#000000".into(), width: 3.0, points: vec![1.0, 1.0, 1.0], shape: None }
    }

    #[test]
    fn saves_exports_and_reloads() {
        let root = tmp();
        let mut s = Store::open(&root).unwrap();
        s.put_notebook(nb("Cálculo", &["p1", "p2"])).unwrap();
        s.put_page(page("p1")).unwrap();
        s.put_page(page("p2")).unwrap();
        assert_eq!(s.add_stroke("p2", 1, stroke()).unwrap(), Applied::Ok);
        assert!(root.join("Cálculo/Página 02.svg").is_file());
        let svg = fs::read_to_string(root.join("Cálculo/Página 02.svg")).unwrap();
        assert!(svg.contains("<path"));

        let s = Store::open(&root).unwrap();
        assert_eq!(s.manifest()["p2"], 1);
        assert_eq!(s.page("p2").unwrap().strokes.len(), 1);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rename_and_remove_pages() {
        let root = tmp();
        let mut s = Store::open(&root).unwrap();
        s.put_notebook(nb("Física", &["p1", "p2"])).unwrap();
        s.put_page(page("p1")).unwrap();
        s.put_page(page("p2")).unwrap();
        s.put_notebook(nb("Física II", &["p2"])).unwrap();
        assert!(!root.join("Física").exists());
        assert!(root.join("Física II/Página 01.svg").is_file());
        assert!(!root.join("Física II/Página 02.svg").exists());
        s.delete_notebook("nb1").unwrap();
        assert!(!root.join("Física II").exists());
        assert!(s.page("p2").is_none());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn plan_entre_aparatos() {
        let dir = tmp();
        {
            // Lo de antes (sin seen.json) lo tenía la tablet.
            let mut s = Store::open(&dir).unwrap();
            s.put_notebook(nb("Viejo", &["p1", "p2"])).unwrap();
            s.put_page(page("p1")).unwrap();
            s.put_page(page("p2")).unwrap();
        }
        let _ = std::fs::remove_file(dir.join(".data/seen.json"));
        let mut s = Store::open(&dir).unwrap();
        let none = HashMap::new();
        // La app del PC, nueva: le llega todo.
        let pc = s.plan("PC", false, &none, Some(&[]));
        assert_eq!((pc.notebooks.len(), pc.pages.len()), (1, 2));
        // La tablet borró p2 sin conexión: no vuelve.
        let have: HashMap<String, u64> = [("p1".to_owned(), 0)].into();
        let tab = s.plan("Tab", true, &have, Some(&[nb("Viejo", &[]).id]));
        assert!(tab.pages.is_empty(), "{:?}", tab.pages.iter().map(|p| &p.id).collect::<Vec<_>>());
        // El PC escribe en p1: a la tablet le llega al conectar.
        s.add_stroke("p1", 1, stroke()).unwrap();
        s.saw_page("PC", "p1", 1);
        let tab = s.plan("Tab", true, &have, None);
        assert_eq!(tab.pages.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["p1"]);
        // Y el PC, que ya la tiene, no la vuelve a recibir; una página nueva del PC sí llega a la tablet.
        s.put_page(page("p3")).unwrap();
        s.saw_page("PC", "p3", 0);
        let have_pc: HashMap<String, u64> = [("p1".to_owned(), 1), ("p2".to_owned(), 0), ("p3".to_owned(), 0)].into();
        assert!(s.plan("PC", false, &have_pc, None).pages.is_empty());
        let have: HashMap<String, u64> = [("p1".to_owned(), 1)].into();
        let tab = s.plan("Tab", true, &have, None);
        assert_eq!(tab.pages.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["p3"]);
    }

    #[test]
    fn refuses_paths_in_ids() {
        let root = tmp();
        let mut s = Store::open(&root).unwrap();
        assert!(s.put_page(Page { id: "../x".into(), ..page("a") }).is_err());
        assert!(s.put_notebook(Notebook { id: "a/b".into(), ..nb("x", &[]) }).is_err());
        assert_eq!(folder_name("../../etc"), "etc");
        assert_eq!(folder_name(".oculto"), "oculto");
        assert_eq!(folder_name("  "), "Sin título");
        fs::remove_dir_all(root).unwrap();
    }
}
