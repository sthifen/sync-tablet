package android.graphics.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.Deflater;

/** Escribe los objetos de un PDF 1.4 y su tabla de referencias. */
final class PdfWriter {
    private final ArrayList<byte[]> objects = new ArrayList<>();
    private final ArrayList<Integer> pageIds = new ArrayList<>();
    private final int pagesId;

    PdfWriter() {
        objects.add(null); // 1: catálogo
        objects.add(null); // 2: árbol de páginas
        pagesId = 2;
    }

    int reserve() { objects.add(null); return objects.size(); }

    void set(int id, String body) { objects.set(id - 1, body.getBytes(StandardCharsets.ISO_8859_1)); }
    void set(int id, byte[] body) { objects.set(id - 1, body); }

    int add(String body) { int id = reserve(); set(id, body); return id; }

    int stream(String dict, byte[] data, boolean compress) {
        byte[] body = data;
        String extra = "";
        if (compress) {
            Deflater d = new Deflater(6);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!d.finished()) o.write(buf, 0, d.deflate(buf));
            d.end();
            body = o.toByteArray();
            extra = "/Filter /FlateDecode ";
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            o.write(("<< " + dict + " " + extra + "/Length " + body.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            o.write(body);
            o.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException ignored) {}
        int id = reserve();
        set(id, o.toByteArray());
        return id;
    }

    void page(int w, int h, int contents, String resources) {
        int id = add("<< /Type /Page /Parent " + pagesId + " 0 R /MediaBox [0 0 " + w + " " + h + "] /Contents " + contents + " 0 R /Resources " + resources + " >>");
        pageIds.add(id);
    }

    void finish(OutputStream out) throws IOException {
        StringBuilder kids = new StringBuilder();
        for (int id : pageIds) kids.append(id).append(" 0 R ");
        set(1, "<< /Type /Catalog /Pages " + pagesId + " 0 R >>");
        set(pagesId, "<< /Type /Pages /Kids [" + kids + "] /Count " + pageIds.size() + " >>");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        long[] offsets = new long[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = o.size();
            o.write(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] b = objects.get(i);
            o.write(b == null ? "null".getBytes(StandardCharsets.ISO_8859_1) : b);
            o.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        long xref = o.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (long off : offsets) x.append(String.format("%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R /Producer (Sync Tablet) >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        o.write(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        o.writeTo(out);
    }
}
