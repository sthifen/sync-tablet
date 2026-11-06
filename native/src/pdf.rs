//! Leer PDF con el motor de PdfCraft.

use std::sync::Arc;

use pdfcraft_render::{PageRenderer, RenderConfig, RenderRequest, RequestKind};

/// Escala de la pasada barata que sirve para medir la página.
const PROBE_SCALE: f32 = 0.25;

pub struct Doc {
    renderer: PageRenderer,
}

pub struct Image {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

impl Doc {
    pub fn open(bytes: Vec<u8>) -> Option<Self> {
        let renderer = PageRenderer::new(Arc::new(bytes), RenderConfig::default());
        (renderer.page_count() > 0).then_some(Self { renderer })
    }

    pub fn pages(&self) -> usize {
        self.renderer.page_count()
    }

    pub fn render(&mut self, page: usize, width: f32) -> Option<Image> {
        let probe = self.renderer.render(RenderRequest { page, scale: PROBE_SCALE, preview: true, ..Default::default() });
        if probe.error.is_some() || probe.width == 0 {
            return None;
        }
        let scale = width * PROBE_SCALE / probe.width as f32;
        let done = self.renderer.render(RenderRequest { page, scale, ..Default::default() });
        if done.error.is_some() {
            return None;
        }
        Some(Image { width: done.width, height: done.height, rgba: done.rgba.to_vec() })
    }

    pub fn text(&mut self, page: usize) -> Option<String> {
        let done = self.renderer.render(RenderRequest { page, kind: RequestKind::Text, ..Default::default() });
        Some(done.text?.plain_text())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un PDF mínimo de una página con «Hola mundo», con su tabla de referencias.
    fn sample() -> Vec<u8> {
        let content = "BT /F1 24 Tf 72 700 Td (Hola mundo) Tj ET";
        let objects = [
            "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>".to_string(),
            format!("<< /Length {} >>\nstream\n{content}\nendstream", content.len()),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        ];
        let mut out = b"%PDF-1.4\n".to_vec();
        let mut offsets = Vec::new();
        for (i, o) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.extend(format!("{} 0 obj\n{o}\nendobj\n", i + 1).bytes());
        }
        let xref = out.len();
        out.extend(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).bytes());
        for o in offsets {
            out.extend(format!("{o:010} 00000 n \n").bytes());
        }
        out.extend(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1).bytes());
        out
    }

    #[test]
    fn lee_dibuja_y_saca_el_texto() {
        let mut d = Doc::open(sample()).expect("se abre");
        assert_eq!(d.pages(), 1);
        let img = d.render(0, 1000.0).expect("se dibuja");
        assert!((img.width as i32 - 1000).abs() <= 8, "ancho {}", img.width);
        let ratio = img.height as f32 / img.width as f32;
        assert!((ratio - 792.0 / 612.0).abs() < 0.02, "proporción {ratio}");
        assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);
        assert!(img.rgba.chunks(4).any(|p| p[0] < 128), "hay tinta en la página");
        assert_eq!(d.text(0).expect("texto").trim(), "Hola mundo");
    }

    #[test]
    fn un_archivo_roto_no_abre_ni_rompe() {
        assert!(Doc::open(b"esto no es un pdf".to_vec()).is_none());
        let mut d = Doc::open(sample()).unwrap();
        assert!(d.render(99, 100.0).is_none());
    }
}
