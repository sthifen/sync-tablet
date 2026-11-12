package android.graphics.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF a partir de lo que se dibuja en cada página. Cada página se dibuja en
 * un lienzo vectorial (PdfCanvas): trazos, figuras e imágenes van tal cual al
 * PDF y el texto como curvas.
 */
public class PdfDocument {
    public static final class PageInfo {
        final int width, height, number;
        final Rect content;
        private PageInfo(int w, int h, int n, Rect c) { width = w; height = h; number = n; content = c; }
        public int getPageWidth() { return width; }
        public int getPageHeight() { return height; }
        public int getPageNumber() { return number; }
        public Rect getContentRect() { return content; }

        public static final class Builder {
            private final int w, h, n;
            private Rect content;
            public Builder(int w, int h, int n) {
                if (w <= 0 || h <= 0) throw new IllegalArgumentException("page size must be positive");
                this.w = w; this.h = h; this.n = n;
            }
            public Builder setContentRect(Rect r) { content = r; return this; }
            public PageInfo create() { return new PageInfo(w, h, n, content == null ? new Rect(0, 0, w, h) : content); }
        }
    }

    public final class Page {
        final PageInfo info;
        final PdfCanvas canvas;
        boolean finished;
        Page(PageInfo i) { info = i; canvas = new PdfCanvas(i.width, i.height); }
        public Canvas getCanvas() { return canvas; }
        public PageInfo getInfo() { return info; }
    }

    private final ArrayList<Page> pages = new ArrayList<>();
    private final ArrayList<PageInfo> infos = new ArrayList<>();
    private Page current;
    private boolean closed;

    public Page startPage(PageInfo info) {
        if (closed) throw new IllegalStateException("document is closed!");
        if (current != null) throw new IllegalStateException("Previous page not finished!");
        current = new Page(info);
        return current;
    }

    public void finishPage(Page page) {
        if (page != current) throw new IllegalStateException("invalid page");
        page.finished = true;
        page.canvas.finish();
        pages.add(page);
        infos.add(page.info);
        current = null;
    }

    public List<PageInfo> getPages() { return new ArrayList<>(infos); }

    public void writeTo(OutputStream out) throws IOException {
        if (current != null) throw new IllegalStateException("Current page not finished!");
        PdfWriter w = new PdfWriter();
        for (Page p : pages) p.canvas.writePage(w, p.info.width, p.info.height);
        w.finish(out);
    }

    public void close() { closed = true; pages.clear(); }
}
