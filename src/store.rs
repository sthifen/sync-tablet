//! Los apuntes en disco, en `~/SyncTablet`:
//!
//! ```text
//! .data/notebooks.json        cuadernos (orden de páginas incluido)
//! .data/pages/<id>.json       cada página con sus trazos: la fuente de verdad
//! .data/assets/<id>.jpg       imágenes y páginas de PDF
//! <Cuaderno>/Página 01.svg    copia para abrir en cualquier programa
//! ```
//!
//! Los SVG se rehacen a partir de los JSON; se pueden borrar sin perder nada.

use std::collections::HashMap;
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
        tracing::info!("{} cuadernos y {} páginas en {}", notebooks.len(), pages.len(), root.display());
        Ok(Self { root: root.to_owned(), notebooks, pages, exported })
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

    pub fn put_notebook(&mut self, nb: Notebook) -> Result<()> {
        if !valid_id(&nb.id) || !nb.pages.iter().all(|p| valid_id(p)) {
            bail!("cuaderno con id inválido");
        }
        let folder = folder_name(&nb.title);
        if let Some(old) = self.exported.get(&nb.id).filter(|old| **old != folder) {
            let (from, to) = (self.root.join(old), self.root.join(&folder));
            if from.is_dir() && !to.exists() {
                fs::rename(&from, &to)?;
            }
        }
        self.exported.insert(nb.id.clone(), folder);
        match self.notebooks.iter_mut().find(|n| n.id == nb.id) {
            Some(n) => *n = nb.clone(),
            None => self.notebooks.push(nb.clone()),
        }
        self.save_notebooks()?;
        self.export_notebook(&nb)
    }

    pub fn put_page(&mut self, page: Page) -> Result<()> {
        if !valid_id(&page.id) || !valid_id(&page.notebook) {
            bail!("página con id inválido");
        }
        self.save_page(&page)?;
        self.pages.insert(page.id.clone(), page);
        Ok(())
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

    fn nb(title: &str, pages: &[&str]) -> Notebook {
        Notebook { id: "nb1".into(), title: title.into(), color: "#000000".into(), pages: pages.iter().map(|s| s.to_string()).collect(), folder: String::new(), favorite: false, updated: 0 }
    }

    fn page(id: &str) -> Page {
        Page { id: id.into(), notebook: "nb1".into(), version: 0, background: Background::Lines, strokes: vec![], items: vec![], bg_asset: None }
    }

    fn stroke() -> Stroke {
        Stroke { id: "s1".into(), tool: Tool::Pen, color: "#000000".into(), width: 3.0, points: vec![1.0, 1.0, 1.0] }
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
