//! Notas escritas en el PC (páginas de bloques: texto, títulos, listas,
//! tareas… y páginas de la tablet incrustadas). Se guardan en
//! `~/SyncTablet/.data/notes.json` y cada una se copia como
//! `~/SyncTablet/Notas/<Título>.md` para abrirla en Obsidian o donde sea.
//!
//! A diferencia de los apuntes a mano, aquí el dueño es el PC: el visor manda
//! la página entera al guardar (con medio segundo de espera mientras escribes).

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::store::{folder_name, valid_id};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Block {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub checked: bool,
    /// Campos de otros tipos de bloque (p. ej. `page` de una página de la
    /// tablet incrustada): se guardan tal cual.
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Note {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub icon: String,
    #[serde(default)]
    pub blocks: Vec<Block>,
    #[serde(default)]
    pub updated: u64,
}

#[derive(Serialize)]
pub struct Summary {
    pub id: String,
    pub title: String,
    pub icon: String,
    pub updated: u64,
    /// Todo el texto de la página, para la búsqueda.
    pub text: String,
}

/// Lo que puede cambiar el visor al guardar.
#[derive(Deserialize)]
pub struct Update {
    pub title: Option<String>,
    pub icon: Option<String>,
    pub blocks: Option<Vec<Block>>,
}

pub struct Notes {
    root: PathBuf,
    notes: Vec<Note>,
    /// Archivo `.md` de cada nota, para renombrarlo o borrarlo.
    exported: HashMap<String, String>,
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn new_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    let t = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0) as u64;
    format!("n{:x}{:x}", t, N.fetch_add(1, Ordering::Relaxed))
}

fn block(kind: &str, text: &str) -> Block {
    Block { id: new_id(), kind: kind.into(), text: text.into(), checked: false, extra: Map::new() }
}

fn welcome() -> Note {
    Note {
        id: new_id(),
        title: "Primeros pasos".into(),
        icon: "👋".into(),
        blocks: vec![
            block("text", "Aquí escribes en el PC. Lo que escribes a mano en la tablet sale en «Cuadernos de la tablet», en la barra lateral."),
            block("h2", "Cómo se usa"),
            block("bullet", "Escribe / en un bloque vacío para elegir el tipo: títulos, listas, tareas, citas…"),
            block("bullet", "Con «Página de la tablet» metes aquí una página escrita a mano; se actualiza sola."),
            block("bullet", "Cada nota se guarda también como Notas/<título>.md en tu carpeta Sync Tablet."),
            block("todo", "Escribir tu primera nota"),
        ],
        updated: now_ms(),
    }
}

/// La nota en Markdown (para Obsidian y compañía).
pub fn markdown(note: &Note) -> String {
    let mut out = format!("# {}\n\n", if note.title.trim().is_empty() { "Sin título" } else { note.title.trim() });
    let mut n = 0;
    let is_list = |k: &str| matches!(k, "bullet" | "number" | "todo");
    let mut prev_list = false;
    for b in &note.blocks {
        n = if b.kind == "number" { n + 1 } else { 0 };
        // Una lista termina con una línea en blanco antes de lo siguiente.
        if prev_list && !is_list(&b.kind) {
            out.push('\n');
        }
        prev_list = is_list(&b.kind);
        let line = match b.kind.as_str() {
            "h1" => format!("## {}", b.text),
            "h2" => format!("### {}", b.text),
            "h3" => format!("#### {}", b.text),
            "bullet" => format!("- {}", b.text),
            "number" => format!("{n}. {}", b.text),
            "todo" => format!("- [{}] {}", if b.checked { "x" } else { " " }, b.text),
            "quote" => format!("> {}", b.text.replace('\n', "\n> ")),
            "callout" => format!("> 💡 {}", b.text.replace('\n', "\n> ")),
            "divider" => "---".into(),
            "tablet" => {
                let label = b.extra.get("label").and_then(Value::as_str).unwrap_or("Página de la tablet");
                format!("*✏️ {label}*")
            }
            _ => b.text.clone(),
        };
        out.push_str(&line);
        // Las listas van seguidas; el resto, separado por una línea en blanco.
        out.push_str(if prev_list { "\n" } else { "\n\n" });
    }
    out
}

fn write_atomic(path: &Path, data: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, data).with_context(|| format!("no se pudo escribir {}", tmp.display()))?;
    fs::rename(&tmp, path)?;
    Ok(())
}

impl Notes {
    pub fn open(root: &Path) -> Result<Self> {
        let file = root.join(".data/notes.json");
        let notes = match fs::read(&file) {
            Ok(b) => serde_json::from_slice(&b).with_context(|| format!("{} está dañado", file.display()))?,
            Err(_) => vec![welcome()],
        };
        let mut s = Self { root: root.to_owned(), notes, exported: HashMap::new() };
        s.exported = s.notes.iter().map(|n| (n.id.clone(), s.file_name(n))).collect();
        if !file.exists() {
            s.save_all()?;
            for n in s.notes.clone() {
                s.export(&n)?;
            }
        }
        Ok(s)
    }

