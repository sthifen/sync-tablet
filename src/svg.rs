//! Página → SVG, para abrir los apuntes en cualquier programa (visor de
//! imágenes, Obsidian, el navegador) sin Sync Tablet.
//!
//! Los lápices varían con la presión: cada tramo entre dos puntos es una
//! línea con su propio grosor (y opacidad, el lápiz) y extremos redondeados,
//! así los tramos se funden sin costuras. Los tramos iguales van en el mismo
//! path. Las fórmulas son las mismas que en la tablet (Ink.java) y en el
//! visor web.

use std::fmt::Write;

use crate::ink::{Background, Item, PAGE_H, PAGE_W, Page, Stroke, Tool};

/// Grosor de un tramo según la herramienta, la presión `p` y la dirección
/// `(dx, dy)` del tramo (sólo la caligrafía la usa).
pub fn segment_width(tool: Tool, width: f32, p: f32, dx: f32, dy: f32) -> f32 {
    let p = p.clamp(0.0, 1.0);
    match tool {
        Tool::Pen => width * (0.35 + 0.65 * p),
        Tool::Pencil => width * 0.7 * (0.5 + 0.5 * p),
        Tool::Brush => width * (0.1 + 1.7 * p),
        Tool::Calligraphy => {
            // Punta plana a 45°: fina en una diagonal, gruesa en la otra.
            let a = dy.atan2(dx) - std::f32::consts::FRAC_PI_4;
            width * (0.25 + 0.95 * a.sin().abs()) * (0.6 + 0.4 * p)
        }
        Tool::Highlighter | Tool::Tape => width,
    }
}

/// Opacidad de un tramo (el lápiz es más claro si se aprieta poco).
pub fn segment_opacity(tool: Tool, p: f32) -> f32 {
    match tool {
        Tool::Pencil => 0.75 * (0.4 + 0.6 * p.clamp(0.0, 1.0)),
        _ => 1.0,
    }
}

/// `assets`: ruta delante de `<id>.jpg` para las imágenes (relativa al SVG
/// en disco, o la URL del servidor para el visor).
pub fn page_svg(page: &Page, assets: &str) -> String {
    let mut s = String::new();
    let _ = write!(
        s,
        r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {PAGE_W} {PAGE_H}" width="{PAGE_W}" height="{PAGE_H}"><rect width="{PAGE_W}" height="{PAGE_H}" fill="#FFFFFF"/>"##
    );
    match &page.bg_asset {
        Some(a) if valid_asset(a) => {
            let _ = write!(
                s,
                r#"<image href="{assets}{a}.jpg" x="0" y="0" width="{PAGE_W}" height="{PAGE_H}" preserveAspectRatio="xMidYMid meet"/>"#
            );
        }
        _ => background(&mut s, page.background),
    }
    for item in &page.items {
        item_svg(&mut s, item, assets);
    }
    for stroke in &page.strokes {
        stroke_svg(&mut s, stroke);
    }
    s.push_str("</svg>");
    s
}

