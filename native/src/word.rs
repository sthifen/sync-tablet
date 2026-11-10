//! Word: un `.docx` pasa a bloques de nota y al revés, con WordCraft.
//! El puente es Markdown, que los dos lados entienden. Las notas no tienen
//! formato dentro del texto, así que negrita y demás se pierden al importar.

use serde_json::{Value, json};

/// Un `.docx` como nota: `{"title": …, "blocks": [{"type", "text", "checked"}]}`.
pub fn docx_to_note(bytes: &[u8]) -> Option<String> {
    let doc = wordcraft_docx::read(bytes).ok()?;
    let markdown = String::from_utf8(wordcraft_formats::export("md", &doc)?.ok()?).ok()?;
    Some(markdown_to_note(&markdown).to_string())
}

/// Una nota (el JSON de la app) como `.docx`.
pub fn note_to_docx(note: &str) -> Option<Vec<u8>> {
    let note: Value = serde_json::from_str(note).ok()?;
    let markdown = note_to_markdown(&note);
    let doc = wordcraft_formats::import("md", markdown.as_bytes())?.ok()?;
    wordcraft_docx::write(&doc).ok()
}

fn block(kind: &str, text: &str, checked: bool) -> Value {
    json!({ "type": kind, "text": text, "checked": checked })
}

fn markdown_to_note(markdown: &str) -> Value {
    let mut title = String::new();
    let mut blocks = Vec::new();
    for raw in markdown.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = heading(line, 1) {
            // El primer título de un documento es el de la nota.
            if title.is_empty() && blocks.is_empty() {
                title = plain(rest);
            } else {
                blocks.push(block("h1", &plain(rest), false));
            }
        } else if let Some(rest) = heading(line, 2) {
            blocks.push(block("h2", &plain(rest), false));
        } else if let Some(rest) = deep_heading(line) {
            blocks.push(block("h3", &plain(rest), false));
        } else if is_divider(line) {
            blocks.push(block("divider", "", false));
        } else if let Some(rest) = line.strip_prefix("- ").or(line.strip_prefix("* ")) {
            blocks.push(list_item(&plain(rest)));
        } else if let Some(rest) = numbered(line) {
            blocks.push(block("number", &plain(rest), false));
        } else if let Some(rest) = line.strip_prefix("> ") {
            blocks.push(block("quote", &plain(rest), false));
        } else if line.starts_with('|') {
            if let Some(row) = table_row(line) {
                blocks.push(block("text", &row, false));
            }
        } else {
            blocks.push(block("text", &plain(line), false));
        }
    }
    json!({ "title": title, "blocks": blocks })
}

/// Un elemento de lista; con casilla delante, una tarea.
fn list_item(text: &str) -> Value {
    for (mark, checked) in [("☐ ", false), ("[ ] ", false), ("☑ ", true), ("[x] ", true), ("[X] ", true)] {
        if let Some(rest) = text.strip_prefix(mark) {
            return block("todo", rest, checked);
        }
    }
    block("bullet", text, false)
}

fn heading(line: &str, level: usize) -> Option<&str> {
    let rest = line.strip_prefix(&"#".repeat(level))?;
    rest.strip_prefix(' ')
}

fn deep_heading(line: &str) -> Option<&str> {
    let level = line.bytes().take_while(|b| *b == b'#').count();
    (3..=6).contains(&level).then(|| line[level..].strip_prefix(' ')).flatten()
}

fn is_divider(line: &str) -> bool {
    line.len() >= 3 && (line.bytes().all(|b| b == b'-') || line.bytes().all(|b| b == b'*'))
}

fn numbered(line: &str) -> Option<&str> {
    let digits = line.bytes().take_while(u8::is_ascii_digit).count();
    if digits == 0 {
        return None;
    }
    line[digits..].strip_prefix(". ")
}

/// Una fila de tabla, con sus celdas separadas; `None` si es la línea de guiones.
fn table_row(line: &str) -> Option<String> {
    let cells: Vec<&str> = line.trim_matches('|').split('|').map(str::trim).collect();
    if cells.iter().all(|c| !c.is_empty() && c.bytes().all(|b| matches!(b, b'-' | b':'))) {
        return None;
    }
    Some(cells.iter().map(|c| plain(c)).collect::<Vec<_>>().join(" · "))
}

