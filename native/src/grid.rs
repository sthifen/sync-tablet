//! Hojas de cálculo con GridCraft: abrir y guardar `.xlsx`/`.csv`/`.ods`, ejecutar
//! cualquier comando del motor (fórmulas, formato, ordenar, gráficas…), y leer lo que
//! se ve en un trozo de la hoja para que Java lo pinte. También calcula las tablas
//! que se ponen en las páginas de los cuadernos.

use gridcraft_engine::{DocState, Session, Trust};
use gridcraft_engine::core::CellRef;
use gridcraft_model::{Color, HAlign, Workbook};
use serde_json::{Value, json};

pub struct Book {
    session: Session,
}

impl Book {
    pub fn new() -> Book {
        let mut session = Session::new();
        let mut wb = Workbook::new();
        if let Some(sh) = wb.sheet_mut(0) {
            sh.name = "Hoja1".into();
        }
        session.add_document(DocState::new(wb, None, "Hoja".into(), Trust::Local));
        Book { session }
    }

    pub fn open(name: &str, bytes: &[u8]) -> Option<Book> {
        let (wb, _warnings) = gridcraft_engine::io::open_bytes(name, bytes).ok()?;
        let mut session = Session::new();
        session.add_document(DocState::new(wb, None, name.into(), Trust::FromFile));
        Some(Book { session })
    }

    /// Un comando del motor con sus parámetros JSON; devuelve `{"ok":…}` o `{"error":"…"}`.
    pub fn exec(&mut self, id: &str, params: &str) -> String {
        let mut p: Value = serde_json::from_str(params).unwrap_or(json!({}));
        // Lo que se escribe a mano puede venir en español: =SUMA(A1;A2).
        if id == "cell.set" {
            if let Some(input) = p.get("input").and_then(Value::as_str).map(translate) {
                p["input"] = json!(input);
            }
        }
        match self.session.execute_headless(id, p) {
            Ok(v) => json!({ "ok": v }).to_string(),
            Err(e) => json!({ "error": e.to_string() }).to_string(),
        }
    }

    pub fn save(&self, kind: &str) -> Option<Vec<u8>> {
        let d = self.session.doc().ok()?;
        let path = if kind.is_empty() { "hoja.xlsx".to_string() } else { format!("hoja.{kind}") };
        gridcraft_engine::io::save_bytes(&d.wb, &path).ok()
    }

    /// Lo que se ve de `rows`×`cols` celdas desde (`row0`, `col0`) en la hoja activa.
    pub fn view(&self, row0: u32, col0: u32, rows: u32, cols: u32) -> Option<String> {
        let d = self.session.doc().ok()?;
        let wb = &d.wb;
        let si = wb.active_sheet();
        let sh = wb.sheet(si)?;
        let theme = &wb.theme;
        let hex = |c: &Color| c.resolve(theme).map(|[r, g, b]| format!("#{r:02X}{g:02X}{b:02X}"));
        let mut cells = Vec::new();
        for r in row0..row0.saturating_add(rows) {
            let mut line = Vec::new();
            for c in col0..col0.saturating_add(cols) {
                let at = CellRef { row: r, col: c };
                let text = gridcraft_engine::display::cell_text(wb, sh, at);
                let style = wb.styles.get(sh.style_id(at));
                let mut o = serde_json::Map::new();
                if !text.is_empty() {
                    o.insert("t".into(), json!(text));
                    let number = matches!(sh.value(at), gridcraft_engine::core::Value::Number(_));
                    let align = match style.align.h {
                        HAlign::Center | HAlign::CenterAcross => "c",
                        HAlign::Right => "r",
                        HAlign::Left => "l",
                        _ if number => "r",
                        _ => "l",
                    };
                    o.insert("a".into(), json!(align));
                }
                if style.font.bold {
                    o.insert("b".into(), json!(true));
                }
                if style.font.italic {
                    o.insert("i".into(), json!(true));
                }
                if let Some(c) = hex(&style.font.color) {
                    if c != "#000000" {
                        o.insert("fc".into(), json!(c));
                    }
                }
                if !matches!(style.fill.pattern, gridcraft_model::PatternType::None) {
                    if let Some(c) = hex(&style.fill.fg) {
                        o.insert("bg".into(), json!(c));
                    }
                }
                line.push(Value::Object(o));
            }
            cells.push(Value::Array(line));
        }
        let col_w: Vec<f32> = (col0..col0.saturating_add(cols)).map(|c| if sh.is_col_hidden(c) { 0.0 } else { sh.col_width(c) }).collect();
        let row_h: Vec<f32> = (row0..row0.saturating_add(rows)).map(|r| if sh.is_row_hidden(r) { 0.0 } else { sh.row_height(r) }).collect();
        let sheets: Vec<&str> = wb.sheets.iter().map(|s| s.name.as_str()).collect();
        let used = sh.used_range().map(|u| json!([u.end.row, u.end.col])).unwrap_or(json!([0, 0]));
        let sel = &d.selection;
        Some(
            json!({
                "sheets": sheets, "active": si, "cells": cells, "colW": col_w, "rowH": row_h, "used": used,
                "canUndo": !d.undo.is_empty(), "canRedo": !d.redo.is_empty(),
                "sel": [sel.active.row, sel.active.col],
            })
            .to_string(),
        )
    }

