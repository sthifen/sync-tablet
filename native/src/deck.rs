//! PowerPoint con DeckCraft: dibujar cada diapositiva, sacar su texto y crear
//! un `.pptx` con las páginas del cuaderno como imágenes.

use std::sync::Once;

use deckcraft_fonts::FontDb;
use deckcraft_geom::{Size, Xfrm};
use deckcraft_model::defaults::{blank_presentation, layout_of_kind, new_slide};
use deckcraft_model::style::PictureFill;
use deckcraft_model::{LayoutType, Presentation, Shape, ShapeId, ShapeKind};
use deckcraft_render::{RenderOpts, render_slide};

/// Android no guarda sus fuentes donde DeckCraft las busca.
const ANDROID_FONTS: &[&str] = &["Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSans.ttf", "DroidSans-Bold.ttf"];

pub struct Deck {
    pres: Presentation,
}

pub struct Image {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

fn load_system_fonts() {
    static FONTS: Once = Once::new();
    FONTS.call_once(|| {
        for name in ANDROID_FONTS {
            if let Ok(bytes) = std::fs::read(format!("/system/fonts/{name}")) {
                FontDb::global().add_font(bytes);
            }
        }
    });
}

impl Deck {
    pub fn open(bytes: &[u8]) -> Option<Self> {
        let pres = deckcraft_pptx::import(bytes).ok()?;
        (!pres.slides.is_empty()).then_some(Self { pres })
    }

    pub fn slides(&self) -> usize {
        self.pres.slides.len()
    }

    pub fn render(&self, index: usize, width: f64) -> Option<Image> {
        load_system_fonts();
        let opts = RenderOpts { scale: width / self.pres.slide_size.width, ..Default::default() };
        let image = render_slide(&self.pres, index, &opts);
        (image.width > 0).then_some(Image { width: image.width, height: image.height, rgba: image.pixels })
    }

    /// Todo el texto de la diapositiva y, al final, las notas del orador.
    pub fn text(&self, index: usize) -> String {
        let Some(slide) = self.pres.slides.get(index) else { return String::new() };
        let mut lines = Vec::new();
        collect_text(&slide.shapes, &mut lines);
        let notes = slide.notes.text();
        if !notes.trim().is_empty() {
            lines.push(notes);
        }
        lines.join("\n")
    }
}

fn collect_text(shapes: &[Shape], out: &mut Vec<String>) {
    for shape in shapes {
        if let ShapeKind::Group { children, .. } = &shape.kind {
            collect_text(children, out);
        }
        if let Some(text) = shape.text.as_ref().map(|t| t.text()) {
            if !text.trim().is_empty() {
                out.push(text);
            }
        }
    }
}

/// Un `.pptx` con una imagen JPEG por diapositiva. `width` y `height` dan la
/// forma de la diapositiva; cada imagen la llena entera.
pub fn images_to_pptx(images: Vec<Vec<u8>>, width: f64, height: f64) -> Option<Vec<u8>> {
    // Lado largo de 960 puntos, como una diapositiva normal.
    let k = 960.0 / width.max(height);
    let size = Size::new(width * k, height * k);
    let mut pres = blank_presentation(size, Default::default(), false);
    pres.slides.clear();
    let layout = layout_of_kind(&pres, LayoutType::Blank)?;
    for (i, jpeg) in images.into_iter().enumerate() {
        let media = pres.add_media(&format!("pagina{}.jpg", i + 1), "image/jpeg", jpeg)?;
        let mut slide = new_slide(&mut pres, layout)?;
        let id = ShapeId(pres.alloc_id()?);
        slide.shapes.push(Shape {
            id,
            name: format!("Página {}", i + 1),
            xfrm: Some(Xfrm { x: 0.0, y: 0.0, w: size.width, h: size.height, ..Default::default() }),
            kind: ShapeKind::Picture { fill: PictureFill { media, ..Default::default() } },
            ..Default::default()
        });
        pres.slides.push(std::sync::Arc::new(slide));
    }
    deckcraft_pptx::export(&pres).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use deckcraft_model::text::TextBody;

    fn jpeg(r: u8, g: u8, b: u8) -> Vec<u8> {
        let img = image::RgbImage::from_pixel(160, 90, image::Rgb([r, g, b]));
        let mut out = std::io::Cursor::new(Vec::new());
        img.write_to(&mut out, image::ImageFormat::Jpeg).unwrap();
        out.into_inner()
    }

    fn pixel(img: &Image, x: u32, y: u32) -> [u8; 3] {
        let i = ((y * img.width + x) * 4) as usize;
        [img.rgba[i], img.rgba[i + 1], img.rgba[i + 2]]
    }

    #[test]
    fn las_paginas_van_a_powerpoint_y_se_ven() {
        let pptx = images_to_pptx(vec![jpeg(220, 30, 30), jpeg(30, 30, 220)], 16.0, 9.0).expect("se crea");
        assert_eq!(&pptx[..2], b"PK");
        let deck = Deck::open(&pptx).expect("se abre");
        assert_eq!(deck.slides(), 2);
        let first = deck.render(0, 640.0).expect("primera");
        assert_eq!(first.width, 640);
        assert!((first.height as i32 - 360).abs() <= 1, "alto {}", first.height);
        let [r, _, b] = pixel(&first, 320, 180);
        assert!(r > 180 && b < 80, "la primera es roja");
        let second = deck.render(1, 640.0).expect("segunda");
        let [r, _, b] = pixel(&second, 320, 180);
        assert!(b > 180 && r < 80, "la segunda es azul");
    }

    #[test]
    fn una_pagina_vertical_da_una_diapositiva_vertical() {
        let pptx = images_to_pptx(vec![jpeg(0, 200, 0)], 1000.0, 1414.0).unwrap();
        let deck = Deck::open(&pptx).unwrap();
        let img = deck.render(0, 500.0).unwrap();
        assert!(img.height > img.width);
    }

    #[test]
    fn saca_el_texto_y_las_notas() {
        let mut pres = blank_presentation(Size::new(960.0, 540.0), Default::default(), false);
        pres.slides.clear();
        let layout = layout_of_kind(&pres, LayoutType::Blank).unwrap();
        let mut slide = new_slide(&mut pres, layout).unwrap();
        let id = ShapeId(pres.alloc_id().unwrap());
        slide.shapes.push(Shape {
            id,
            name: "Texto".into(),
            xfrm: Some(Xfrm { x: 50.0, y: 50.0, w: 800.0, h: 200.0, ..Default::default() }),
            text: Some(TextBody::from_text("Energía solar\nSegunda línea ñ")),
            text_box: true,
            ..Default::default()
        });
        slide.notes = TextBody::from_text("Recordar la demo");
        pres.slides.push(std::sync::Arc::new(slide));
        let bytes = deckcraft_pptx::export(&pres).unwrap();
        let deck = Deck::open(&bytes).unwrap();
        let text = deck.text(0);
        assert!(text.contains("Energía solar") && text.contains("Segunda línea ñ"), "{text}");
        assert!(text.contains("Recordar la demo"), "{text}");
        let img = deck.render(0, 960.0).unwrap();
        assert!(img.rgba.chunks(4).any(|p| p[0] < 128), "el texto se dibuja");
    }

    #[test]
    fn lo_que_no_es_pptx_no_rompe() {
        assert!(Deck::open(b"esto no es un pptx").is_none());
        let deck = Deck::open(&images_to_pptx(vec![jpeg(1, 2, 3)], 4.0, 3.0).unwrap()).unwrap();
        assert!(deck.render(99, 100.0).is_none());
        assert_eq!(deck.text(99), "");
    }
}
