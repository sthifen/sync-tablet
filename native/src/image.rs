//! Editar una foto con PhotoCraft: girar, recortar, ajustar y limpiar documentos.

use photocraft_codecs::{ChannelLayout, EncodeOptions, Format, Image};
use photocraft_compose::Buffer;
use photocraft_compose::adjust::apply;
use photocraft_doc::adjust::{Adjustment, LevelsChannel};
use photocraft_geom::Rect;

/// Más lado que esto no se abre: la memoria de la tablet no da para más.
const MAX_PIXELS: u64 = 16_000_000;

#[derive(Clone, Copy, Debug)]
pub struct Edit {
    /// −100 a 100.
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    /// Cuartos de vuelta en el sentido del reloj.
    pub quarter_turns: u8,
    /// Izquierda, arriba, derecha y abajo, de 0 a 1, ya girada la imagen.
    pub crop: [f32; 4],
    /// Foto de un papel o una pizarra: fondo blanco y tinta negra.
    pub document: bool,
}

impl Default for Edit {
    fn default() -> Self {
        Self { brightness: 0.0, contrast: 0.0, saturation: 0.0, quarter_turns: 0, crop: [0.0, 0.0, 1.0, 1.0], document: false }
    }
}

struct Rgba {
    width: usize,
    height: usize,
    data: Vec<u8>,
}

/// La imagen editada como JPEG. `None` si no se puede leer.
pub fn edit(bytes: &[u8], e: &Edit) -> Option<Vec<u8>> {
    let image = photocraft_codecs::decode(bytes).ok()?;
    if u64::from(image.width()) * u64::from(image.height()) > MAX_PIXELS {
        return None;
    }
    let source = Rgba { width: image.width() as usize, height: image.height() as usize, data: image.to_rgba8() };
    drop(image);
    let cut = crop(rotate(source, e.quarter_turns), e.crop)?;
    let mut buffer = to_buffer(&cut);
    let (width, height) = (cut.width as u32, cut.height as u32);
    drop(cut);
    if e.document {
        clean_document(&mut buffer);
    }
    if e.brightness != 0.0 || e.contrast != 0.0 {
        apply(&Adjustment::BrightnessContrast { brightness: e.brightness, contrast: e.contrast, legacy: false }, &mut buffer);
    }
    if e.saturation != 0.0 {
        apply(&Adjustment::Vibrance { vibrance: 0.0, saturation: e.saturation }, &mut buffer);
    }
    let rgb: Vec<u8> = buffer.px.iter().flat_map(|p| [to_u8(p[0]), to_u8(p[1]), to_u8(p[2])]).collect();
    let out = Image::from_u8(width, height, ChannelLayout::Rgb, rgb).ok()?;
    photocraft_codecs::encode(&out, Format::Jpeg, &EncodeOptions::default()).ok()
}

fn to_u8(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8
}

fn to_buffer(image: &Rgba) -> Buffer {
    let px = image.data.chunks_exact(4).map(|p| [f32::from(p[0]) / 255.0, f32::from(p[1]) / 255.0, f32::from(p[2]) / 255.0, 1.0]).collect();
    Buffer { rect: Rect { x0: 0, y0: 0, x1: image.width as i32, y1: image.height as i32 }, px }
}

fn rotate(src: Rgba, quarter_turns: u8) -> Rgba {
    let turns = quarter_turns % 4;
    if turns == 0 {
        return src;
    }
    let (w, h) = (src.width, src.height);
    let (nw, nh) = if turns == 2 { (w, h) } else { (h, w) };
    let mut data = vec![0; src.data.len()];
    for y in 0..h {
        for x in 0..w {
            let (nx, ny) = match turns {
                1 => (h - 1 - y, x),
                2 => (w - 1 - x, h - 1 - y),
                _ => (y, w - 1 - x),
            };
            let (from, to) = ((y * w + x) * 4, (ny * nw + nx) * 4);
            data[to..to + 4].copy_from_slice(&src.data[from..from + 4]);
        }
    }
    Rgba { width: nw, height: nh, data }
}

fn crop(src: Rgba, area: [f32; 4]) -> Option<Rgba> {
    if area == [0.0, 0.0, 1.0, 1.0] {
        return Some(src);
    }
    let edge = |v: f32, size: usize| ((v.clamp(0.0, 1.0) * size as f32).round() as usize).min(size);
    let (x0, y0, x1, y1) = (edge(area[0], src.width), edge(area[1], src.height), edge(area[2], src.width), edge(area[3], src.height));
    if x1 <= x0 || y1 <= y0 {
        return None;
    }
    let (width, height) = (x1 - x0, y1 - y0);
    let mut data = Vec::with_capacity(width * height * 4);
    for y in y0..y1 {
        let from = (y * src.width + x0) * 4;
        data.extend_from_slice(&src.data[from..from + width * 4]);
    }
    Some(Rgba { width, height, data })
}