    /// Lo que se escribe para editar una celda (la fórmula si la tiene).
    pub fn edit_text(&self, row: u32, col: u32) -> Option<String> {
        let d = self.session.doc().ok()?;
        let sh = d.wb.sheet(d.wb.active_sheet())?;
        Some(gridcraft_engine::display::edit_text(&d.wb, sh, CellRef { row, col }))
    }

    /// Las gráficas de la hoja activa: dónde van y sus trazos (de gridcraft-chart) para pintarlas.
    pub fn charts(&self) -> Option<String> {
        let d = self.session.doc().ok()?;
        let wb = &d.wb;
        let si = wb.active_sheet();
        let sh = wb.sheet(si)?;
        let mut out = Vec::new();
        for chart in &sh.charts {
            let (w, h) = (chart.anchor.width.clamp(40.0, 4000.0), chart.anchor.height.clamp(40.0, 4000.0));
            let data = gridcraft_chart::resolve(wb, si, chart);
            let prims = gridcraft_chart::render(chart, &data, w, h, &gridcraft_chart::ApproxMeasure);
            let x = sh.col_left(chart.anchor.cell.col) + f64::from(chart.anchor.dx);
            let y = sh.row_top(chart.anchor.cell.row) + f64::from(chart.anchor.dy);
            out.push(json!({ "id": chart.id, "x": x, "y": y, "w": w, "h": h, "prims": prims }));
        }
        Some(Value::Array(out).to_string())
    }
}

impl Default for Book {
    fn default() -> Self {
        Book::new()
    }
}

/// Todas las funciones que conoce el motor, separadas por comas.
pub fn functions() -> String {
    let mut names: Vec<&str> = gridcraft_functions::all().iter().map(|f| f.name).collect();
    names.sort_unstable();
    names.join(",")
}

/// Nombres en español de las funciones más usadas → los del motor.
const SPANISH: &[(&str, &str)] = &[
    ("SUMA", "SUM"), ("PROMEDIO", "AVERAGE"), ("CONTAR", "COUNT"), ("CONTARA", "COUNTA"), ("SI", "IF"), ("Y", "AND"),
    ("O", "OR"), ("NO", "NOT"), ("REDONDEAR", "ROUND"), ("REDONDEAR.MAS", "ROUNDUP"), ("REDONDEAR.MENOS", "ROUNDDOWN"),
    ("RAIZ", "SQRT"), ("POTENCIA", "POWER"), ("BUSCARV", "VLOOKUP"), ("BUSCARH", "HLOOKUP"), ("BUSCARX", "XLOOKUP"),
    ("CONTAR.SI", "COUNTIF"), ("SUMAR.SI", "SUMIF"), ("CONTAR.SI.CONJUNTO", "COUNTIFS"), ("SUMAR.SI.CONJUNTO", "SUMIFS"),
    ("PROMEDIO.SI", "AVERAGEIF"), ("HOY", "TODAY"), ("AHORA", "NOW"), ("CONCATENAR", "CONCATENATE"), ("ENTERO", "INT"),
    ("RESIDUO", "MOD"), ("ALEATORIO", "RAND"), ("ALEATORIO.ENTRE", "RANDBETWEEN"), ("MEDIANA", "MEDIAN"), ("MODA", "MODE"),
    ("DESVEST", "STDEV"), ("DESVEST.M", "STDEV.S"), ("VAR.S", "VAR.S"), ("LARGO", "LEN"), ("IZQUIERDA", "LEFT"),
    ("DERECHA", "RIGHT"), ("EXTRAE", "MID"), ("MAYUSC", "UPPER"), ("MINUSC", "LOWER"), ("TEXTO", "TEXT"), ("VALOR", "VALUE"),
    ("INDICE", "INDEX"), ("COINCIDIR", "MATCH"), ("SI.ERROR", "IFERROR"), ("PRODUCTO", "PRODUCT"), ("GRADOS", "DEGREES"),
    ("RADIANES", "RADIANS"), ("SENO", "SIN"), ("COSENO", "COS"), ("TANGENTE", "TAN"), ("LOG10", "LOG10"), ("EXP", "EXP"),
    ("FECHA", "DATE"), ("AÑO", "YEAR"), ("MES", "MONTH"), ("DIA", "DAY"), ("PENDIENTE", "SLOPE"), ("INTERSECCION.EJE", "INTERCEPT"),
    ("COEF.DE.CORREL", "CORREL"), ("PAGO", "PMT"), ("VNA", "NPV"), ("TIR", "IRR"), ("TRANSPONER", "TRANSPOSE"),
    ("FILTRAR", "FILTER"), ("ORDENAR", "SORT"), ("UNICOS", "UNIQUE"), ("SECUENCIA", "SEQUENCE"), ("ABS", "ABS"), ("PI", "PI"),
];