/// Quita el formato de Markdown y deja el texto.
fn plain(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' => out.extend(chars.next()),
            '*' | '`' => {}
            // `_` sólo marca formato al borde de una palabra; en precio_unitario, no.
            '_' => {
                let inside = out.chars().last().is_some_and(char::is_alphanumeric)
                    && chars.peek().is_some_and(|n| n.is_alphanumeric());
                if inside {
                    out.push('_');
                }
            }
            '!' if chars.peek() == Some(&'[') => {
                // Una imagen: las notas aún no las tienen.
                skip_link(&mut chars);
            }
            '[' => {
                let mut label = String::new();
                let mut closed = false;
                for n in chars.by_ref() {
                    if n == ']' {
                        closed = true;
                        break;
                    }
                    label.push(n);
                }
                if !closed {
                    out.push('[');
                }
                out.push_str(&plain(&label));
                if closed && chars.peek() == Some(&'(') {
                    for n in chars.by_ref() {
                        if n == ')' {
                            break;
                        }
                    }
                }
            }
            _ => out.push(c),
        }
    }
    out
}

fn skip_link(chars: &mut std::iter::Peekable<std::str::Chars>) {
    for n in chars.by_ref() {
        if n == ']' {
            break;
        }
    }
    if chars.peek() == Some(&'(') {
        for n in chars.by_ref() {
            if n == ')' {
                break;
            }
        }
    }
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if matches!(c, '\\' | '*' | '_' | '`' | '[' | ']') {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

fn note_to_markdown(note: &Value) -> String {
    let mut out = String::new();
    let title = note["title"].as_str().unwrap_or("").trim();
    if !title.is_empty() {
        out.push_str(&format!("# {}\n\n", escape(title)));
    }
    let mut number = 0;
    for b in note["blocks"].as_array().map(Vec::as_slice).unwrap_or(&[]) {
        let kind = b["type"].as_str().unwrap_or("text");
        let text = escape(b["text"].as_str().unwrap_or(""));
        number = if kind == "number" { number + 1 } else { 0 };
        let line = match kind {
            "h1" => format!("# {text}"),
            "h2" => format!("## {text}"),
            "h3" => format!("### {text}"),
            "bullet" => format!("- {text}"),
            "number" => format!("{number}. {text}"),
            "todo" => format!("- {} {text}", if b["checked"].as_bool().unwrap_or(false) { "☑" } else { "☐" }),
            "quote" | "callout" => format!("> {}", text.replace('\n', "\n> ")),
            "divider" => "---".into(),
            "tablet" => format!("✏️ {}", escape(b["label"].as_str().unwrap_or("Página de la tablet"))),
            _ => text,
        };
        if line.is_empty() {
            continue;
        }
        out.push_str(&line);
        out.push_str("\n\n");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(note: &Value) -> Vec<(String, String)> {
        note["blocks"]
            .as_array()
            .unwrap()
            .iter()
            .map(|b| (b["type"].as_str().unwrap().to_string(), b["text"].as_str().unwrap().to_string()))
            .collect()
    }

    fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect()
    }

    #[test]
    fn un_docx_real_se_vuelve_nota() {
        let json = docx_to_note(include_bytes!("../tests/fixtures/informe.docx")).unwrap();
        let note: Value = serde_json::from_str(&json).unwrap();
        assert_eq!(note["title"], "Informe de laboratorio");
        assert_eq!(
            kinds(&note),
            pairs(&[
                ("text", "Este informe tiene negrita y cursiva; precio_unitario = 2*3."),
                ("h2", "Materiales"),
                ("bullet", "Multímetro"),
                ("bullet", "Panel solar"),
                ("h3", "Pasos"),
                ("number", "Medir tensión"),
                ("number", "Anotar corriente"),
                ("quote", "Una cita importante."),
                ("text", "Hora · Potencia"),
                ("text", "10:00 · 120 W"),
                ("text", "Párrafo final con ñ y tildes."),
            ])
        );
    }

    #[test]
    fn una_nota_va_a_word_y_vuelve_igual() {
        let note = json!({
            "title": "Mi nota",
            "blocks": [
                block("h1", "Sección", false),
                block("text", "Con *asteriscos*, guion_bajo y [corchetes].", false),
                block("bullet", "Uno", false),
                block("number", "Primero", false),
                block("number", "Segundo", false),
                block("todo", "Pendiente", false),
                block("todo", "Hecho", true),
                block("quote", "Una cita", false),
                block("divider", "", false),
                block("h3", "Final ñ", false),
            ]
        });
        let docx = note_to_docx(&note.to_string()).unwrap();
        assert_eq!(&docx[..2], b"PK", "es un zip");
        let back: Value = serde_json::from_str(&docx_to_note(&docx).unwrap()).unwrap();
        assert_eq!(back["title"], "Mi nota");
        let got = kinds(&back);
        for want in note["blocks"].as_array().unwrap() {
            let w = (want["type"].as_str().unwrap().to_string(), want["text"].as_str().unwrap().to_string());
            assert!(got.contains(&w), "falta {w:?} en {got:#?}");
        }
    }

    #[test]
    fn lo_que_no_es_docx_no_rompe() {
        assert!(docx_to_note(b"esto no es un word").is_none());
        assert!(note_to_docx("no es json").is_none());
    }

    #[test]
    fn quita_el_formato() {
        assert_eq!(plain("**negrita** y *cursiva*"), "negrita y cursiva");
        assert_eq!(plain("un [enlace](https://a.b/c_d) aquí"), "un enlace aquí");
        assert_eq!(plain(r"precio\_unitario 2\*3"), "precio_unitario 2*3");
        assert_eq!(plain("__fuerte__ y snake_case"), "fuerte y snake_case");
        assert_eq!(plain("antes ![foto](a.png) después"), "antes  después");
    }
}

/// HTML (el del editor de documentos de la tablet) como `.docx`.
pub fn html_to_docx(html: &str) -> Option<Vec<u8>> {
    // Letra de documento moderno en vez de la Times de serie.
    let html = format!("<html><body style=\"font-family:Calibri,Arial,sans-serif\">{html}</body></html>");
    let doc = wordcraft_formats::import("html", html.as_bytes())?.ok()?;
    wordcraft_docx::write(&doc).ok()
}

/// Un `.docx` como HTML para el editor de documentos.
pub fn docx_to_html(bytes: &[u8]) -> Option<String> {
    let doc = wordcraft_docx::read(bytes).ok()?;
    String::from_utf8(wordcraft_formats::export("html", &doc)?.ok()?).ok()
}

#[cfg(test)]
mod html_tests {
    #[test]
    fn ida_y_vuelta_con_formato() {
        let html = r#"<p dir="ltr" style="text-align:center;"><span style="font-size:1.80em;"><b>Informe</b></span></p>
<p dir="ltr">Texto con <b>negrita</b>, <i>cursiva</i>, <u>subrayado</u> y <span style="color:#DC2626;">rojo</span>.</p>
<ul><li dir="ltr">uno</li><li dir="ltr">dos</li></ul>"#;
        let docx = super::html_to_docx(html).expect("docx");
        let back = super::docx_to_html(&docx).expect("html");
        println!("{back}");
        assert!(back.contains("negrita"));
        assert!(back.contains("<b>") || back.contains("<strong>") || back.contains("bold"), "{back}");
    }
}

#[cfg(test)]
mod archivos_reales {
    /// SUITE_DIR=carpeta cargo test archivos_reales -- --ignored --nocapture
    #[test]
    #[ignore]
    fn abre_los_word_de_la_carpeta() {
        let Ok(dir) = std::env::var("SUITE_DIR") else { return };
        for e in std::fs::read_dir(dir).unwrap().flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) != Some("docx") {
                continue;
            }
            let bytes = std::fs::read(&p).unwrap();
            let html = super::docx_to_html(&bytes).unwrap_or_default();
            let back = super::html_to_docx(&html).map(|d| d.len()).unwrap_or(0);
            if let Ok(out) = std::env::var("SUITE_OUT") {
                let _ = std::fs::write(format!("{out}/{}.html", p.file_stem().unwrap().to_string_lossy()), &html);
            }
            println!("{:60} html {:>7}  tablas {:>2}  imágenes {:>2}  vuelta {:>7}", p.file_name().unwrap().to_string_lossy(), html.len(),
                html.matches("<table").count(), html.matches("<img").count(), back);
        }
    }
}