/// Lleva a blanco el tono del papel y a negro lo bastante más oscuro que él.
fn clean_document(buffer: &mut Buffer) {
    let mut levels = [0u32; 256];
    for p in &buffer.px {
        levels[to_u8(0.3 * p[0] + 0.59 * p[1] + 0.11 * p[2]) as usize] += 1;
    }
    // El papel es lo más claro que ocupa bastante: el percentil 85 de la luz.
    let target = buffer.px.len() as u32 / 100 * 85;
    let mut seen = 0;
    let paper = levels.iter().position(|n| {
        seen += n;
        seen >= target
    });
    let paper = paper.unwrap_or(255).max(1) as f32 / 255.0;
    let master = LevelsChannel { in_black: paper * 0.45, in_white: paper * 0.92, ..Default::default() };
    apply(&Adjustment::Vibrance { vibrance: 0.0, saturation: -100.0 }, buffer);
    apply(
        &Adjustment::Levels { master, per_channel: Default::default(), space: Default::default(), black: Default::default() },
        buffer,
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    /// JPEG de `w`×`h`: cada píxel lo da `f`.
    fn jpeg(w: u32, h: u32, f: impl Fn(u32, u32) -> [u8; 3]) -> Vec<u8> {
        let mut rgb = Vec::new();
        for y in 0..h {
            for x in 0..w {
                rgb.extend(f(x, y));
            }
        }
        let image = Image::from_u8(w, h, ChannelLayout::Rgb, rgb).unwrap();
        photocraft_codecs::encode(&image, Format::Jpeg, &EncodeOptions::default()).unwrap()
    }

    fn read(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
        let i = photocraft_codecs::decode(bytes).unwrap();
        (i.width(), i.height(), i.to_rgba8())
    }

    fn at(img: &(u32, u32, Vec<u8>), x: u32, y: u32) -> [u8; 3] {
        let i = ((y * img.0 + x) * 4) as usize;
        [img.2[i], img.2[i + 1], img.2[i + 2]]
    }

    fn near(a: u8, b: u8) -> bool {
        a.abs_diff(b) < 24
    }

    /// Izquierda roja, derecha azul.
    fn halves() -> Vec<u8> {
        jpeg(64, 32, |x, _| if x < 32 { [220, 30, 30] } else { [30, 30, 220] })
    }

    #[test]
    fn sin_cambios_queda_igual() {
        let out = read(&edit(&halves(), &Edit::default()).unwrap());
        assert_eq!((out.0, out.1), (64, 32));
        assert!(at(&out, 8, 16)[0] > 180 && at(&out, 56, 16)[2] > 180);
    }

    #[test]
    fn girar_un_cuarto_pone_la_izquierda_arriba() {
        let out = read(&edit(&halves(), &Edit { quarter_turns: 1, ..Default::default() }).unwrap());
        assert_eq!((out.0, out.1), (32, 64));
        assert!(at(&out, 16, 8)[0] > 180, "lo rojo queda arriba");
        assert!(at(&out, 16, 56)[2] > 180, "lo azul queda abajo");
    }

    #[test]
    fn media_vuelta_y_tres_cuartos() {
        let out = read(&edit(&halves(), &Edit { quarter_turns: 2, ..Default::default() }).unwrap());
        assert!(at(&out, 8, 16)[2] > 180, "lo azul pasa a la izquierda");
        let out = read(&edit(&halves(), &Edit { quarter_turns: 3, ..Default::default() }).unwrap());
        assert_eq!((out.0, out.1), (32, 64));
        assert!(at(&out, 16, 8)[2] > 180, "lo azul queda arriba");
    }

    #[test]
    fn recorta_lo_pedido() {
        let out = read(&edit(&halves(), &Edit { crop: [0.5, 0.0, 1.0, 1.0], ..Default::default() }).unwrap());
        assert_eq!((out.0, out.1), (32, 32));
        assert!(at(&out, 16, 16)[2] > 180, "sólo queda lo azul");
    }

    #[test]
    fn un_recorte_vacio_falla_sin_romper() {
        assert!(edit(&halves(), &Edit { crop: [0.5, 0.5, 0.5, 0.5], ..Default::default() }).is_none());
    }

    #[test]
    fn brillo_contraste_y_color_mueven_lo_esperado() {
        let gray = jpeg(32, 32, |_, _| [100, 100, 100]);
        let lighter = read(&edit(&gray, &Edit { brightness: 60.0, ..Default::default() }).unwrap());
        assert!(at(&lighter, 16, 16)[0] > 120, "{:?}", at(&lighter, 16, 16));
        let darker = read(&edit(&gray, &Edit { brightness: -60.0, ..Default::default() }).unwrap());
        assert!(at(&darker, 16, 16)[0] < 80, "{:?}", at(&darker, 16, 16));
        let red = jpeg(32, 32, |_, _| [200, 60, 60]);
        let gray_out = read(&edit(&red, &Edit { saturation: -100.0, ..Default::default() }).unwrap());
        let [r, g, b] = at(&gray_out, 16, 16);
        assert!(near(r, g) && near(g, b), "sin color: {r} {g} {b}");
    }

    #[test]
    fn un_papel_gris_queda_blanco_con_tinta_negra() {
        // Hoja mal iluminada: papel a 170 con una raya de tinta a 60.
        let photo = jpeg(64, 64, |_, y| if (28..36).contains(&y) { [60, 60, 70] } else { [170, 165, 150] });
        let out = read(&edit(&photo, &Edit { document: true, ..Default::default() }).unwrap());
        let paper = at(&out, 32, 8);
        let ink = at(&out, 32, 32);
        assert!(paper.iter().all(|v| *v > 235), "papel {paper:?}");
        assert!(ink.iter().all(|v| *v < 40), "tinta {ink:?}");
    }

    #[test]
    fn lo_que_no_es_imagen_no_rompe() {
        assert!(edit(b"esto no es una foto", &Edit::default()).is_none());
    }
}