/// Una fórmula escrita «a la española» (`=SUMA(A1;A2)`, `3,5`) en la forma del motor.
pub fn translate(formula: &str) -> String {
    if !formula.starts_with('=') {
        return formula.to_string();
    }
    let chars: Vec<char> = formula.chars().collect();
    let mut out = String::with_capacity(formula.len());
    let mut i = 0;
    let mut in_text = false;
    while i < chars.len() {
        let c = chars[i];
        if c == '"' {
            in_text = !in_text;
            out.push(c);
            i += 1;
            continue;
        }
        if in_text {
            out.push(c);
            i += 1;
            continue;
        }
        if c.is_alphabetic() {
            let start = i;
            while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '.' || chars[i] == '_') {
                i += 1;
            }
            let word: String = chars[start..i].iter().collect();
            let upper = word.to_uppercase();
            if i < chars.len() && chars[i] == '(' {
                if let Some((_, en)) = SPANISH.iter().find(|(es, _)| *es == upper) {
                    out.push_str(en);
                    continue;
                }
            }
            out.push_str(&word);
            continue;
        }
        if c == ';' {
            out.push(',');
        } else if c == ',' && decimal_comma(&chars, i) {
            out.push('.');
        } else {
            out.push(c);
        }
        i += 1;
    }
    out
}

/// Una coma entre cifras de un número suelto (no tras una celda como B1) es decimal,
/// salvo que la fórmula ya use `;` para separar.
fn decimal_comma(chars: &[char], i: usize) -> bool {
    if !chars.contains(&';') || i == 0 || i + 1 >= chars.len() || !chars[i + 1].is_ascii_digit() || !chars[i - 1].is_ascii_digit() {
        return false;
    }
    let mut k = i;
    while k > 0 && chars[k - 1].is_ascii_digit() {
        k -= 1;
    }
    !(k > 0 && (chars[k - 1].is_alphabetic() || chars[k - 1] == '$'))
}

/// Calcula una tabla de una página: lo escrito por filas → lo que se ve, por filas.
pub fn eval_table(cells: &[String], rows: u32, cols: u32) -> Vec<String> {
    let mut book = Book::new();
    let mut values = Vec::new();
    for r in 0..rows {
        let mut line = Vec::new();
        for c in 0..cols {
            let raw = cells.get((r * cols + c) as usize).map(|s| s.trim()).unwrap_or("");
            line.push(json!(translate(raw)));
        }
        values.push(Value::Array(line));
    }
    let _ = book.session.execute_headless("range.setValues", json!({ "range": "A1", "values": values }));
    let mut out = Vec::new();
    let Ok(d) = book.session.doc() else { return out };
    let Some(sh) = d.wb.sheet(0) else { return out };
    for r in 0..rows {
        for c in 0..cols {
            out.push(gridcraft_engine::display::cell_text(&d.wb, sh, CellRef { row: r, col: c }));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spanish_formulas() {
        assert_eq!(translate("=SUMA(A1;A2)*2,5"), "=SUM(A1,A2)*2.5");
        assert_eq!(translate("=BUSCARV(2,A1:B1,2,FALSE)"), "=VLOOKUP(2,A1:B1,2,FALSE)");
        assert_eq!(translate("=SI(A1>3;\"sí; claro\";\"no\")"), "=IF(A1>3,\"sí; claro\",\"no\")");
    }

    #[test]
    fn tables_use_gridcraft() {
        let cells: Vec<String> = ["2", "3", "=SUMA(A1:B1)", "=PROMEDIO(A1:B1)", "=IF(A1>1,\"grande\",\"chico\")", "=VLOOKUP(2,A1:B1,2,FALSE)"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        let v = eval_table(&cells, 2, 3);
        assert_eq!(v, vec!["2", "3", "5", "2.5", "grande", "3"]);
    }

    #[test]
    fn book_roundtrip_and_chart() {
        let mut b = Book::new();
        assert!(b.exec("range.setValues", r#"{"range":"A1","values":[["Mes","Ventas"],["Ene",10],["Feb",20],["Mar",15]]}"#).contains("ok"));
        assert!(b.exec("selection.set", r#"{"range":"A1:B4"}"#).contains("ok"));
        let r = b.exec("insert.chart", r#"{"type":"column","title":"Ventas","at":"D2","width":400,"height":250}"#);
        assert!(r.contains("ok"), "{r}");
        let charts = b.charts().unwrap_or_default();
        assert!(charts.contains("Rect"), "{charts}");
        let bytes = b.save("xlsx").unwrap_or_default();
        let again = Book::open("x.xlsx", &bytes).map(|b| b.view(0, 0, 4, 2).unwrap_or_default()).unwrap_or_default();
        assert!(again.contains("Ventas"), "{again}");
        assert!(functions().split(',').count() > 300);
    }
}