fn valid_asset(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn escape(t: &str) -> String {
    t.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn line(s: &mut String, x1: f32, y1: f32, x2: f32, y2: f32) {
    let _ = write!(s, r#"<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>"#);
}

/// Plantillas de hoja (mismas medidas que en la tablet).
fn background(s: &mut String, bg: Background) {
    if bg == Background::Plain {
        return;
    }
    s.push_str(r##"<g stroke="#C9D3E3" stroke-width="1" fill="none">"##);
    match bg {
        Background::Plain => {}
        Background::Lines | Background::Grid | Background::Cornell => {
            let mut y = 80.0;
            while y < PAGE_H {
                line(s, 0.0, y, PAGE_W, y);
                y += 40.0;
            }
            if bg == Background::Grid {
                let mut x = 40.0;
                while x < PAGE_W {
                    line(s, x, 0.0, x, PAGE_H);
                    x += 40.0;
                }
            }
        }
        Background::Dots => {}
        Background::Music => {
            let mut top = 120.0;
            while top + 48.0 < PAGE_H - 40.0 {
                for k in 0..5 {
                    let y = top + 12.0 * k as f32;
                    line(s, 60.0, y, PAGE_W - 60.0, y);
                }
                top += 110.0;
            }
        }
        Background::Todo => {
            let mut y = 100.0;
            while y < PAGE_H - 40.0 {
                line(s, 90.0, y, PAGE_W - 40.0, y);
                let _ = write!(s, r#"<rect x="40" y="{}" width="26" height="26" rx="5"/>"#, y - 32.0);
                y += 64.0;
            }
        }
    }
    s.push_str("</g>");
    if bg == Background::Dots {
        s.push_str(r##"<g fill="#B8C4D6">"##);
        let mut y = 40.0;
        while y < PAGE_H {
            let mut x = 40.0;
            while x < PAGE_W {
                let _ = write!(s, r#"<circle cx="{x}" cy="{y}" r="2"/>"#);
                x += 40.0;
            }
            y += 40.0;
        }
        s.push_str("</g>");
    }
    if bg == Background::Cornell {
        let bottom = PAGE_H - 280.0;
        s.push_str(r##"<g stroke="#E28B8B" stroke-width="2.5">"##);
        line(s, 250.0, 40.0, 250.0, bottom);
        line(s, 0.0, bottom, PAGE_W, bottom);
        s.push_str("</g>");
    }
}

/// Partir el texto en líneas que quepan en `w` (estimando el ancho de letra).
pub fn wrap(text: &str, w: f32, size: f32) -> Vec<String> {
    let per_line = ((w / (size * 0.47)).floor() as usize).max(1);
    let mut out = Vec::new();
    for para in text.split('\n') {
        let mut cur = String::new();
        for word in para.split(' ') {
            if !cur.is_empty() && cur.chars().count() + 1 + word.chars().count() > per_line {
                out.push(std::mem::take(&mut cur));
            }
            if !cur.is_empty() {
                cur.push(' ');
            }
            cur.push_str(word);
        }
        out.push(cur);
    }
    out
}

fn item_svg(s: &mut String, item: &Item, assets: &str) {
    match item {
        Item::Image { asset, x, y, w, h, .. } if valid_asset(asset) => {
            let _ = write!(
                s,
                r#"<image href="{assets}{asset}.jpg" x="{x:.1}" y="{y:.1}" width="{w:.1}" height="{h:.1}" preserveAspectRatio="none"/>"#
            );
        }
        Item::Image { .. } => {}
        Item::Table { x, y, w, h, rows, cols, values, .. } => {
            let (rows, cols) = ((*rows).clamp(1, 200), (*cols).clamp(1, 50));
            let (cw, ch) = (w / cols as f32, h / rows as f32);
            let _ = write!(s, r##"<g><rect x="{x:.1}" y="{y:.1}" width="{w:.1}" height="{:.1}" fill="#F1F5F9" stroke="none"/>"##, ch);
            s.push_str(r##"<g stroke="#94A3B8" stroke-width="1.2">"##);
            for r in 0..=rows {
                line(s, *x, y + r as f32 * ch, x + w, y + r as f32 * ch);
            }
            for c in 0..=cols {
                line(s, x + c as f32 * cw, *y, x + c as f32 * cw, y + h);
            }
            s.push_str("</g>");
            let size = (ch * 0.5).clamp(10.0, 32.0);
            for r in 0..rows {
                for c in 0..cols {
                    let Some(v) = values.get(r * cols + c).filter(|v| !v.is_empty()) else { continue };
                    let weight = if r == 0 { r#" font-weight="bold""# } else { "" };
                    let _ = write!(
                        s,
                        r##"<text x="{:.1}" y="{:.1}" font-size="{size:.1}" font-family="sans-serif" fill="#111827"{weight}>{}</text>"##,
                        x + c as f32 * cw + 6.0,
                        y + r as f32 * ch + ch * 0.68,
                        escape(&v.chars().take(((cw - 8.0) / (size * 0.55)).max(1.0) as usize).collect::<String>())
                    );
                }
            }
            s.push_str("</g>");
        }
        Item::Text { text, x, y, w, size, color: col, .. } => {
            let _ = write!(
                s,
                r#"<text x="{x:.1}" y="{:.1}" font-size="{size:.1}" font-family="sans-serif" fill="{}">"#,
                y + size,
                color(col)
            );
            for (i, l) in wrap(text, *w, *size).iter().enumerate() {
                let dy = if i == 0 { 0.0 } else { size * 1.3 };
                let _ = write!(s, r#"<tspan x="{x:.1}" dy="{dy:.1}">{}</tspan>"#, escape(l));
            }
            s.push_str("</text>");
        }
    }
}

fn color(c: &str) -> &str {
    // Sólo `#RRGGBB`: lo demás no entra en el SVG.
    if c.len() == 7 && c.starts_with('#') && c[1..].chars().all(|ch| ch.is_ascii_hexdigit()) { c } else { "#000000" }
}

fn stroke_svg(s: &mut String, stroke: &Stroke) {
    let n = stroke.point_count();
    if n == 0 {
        return;
    }
    let col = color(&stroke.color);
    if matches!(stroke.tool, Tool::Highlighter | Tool::Tape) {
        let opacity = if stroke.tool == Tool::Tape { 0.85 } else { 0.35 };
        let _ = write!(
            s,
            r#"<path fill="none" stroke="{col}" stroke-opacity="{opacity}" stroke-width="{:.1}" stroke-linecap="round" stroke-linejoin="round" d=""#,
            stroke.width
        );
        for i in 0..n {
            let (x, y, _) = stroke.point(i);
            let _ = write!(s, "{}{x:.1} {y:.1}", if i == 0 { "M" } else { "L" });
        }
        if n == 1 {
            let (x, y, _) = stroke.point(0);
            let _ = write!(s, "L{x:.1} {y:.1}");
        }
        s.push_str(r#""/>"#);
        return;
    }
    // Lápices: tramos agrupados por grosor (a 0,1) y opacidad (a 0,05).
    let mut groups: Vec<((i32, i32), String)> = Vec::new();
    let mut seg = |w: f32, o: f32, x0: f32, y0: f32, x1: f32, y1: f32| {
        let key = ((w * 10.0).round() as i32, (o * 20.0).round() as i32);
        let d = match groups.iter_mut().find(|(k, _)| *k == key) {
            Some((_, d)) => d,
            None => {
                groups.push((key, String::new()));
                &mut groups.last_mut().unwrap().1
            }
        };
        let _ = write!(d, "M{x0:.1} {y0:.1}L{x1:.1} {y1:.1}");
    };
    if n == 1 {
        let (x, y, p) = stroke.point(0);
        seg(segment_width(stroke.tool, stroke.width, p, 1.0, 0.0), segment_opacity(stroke.tool, p), x, y, x, y);
    }
    for i in 1..n {
        let (x0, y0, p0) = stroke.point(i - 1);
        let (x1, y1, p1) = stroke.point(i);
        // Presión negativa: ahí se levanta el lápiz (componentes de circuito).
        if p1 < 0.0 {
            continue;
        }
        let p = (p0.max(p1) + p1) / 2.0;
        seg(segment_width(stroke.tool, stroke.width, p, x1 - x0, y1 - y0), segment_opacity(stroke.tool, p), x0, y0, x1, y1);
    }
    for ((wk, ok), d) in groups {
        let opacity = if ok == 20 { String::new() } else { format!(r#" stroke-opacity="{:.2}""#, ok as f32 / 20.0) };
        let _ = write!(
            s,
            r#"<path fill="none" stroke="{col}"{opacity} stroke-width="{:.1}" stroke-linecap="round" d="{d}"/>"#,
            wk as f32 / 10.0
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page(strokes: Vec<Stroke>, background: Background) -> Page {
        Page { id: "p".into(), notebook: "n".into(), version: 1, background, strokes, items: vec![], bg_asset: None, bg_text: None, notes: vec![] }
    }

    #[test]
    fn un_salto_del_lapiz_no_dibuja_linea() {
        let st = Stroke { id: "c".into(), tool: Tool::Pen, color: "#000000".into(), width: 3.0, points: vec![0.0, 0.0, 0.7, 10.0, 0.0, 0.7, 50.0, 0.0, -1.0, 60.0, 0.0, 0.7], shape: None };
        let svg = page_svg(&page(vec![st], Background::Plain), "");
        assert!(svg.contains("M0.0 0.0L10.0 0.0") && svg.contains("M50.0 0.0L60.0 0.0"), "{svg}");
        assert!(!svg.contains("M10.0 0.0L50.0"), "{svg}");
    }

    #[test]
    fn pen_width_follows_pressure() {
        let st = Stroke {
            id: "a".into(),
            tool: Tool::Pen,
            color: "#112233".into(),
            width: 4.0,
            points: vec![0.0, 0.0, 1.0, 10.0, 0.0, 1.0, 20.0, 0.0, 0.0],
            shape: None,
        };
        let svg = page_svg(&page(vec![st], Background::Plain), "");
        // Presión 1 → 4.0; tramo con presión media 0.5 → 4·(0.35+0.325)=2.7.
        assert!(svg.contains(r##"stroke="#112233" stroke-width="4.0" stroke-linecap="round" d="M0.0 0.0L10.0 0.0""##), "{svg}");
        assert!(svg.contains(r#"stroke-width="2.7" stroke-linecap="round" d="M10.0 0.0L20.0 0.0""#), "{svg}");
    }

    #[test]
    fn calligraphy_and_pencil() {
        // Caligrafía: tramo en la diagonal de la punta (45°) fino; perpendicular, grueso.
        let thin = segment_width(Tool::Calligraphy, 10.0, 1.0, 1.0, 1.0);
        let thick = segment_width(Tool::Calligraphy, 10.0, 1.0, 1.0, -1.0);
        assert!(thin < 3.0 && thick > 11.0, "{thin} {thick}");
        // El lápiz se aclara con poca presión.
        assert!(segment_opacity(Tool::Pencil, 0.0) < segment_opacity(Tool::Pencil, 1.0));
        let st = Stroke { id: "l".into(), tool: Tool::Pencil, color: "#000000".into(), width: 4.0, points: vec![0.0, 0.0, 0.2, 5.0, 0.0, 0.2], shape: None };
        assert!(page_svg(&page(vec![st], Background::Plain), "").contains("stroke-opacity="));
    }

    #[test]
    fn highlighter_tape_and_backgrounds() {
        let st = Stroke { id: "h".into(), tool: Tool::Highlighter, color: "#FFD60A".into(), width: 20.0, points: vec![5.0, 5.0, 0.3], shape: None };
        let svg = page_svg(&page(vec![st], Background::Grid), "");
        assert!(svg.contains(r#"stroke-opacity="0.35" stroke-width="20.0""#));
        assert!(svg.contains("M5.0 5.0L5.0 5.0"));
        assert!(svg.contains(r#"<line x1="40" y1="0""#));
        assert!(!page_svg(&page(vec![], Background::Plain), "").contains("<line"));
        assert!(page_svg(&page(vec![], Background::Dots), "").contains("<circle"));
        assert!(page_svg(&page(vec![], Background::Todo), "").contains("<rect x=\"40\""));
        assert!(page_svg(&page(vec![], Background::Cornell), "").contains("#E28B8B"));
    }

    #[test]
    fn images_text_and_pdf_background() {
        let mut p = page(vec![], Background::Lines);
        p.bg_asset = Some("pdf1".into());
        p.items.push(Item::Image { id: "i".into(), asset: "foto".into(), x: 10.0, y: 20.0, w: 300.0, h: 200.0 });
        p.items.push(Item::Text { id: "t".into(), text: "Hola <mundo> & cía".into(), x: 50.0, y: 60.0, w: 600.0, size: 28.0, color: "#2563EB".into() });
        let svg = page_svg(&p, "../.data/assets/");
        assert!(svg.contains(r#"href="../.data/assets/pdf1.jpg""#));
        assert!(svg.contains(r#"href="../.data/assets/foto.jpg" x="10.0" y="20.0""#));
        assert!(svg.contains("Hola &lt;mundo&gt; &amp; cía"));
        // Con fondo de PDF no se dibuja la plantilla.
        assert!(!svg.contains("<line"));
        p.items.push(Item::Image { id: "x".into(), asset: "../../etc".into(), x: 0.0, y: 0.0, w: 1.0, h: 1.0 });
        assert!(!page_svg(&p, "").contains("etc"));
        p.items.push(Item::Table { id: "tb".into(), x: 100.0, y: 900.0, w: 400.0, h: 120.0, rows: 2, cols: 2,
            cells: vec!["Total".into(), "".into(), "=1+2".into(), "".into()], values: vec!["Total".into(), "".into(), "3".into(), "".into()] });
        let svg = page_svg(&p, "");
        assert!(svg.contains(r#"font-weight="bold">Total</text>"#));
        assert!(svg.contains(">3</text>") && !svg.contains("=1+2"));
    }

    #[test]
    fn wraps_text() {
        assert_eq!(wrap("uno dos tres cuatro", 100.0, 20.0), vec!["uno dos", "tres", "cuatro"]);
        assert_eq!(wrap("a\nb", 500.0, 20.0), vec!["a", "b"]);
    }

    #[test]
    fn rejects_odd_colors() {
        let st = Stroke { id: "x".into(), tool: Tool::Pen, color: "red\"/><script>".into(), width: 2.0, points: vec![1.0, 1.0, 1.0], shape: None };
        let svg = page_svg(&page(vec![st], Background::Plain), "");
        assert!(!svg.contains("script"));
        assert!(svg.contains(r##"stroke="#000000""##));
    }
}