    pub fn list(&self) -> Vec<Summary> {
        self.notes
            .iter()
            .map(|n| Summary {
                id: n.id.clone(),
                title: n.title.clone(),
                icon: n.icon.clone(),
                updated: n.updated,
                text: n.blocks.iter().map(|b| b.text.as_str()).filter(|t| !t.is_empty()).collect::<Vec<_>>().join(" "),
            })
            .collect()
    }

    pub fn get(&self, id: &str) -> Option<&Note> {
        self.notes.iter().find(|n| n.id == id)
    }

    pub fn create(&mut self, title: String) -> Result<Note> {
        let note = Note { id: new_id(), title, icon: "📄".into(), blocks: vec![block("text", "")], updated: now_ms() };
        self.notes.push(note.clone());
        self.save_all()?;
        self.export(&note)?;
        Ok(note)
    }

    /// Guarda los cambios; crea la nota si no existía. Devuelve si cambió el
    /// título o el icono (hay que repintar la barra lateral).
    pub fn update(&mut self, id: &str, up: Update) -> Result<(bool, u64)> {
        if !valid_id(id) {
            bail!("nota con id inválido");
        }
        let created = self.get(id).is_none();
        if created {
            self.notes.push(Note { id: id.into(), title: String::new(), icon: "📄".into(), blocks: vec![], updated: 0 });
        }
        let note = self.notes.iter_mut().find(|n| n.id == id).unwrap();
        let renamed = created
            || up.title.as_ref().is_some_and(|t| *t != note.title)
            || up.icon.as_ref().is_some_and(|i| *i != note.icon);
        if let Some(t) = up.title {
            note.title = t.replace('\n', " ");
        }
        if let Some(i) = up.icon {
            note.icon = i;
        }
        if let Some(b) = up.blocks {
            note.blocks = b;
        }
        note.updated = now_ms();
        let (note, updated) = (note.clone(), note.updated);
        self.save_all()?;
        self.export(&note)?;
        Ok((renamed, updated))
    }

    pub fn delete(&mut self, id: &str) -> Result<bool> {
        let before = self.notes.len();
        self.notes.retain(|n| n.id != id);
        if self.notes.len() == before {
            return Ok(false);
        }
        if let Some(old) = self.exported.remove(id) {
            let _ = fs::remove_file(self.root.join("Notas").join(old));
        }
        self.save_all()?;
        Ok(true)
    }

    fn save_all(&self) -> Result<()> {
        fs::create_dir_all(self.root.join(".data"))?;
        write_atomic(&self.root.join(".data/notes.json"), &serde_json::to_vec_pretty(&self.notes)?)
    }

    /// `Título.md`; si una nota anterior ya se llama igual, se le añade el id.
    fn file_name(&self, note: &Note) -> String {
        let base = folder_name(&note.title);
        let taken = self.notes.iter().take_while(|o| o.id != note.id).any(|o| folder_name(&o.title) == base);
        if taken { format!("{base} ({}).md", &note.id[note.id.len().saturating_sub(4)..]) } else { format!("{base}.md") }
    }

    fn export(&mut self, note: &Note) -> Result<()> {
        let dir = self.root.join("Notas");
        fs::create_dir_all(&dir)?;
        let name = self.file_name(note);
        if let Some(old) = self.exported.get(&note.id).filter(|o| **o != name) {
            let _ = fs::remove_file(dir.join(old));
        }
        write_atomic(&dir.join(&name), markdown(note).as_bytes())?;
        self.exported.insert(note.id.clone(), name);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("synctablet-notes-{}-{}", std::process::id(), new_id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn create_rename_delete() {
        let root = tmp();
        let mut s = Notes::open(&root).unwrap();
        assert!(root.join("Notas/Primeros pasos.md").is_file());
        let n = s.create(String::new()).unwrap();
        let mut b = block("todo", "Repasar");
        b.checked = true;
        let up = Update { title: Some("Cálculo".into()), icon: None, blocks: Some(vec![block("h1", "Límites"), b]) };
        assert!(s.update(&n.id, up).unwrap().0);
        let md = fs::read_to_string(root.join("Notas/Cálculo.md")).unwrap();
        assert!(md.contains("## Límites") && md.contains("- [x] Repasar"));
        assert!(markdown(&Note { id: "x".into(), title: "T".into(), icon: String::new(), blocks: vec![block("todo", "a"), block("text", "b")], updated: 0 })
            .contains("- [ ] a\n\nb"));
        assert!(!root.join("Notas/Sin título.md").exists());

        let s2 = Notes::open(&root).unwrap();
        assert_eq!(s2.get(&n.id).unwrap().blocks.len(), 2);
        assert!(s.delete(&n.id).unwrap());
        assert!(!root.join("Notas/Cálculo.md").exists());
        assert!(s.update("../x", Update { title: None, icon: None, blocks: None }).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn keeps_unknown_block_fields() {
        let b: Block = serde_json::from_str(r#"{"id":"a","type":"tablet","page":"p1","label":"Física · Página 2"}"#).unwrap();
        let back = serde_json::to_value(&b).unwrap();
        assert_eq!(back["page"], "p1");
        let note = Note { id: "x".into(), title: "T".into(), icon: String::new(), blocks: vec![b], updated: 0 };
        assert!(markdown(&note).contains("Física · Página 2"));
    }
}
