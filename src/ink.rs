//! Modelo de los apuntes: cuadernos, páginas y trazos, y las operaciones que
//! manda la tablet. El mismo formato JSON que `android/.../Ink.java`.
//!
//! La tablet es la dueña de los datos: cada cambio sube la versión de la
//! página en uno, y el PC sólo aplica una operación si viene justo detrás
//! de la versión que tiene. Si se saltó alguna, pide la página entera.

use serde::{Deserialize, Serialize};

/// Medidas lógicas de una página (proporción A4).
pub const PAGE_W: f32 = 1000.0;
pub const PAGE_H: f32 = 1414.0;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Notebook {
    pub id: String,
    pub title: String,
    /// Color de la tapa, `#RRGGBB`.
    #[serde(default = "default_cover")]
    pub color: String,
    /// Páginas en orden.
    #[serde(default)]
    pub pages: Vec<String>,
    /// Carpeta en la portada de la tablet ("" = ninguna).
    #[serde(default)]
    pub folder: String,
    #[serde(default)]
    pub favorite: bool,
    /// Última edición en la tablet (ms desde 1970).
    #[serde(default)]
    pub updated: u64,
    /// Páginas que en la tablet van a la derecha de la anterior (no debajo).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub right: Vec<String>,
}

fn default_cover() -> String {
    "#2DD4BF".into()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Background {
    #[default]
    Plain,
    Lines,
    Grid,
    Dots,
    /// Método Cornell: columna de ideas a la izquierda y resumen abajo.
    Cornell,
    /// Pentagramas.
    Music,
    /// Lista de tareas con casillas.
    Todo,
    /// Partida a la mitad: dos columnas de rayas.
    Half,
    HalfGrid,
    /// Partida en dos, arriba y abajo.
    HalfH,
    /// Milimetrada.
    Mm,
    Iso,
    Engineering,
    Hex,
    Wide,
    Week,
    Story,
    Handwriting,
    /// Semilogarítmica: y en 4 décadas, x lineal.
    Semilog,
    /// Logarítmica en los dos ejes.
    Loglog,
    /// Carta de Smith (impedancia normalizada).
    Smith,
    /// Polar: círculos y radios cada 15°.
    Polar,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Tool {
    #[default]
    Pen,
    Pencil,
    Brush,
    /// Pluma de caligrafía: el grosor depende de la dirección del trazo.
    Calligraphy,
    Highlighter,
    /// Cinta para tapar (respuestas, apuntes): en la tablet se destapa con un toque.
    Tape,
    /// Grosor parejo, sin presión.
    Marker,
    /// Estilográfica: engorda al apretar.
    Fountain,
    /// Punteado: puntos redondos, grosor parejo.
    Dotted,
    /// Rayado: rayas cortas, grosor parejo.
    Dashed,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Stroke {
    pub id: String,
    #[serde(default)]
    pub tool: Tool,
    pub color: String,
    /// Grosor con presión 1, en unidades de página.
    pub width: f32,
    /// x, y, presión (0–1), seguidos.
    pub points: Vec<f32>,
    /// Figura editable que dio estos puntos. Sólo la entiende la tablet; aquí se
    /// guarda tal cual, y los puntos bastan para dibujarla.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub shape: Option<serde_json::Value>,
}

impl Stroke {
    pub fn point_count(&self) -> usize {
        self.points.len() / 3
    }

    /// (x, y, presión) del punto `i`.
    pub fn point(&self, i: usize) -> (f32, f32, f32) {
        (self.points[3 * i], self.points[3 * i + 1], self.points[3 * i + 2])
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Page {
    pub id: String,
    pub notebook: String,
    #[serde(default)]
    pub version: u64,
    #[serde(default)]
    pub background: Background,
    #[serde(default)]
    pub strokes: Vec<Stroke>,
    /// Imágenes y cuadros de texto.
    #[serde(default)]
    pub items: Vec<Item>,
    /// Imagen de fondo de toda la hoja (una página de un PDF importado).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bg_asset: Option<String>,
    /// Texto de esa página del PDF, para copiarlo y buscarlo.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bg_text: Option<String>,
    /// Notas adhesivas de la app: nunca salen en una exportación.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub notes: Vec<serde_json::Value>,
    /// Marcas del lápiz neurona: como las notas, solo las usa la tablet y nunca se exportan.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub marks: Vec<serde_json::Value>,
}

/// Un elemento de la hoja que no es un trazo.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Item {
    Image { id: String, asset: String, x: f32, y: f32, w: f32, h: f32 },
    Text {
        id: String,
        text: String,
        x: f32,
        y: f32,
        w: f32,
        #[serde(default = "default_text_size")]
        size: f32,
        #[serde(default = "default_text_color")]
        color: String,
    },
    /// Hoja de cálculo: `cells` lo escrito (fórmulas incluidas), `values` lo
    /// que se ve (calculado en la tablet), por filas.
    Table {
        id: String,
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        rows: usize,
        cols: usize,
        #[serde(default)]
        cells: Vec<String>,
        #[serde(default)]
        values: Vec<String>,
    },
}

fn default_text_size() -> f32 {
    28.0
}

fn default_text_color() -> String {
    "#111827".into()
}

impl Page {
    /// Imágenes de las que hace falta el archivo.
    pub fn assets(&self) -> impl Iterator<Item = &str> {
        self.bg_asset.as_deref().into_iter().chain(self.items.iter().filter_map(|i| match i {
            Item::Image { asset, .. } => Some(asset.as_str()),
            Item::Text { .. } | Item::Table { .. } => None,
        }))
    }
}

/// Lo que llega de la tablet por `/ws/ink`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Msg {
    /// Al conectar: versión de cada página que tiene la tablet.
    Hello {
        #[serde(default)]
        device: String,
        #[serde(default)]
        pages: std::collections::HashMap<String, u64>,
    },
    Notebook { notebook: Notebook },
    /// Página completa (al sincronizar o al cambiar el fondo).
    Page { page: Page },
    Stroke { page: String, version: u64, stroke: Stroke },
    /// Archivo de una imagen (JPEG/PNG en base64), antes de usarla en una página.
    Asset { id: String, mime: String, data: String },
    Erase { page: String, version: u64, ids: Vec<String> },
    DeletePage { page: String },
    /// Puntero láser (no se guarda): sólo para el visor en vivo.
    Laser { page: String, points: Vec<f32> },
    DeleteNotebook { notebook: String },
    /// Un documento de la tablet (hoja de cálculo, Word…) para tenerlo también en el PC.
    File { kind: String, title: String, ext: String, data: String },
}

/// Qué pasó al aplicar una operación a una página.
#[derive(Debug, PartialEq, Eq)]
pub enum Applied {
    Ok,
    /// Ya la tenía (llegó repetida).
    Stale,
    /// Falta alguna anterior: hay que pedir la página entera.
    Gap,
}

impl Page {
    fn step(&mut self, version: u64) -> Option<Applied> {
        if version <= self.version {
            Some(Applied::Stale)
        } else if version > self.version + 1 {
            Some(Applied::Gap)
        } else {
            self.version = version;
            None
        }
    }

    pub fn add_stroke(&mut self, version: u64, stroke: Stroke) -> Applied {
        if let Some(r) = self.step(version) {
            return r;
        }
        // Al rehacer, la tablet vuelve a mandar un trazo borrado con su id.
        self.strokes.retain(|s| s.id != stroke.id);
        self.strokes.push(stroke);
        Applied::Ok
    }

    pub fn erase(&mut self, version: u64, ids: &[String]) -> Applied {
        if let Some(r) = self.step(version) {
            return r;
        }
        self.strokes.retain(|s| !ids.contains(&s.id));
        Applied::Ok
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn un_trazo_viejo_y_una_figura_se_leen_y_se_conservan() {
        let old: Stroke = serde_json::from_str(r##"{"id":"a","color":"#000","width":2,"points":[1,2,1]}"##).unwrap();
        assert!(old.shape.is_none());
        assert!(!serde_json::to_string(&old).unwrap().contains("shape"));
        let shaped = r##"{"id":"b","color":"#000","width":2,"points":[1,2,1],"shape":{"v":1,"kind":"arrow","c":[0,0,9,9],"a":0,"futuro":true}}"##;
        let s: Stroke = serde_json::from_str(shaped).unwrap();
        let again = serde_json::to_string(&s).unwrap();
        assert!(again.contains("\"kind\":\"arrow\"") && again.contains("futuro"), "{again}");
    }

    fn stroke(id: &str) -> Stroke {
        Stroke { id: id.into(), tool: Tool::Pen, color: "#000000".into(), width: 3.0, points: vec![0.0, 0.0, 0.5, 10.0, 10.0, 1.0], shape: None }
    }

    fn page() -> Page {
        Page { id: "p".into(), notebook: "n".into(), version: 0, background: Background::Plain, strokes: vec![], items: vec![], bg_asset: None, bg_text: None, notes: vec![], marks: vec![] }
    }

    #[test]
    fn applies_operations_in_order() {
        let mut p = page();
        assert_eq!(p.add_stroke(1, stroke("a")), Applied::Ok);
        assert_eq!(p.add_stroke(2, stroke("b")), Applied::Ok);
        assert_eq!(p.erase(3, &["a".into()]), Applied::Ok);
        assert_eq!(p.strokes.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(), ["b"]);
        assert_eq!(p.version, 3);
        // Rehacer el borrado: vuelve el mismo trazo, sin duplicarse.
        assert_eq!(p.add_stroke(4, stroke("a")), Applied::Ok);
        assert_eq!(p.add_stroke(5, stroke("a")), Applied::Ok);
        assert_eq!(p.strokes.len(), 2);
    }

    #[test]
    fn detects_repeats_and_gaps() {
        let mut p = page();
        p.add_stroke(1, stroke("a"));
        assert_eq!(p.add_stroke(1, stroke("x")), Applied::Stale);
        assert_eq!(p.add_stroke(3, stroke("y")), Applied::Gap);
        assert_eq!(p.version, 1);
        assert_eq!(p.strokes.len(), 1);
    }

    #[test]
    fn reads_tablet_messages() {
        let m: Msg = serde_json::from_str(
            r##"{"t":"stroke","page":"p1","version":7,"stroke":{"id":"s1","tool":"highlighter","color":"#ffd60a","width":12,"points":[1,2,0.5]}}"##,
        )
        .unwrap();
        let Msg::Stroke { page, version, stroke } = m else { panic!() };
        assert_eq!((page.as_str(), version, stroke.tool, stroke.point_count()), ("p1", 7, Tool::Highlighter, 1));
        let m: Msg = serde_json::from_str(r#"{"t":"hello","device":"S8+","pages":{"a":3}}"#).unwrap();
        assert!(matches!(m, Msg::Hello { pages, .. } if pages["a"] == 3));
    }
}
