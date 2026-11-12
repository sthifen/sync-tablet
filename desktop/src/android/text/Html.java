package android.text;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ParagraphStyle;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML ↔ texto con spans, con las mismas reglas y la misma salida que android.text.Html. */
public class Html {
    public interface ImageGetter { Drawable getDrawable(String source); }
    public interface TagHandler { void handleTag(boolean opening, String tag, Editable output, org.xml.sax.XMLReader xmlReader); }

    public static final int FROM_HTML_MODE_LEGACY = 0, FROM_HTML_MODE_COMPACT = 0x3f, FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 1,
            FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 2, FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 4, FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 8,
            FROM_HTML_SEPARATOR_LINE_BREAK_DIV = 16, FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 32, FROM_HTML_OPTION_USE_CSS_COLORS = 256;
    public static final int TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0, TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 1;

    @Deprecated public static Spanned fromHtml(String source) { return fromHtml(source, FROM_HTML_MODE_LEGACY, null, null); }
    public static Spanned fromHtml(String source, int flags) { return fromHtml(source, flags, null, null); }
    @Deprecated public static Spanned fromHtml(String source, ImageGetter g, TagHandler t) { return fromHtml(source, FROM_HTML_MODE_LEGACY, g, t); }

    public static Spanned fromHtml(String source, int flags, ImageGetter imageGetter, TagHandler tagHandler) {
        return new Converter(source == null ? "" : source, flags, imageGetter, tagHandler).convert();
    }

    // ---------------------------------------------------------------- leer

    private static final class Bold {}
    private static final class Italic {}
    private static final class Underline {}
    private static final class Strike {}
    private static final class Big {}
    private static final class Small {}
    private static final class Mono {}
    private static final class Quote {}
    private static final class Super {}
    private static final class Sub {}
    private static final class Bullet {}
    private static final class Font { final String color, face; Font(String c, String f) { color = c; face = f; } }
    private static final class Href { final String href; Href(String h) { href = h; } }
    private static final class Heading { final int level; Heading(int l) { level = l; } }
    private static final class Newline { final int n; Newline(int n) { this.n = n; } }
    private static final class Align { final Layout.Alignment a; Align(Layout.Alignment a) { this.a = a; } }
    private static final class Fg { final int c; Fg(int c) { this.c = c; } }
    private static final class Bg { final int c; Bg(int c) { this.c = c; } }

    private static final float[] HEADING_SIZES = {1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1f};
    private static final Pattern TEXT_ALIGN = Pattern.compile("(?:\\s+|\\A)text-align\\s*:\\s*(\\S*)\\b");
    private static final Pattern FG = Pattern.compile("(?:\\s+|\\A)color\\s*:\\s*(\\S*)\\b");
    private static final Pattern BG = Pattern.compile("(?:\\s+|\\A)background(?:-color)?\\s*:\\s*(\\S*)\\b");
    private static final Pattern DECO = Pattern.compile("(?:\\s+|\\A)text-decoration\\s*:\\s*(\\S*)\\b");

    private static final class Converter {
        private final String src;
        private final int flags;
        private final ImageGetter images;
        private final TagHandler handler;
        private final SpannableStringBuilder out = new SpannableStringBuilder();
        private int pos, skipTo = -1;

        Converter(String src, int flags, ImageGetter i, TagHandler t) { this.src = src; this.flags = flags; images = i; handler = t; }

        private int margin(int flag) { return (flags & flag) != 0 ? 1 : 2; }

        Spanned convert() {
            StringBuilder textBuf = new StringBuilder();
            int n = src.length();
            while (pos < n) {
                char c = src.charAt(pos);
                if (c == '<') {
                    if (src.startsWith("<!--", pos)) {
                        int e = src.indexOf("-->", pos + 4);
                        pos = e < 0 ? n : e + 3;
                        continue;
                    }
                    int e = tagEnd(pos);
                    if (e < 0) { textBuf.append(c); pos++; continue; }
                    flushText(textBuf);
                    skipTo = -1;
                    tag(src.substring(pos + 1, e));
                    // <script>, <style>: se salta el contenido.
                    pos = skipTo >= 0 ? skipTo : e + 1;
                    continue;
                }
                if (c == '&') {
                    int semi = src.indexOf(';', pos);
                    if (semi > pos && semi - pos < 12) {
                        String ent = src.substring(pos + 1, semi);
                        String v = entity(ent);
                        if (v != null) { textBuf.append(v); pos = semi + 1; continue; }
                    }
                }
                textBuf.append(c);
                pos++;
            }
            flushText(textBuf);
            // Los de párrafo, como SPAN_PARAGRAPH y sin la línea en blanco del final.
            for (Object o : out.getSpans(0, out.length(), ParagraphStyle.class)) {
                int s = out.getSpanStart(o), e = out.getSpanEnd(o);
                if (e - 2 >= 0 && out.charAt(e - 1) == '\n' && out.charAt(e - 2) == '\n') e--;
                if (e == s) out.removeSpan(o); else out.setSpan(o, s, e, Spanned.SPAN_PARAGRAPH);
            }
            return out;
        }

        private int tagEnd(int start) {
            char q = 0;
            for (int i = start + 1; i < src.length(); i++) {
                char c = src.charAt(i);
                if (q != 0) { if (c == q) q = 0; continue; }
                if (c == '"' || c == '\'') q = c;
                else if (c == '>') return i;
            }
            return -1;
        }

        private void flushText(StringBuilder b) {
            if (b.length() == 0) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length(); i++) {
                char c = b.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    char pred;
                    int len = sb.length();
                    if (len == 0) {
                        len = out.length();
                        pred = len == 0 ? '\n' : out.charAt(len - 1);
                    } else pred = sb.charAt(len - 1);
                    if (pred != ' ' && pred != '\n') sb.append(' ');
                } else sb.append(c);
            }
            out.append(sb);
            b.setLength(0);
        }

        private void tag(String raw) {
            raw = raw.trim();
            if (raw.startsWith("!") || raw.startsWith("?")) return;
            boolean closing = raw.startsWith("/");
            if (closing) raw = raw.substring(1).trim();
            boolean selfClose = raw.endsWith("/");
            if (selfClose) raw = raw.substring(0, raw.length() - 1).trim();
            int sp = 0;
            while (sp < raw.length() && !Character.isWhitespace(raw.charAt(sp))) sp++;
            String name = raw.substring(0, sp).toLowerCase(Locale.ROOT);
            HashMap<String, String> attrs = attrs(raw.substring(sp));
            if (name.equals("script") || name.equals("style") || name.equals("head") || name.equals("title")) {
                if (!closing) {
                    int e = src.toLowerCase(Locale.ROOT).indexOf("</" + name, pos);
                    if (e >= 0) { int gt = src.indexOf('>', e); skipTo = gt < 0 ? src.length() : gt + 1; }
                }
                return;
            }
            if (!closing) start(name, attrs);
            if (closing || selfClose) end(name);
        }

        private HashMap<String, String> attrs(String s) {
            HashMap<String, String> m = new HashMap<>();
            Matcher a = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?").matcher(s);
            while (a.find()) {
                String v = a.group(2);
                if (v == null) v = "";
                else if (v.startsWith("\"") || v.startsWith("'")) v = v.substring(1, v.length() - 1);
                m.put(a.group(1).toLowerCase(Locale.ROOT), decodeEntities(v));
            }
            return m;
        }

        private void start(String tag, HashMap<String, String> a) {
            switch (tag) {
                case "br": break;
                case "p": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH)); startCss(a); break;
                case "ul": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_LIST)); break;
                case "ol": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_LIST)); break;
                case "li": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM)); mark(new Bullet()); startCss(a); break;
                case "div": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_DIV)); break;
                case "span": startCss(a); break;
                case "strong": case "b": mark(new Bold()); break;
                case "em": case "cite": case "dfn": case "i": mark(new Italic()); break;
                case "big": mark(new Big()); break;
                case "small": mark(new Small()); break;
                case "font": mark(new Font(a.get("color"), a.get("face"))); break;
                case "blockquote": startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE)); mark(new Quote()); break;
                case "tt": case "code": mark(new Mono()); break;
                case "a": mark(new Href(a.get("href"))); break;
                case "u": case "ins": mark(new Underline()); break;
                case "del": case "s": case "strike": mark(new Strike()); break;
                case "sup": mark(new Super()); break;
                case "sub": mark(new Sub()); break;
                case "img": img(a); break;
                default:
                    if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                        startBlock(a, margin(FROM_HTML_SEPARATOR_LINE_BREAK_HEADING));
                        mark(new Heading(tag.charAt(1) - '1'));
                    } else if (handler != null) handler.handleTag(true, tag, out, null);
            }
        }

        private void end(String tag) {
            switch (tag) {
                case "br": out.append("\n"); break;
                case "p": endCss(); endBlock(); break;
                case "ul": case "ol": endBlock(); break;
                case "li": endCss(); endBlock(); endMark(Bullet.class, new BulletSpan()); break;
                case "div": endBlock(); break;
                case "span": endCss(); break;
                case "strong": case "b": endMark(Bold.class, new StyleSpan(Typeface.BOLD)); break;
                case "em": case "cite": case "dfn": case "i": endMark(Italic.class, new StyleSpan(Typeface.ITALIC)); break;
                case "big": endMark(Big.class, new RelativeSizeSpan(1.25f)); break;
                case "small": endMark(Small.class, new RelativeSizeSpan(0.8f)); break;
                case "font": endFont(); break;
                case "blockquote": endBlock(); endMark(Quote.class, new QuoteSpan()); break;
                case "tt": case "code": endMark(Mono.class, new TypefaceSpan("monospace")); break;
                case "a": {
                    Href h = last(Href.class);
                    if (h != null && h.href != null) setFromMark(h, new URLSpan(h.href)); else if (h != null) out.removeSpan(h);
                    break;
                }
                case "u": case "ins": endMark(Underline.class, new UnderlineSpan()); break;
                case "del": case "s": case "strike": endMark(Strike.class, new StrikethroughSpan()); break;
                case "sup": endMark(Super.class, new SuperscriptSpan()); break;
                case "sub": endMark(Sub.class, new SubscriptSpan()); break;
                case "img": break;
                default:
                    if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                        endBlock();
                        Heading h = last(Heading.class);
                        if (h != null) setFromMark(h, new RelativeSizeSpan(HEADING_SIZES[h.level]), new StyleSpan(Typeface.BOLD));
                    } else if (handler != null) handler.handleTag(false, tag, out, null);
            }
        }

        private void mark(Object m) { int len = out.length(); out.setSpan(m, len, len, Spanned.SPAN_INCLUSIVE_EXCLUSIVE); }

        @SuppressWarnings("unchecked")
        private <T> T last(Class<T> kind) {
            T[] objs = out.getSpans(0, out.length(), kind);
            return objs.length == 0 ? null : objs[objs.length - 1];
        }

        private void setFromMark(Object mark, Object... spans) {
            int where = out.getSpanStart(mark);
            out.removeSpan(mark);
            int len = out.length();
            if (where != len && where >= 0) for (Object s : spans) out.setSpan(s, where, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        private <T> void endMark(Class<T> kind, Object repl) {
            T m = last(kind);
            if (m != null) setFromMark(m, repl);
        }

        private void appendNewlines(int min) {
            int len = out.length();
            if (len == 0) return;
            int existing = 0;
            for (int i = len - 1; i >= 0 && out.charAt(i) == '\n'; i--) existing++;
            for (int j = existing; j < min; j++) out.append("\n");
        }

        private void startBlock(HashMap<String, String> a, int margin) {
            appendNewlines(margin);
            mark(new Newline(margin));
            String style = a.get("style");
            if (style != null) {
                Matcher m = TEXT_ALIGN.matcher(style);
                if (m.find()) {
                    String al = m.group(1);
                    if (al.equalsIgnoreCase("start")) mark(new Align(Layout.Alignment.ALIGN_NORMAL));
                    else if (al.equalsIgnoreCase("center")) mark(new Align(Layout.Alignment.ALIGN_CENTER));
                    else if (al.equalsIgnoreCase("end")) mark(new Align(Layout.Alignment.ALIGN_OPPOSITE));
                    else if (al.equalsIgnoreCase("left")) mark(new Align(Layout.Alignment.ALIGN_NORMAL));
                    else if (al.equalsIgnoreCase("right")) mark(new Align(Layout.Alignment.ALIGN_OPPOSITE));
                }
            }
        }

        private void endBlock() {
            Newline n = last(Newline.class);
            if (n != null) { appendNewlines(n.n); out.removeSpan(n); }
            Align a = last(Align.class);
            if (a != null) setFromMark(a, new AlignmentSpan.Standard(a.a));
        }

        private void startCss(HashMap<String, String> a) {
            String style = a.get("style");
            if (style == null) return;
            Matcher m = FG.matcher(style);
            if (m.find()) { int c = color(m.group(1)); if (c != -1) mark(new Fg(c | 0xFF000000)); }
            m = BG.matcher(style);
            if (m.find()) { int c = color(m.group(1)); if (c != -1) mark(new Bg(c | 0xFF000000)); }
            m = DECO.matcher(style);
            if (m.find() && m.group(1).equalsIgnoreCase("line-through")) mark(new Strike());
        }

        private void endCss() {
            Strike s = last(Strike.class);
            if (s != null) setFromMark(s, new StrikethroughSpan());
            Bg b = last(Bg.class);
            if (b != null) setFromMark(b, new BackgroundColorSpan(b.c));
            Fg f = last(Fg.class);
            if (f != null) setFromMark(f, new ForegroundColorSpan(f.c));
        }

        private void endFont() {
            Font f = last(Font.class);
            if (f == null) return;
            int where = out.getSpanStart(f);
            out.removeSpan(f);
            int len = out.length();
            if (where == len) return;
            if (f.face != null && !f.face.isEmpty()) out.setSpan(new TypefaceSpan(f.face), where, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (f.color != null) {
                int c = color(f.color);
                if (c != -1) out.setSpan(new ForegroundColorSpan(c | 0xFF000000), where, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        private void img(HashMap<String, String> a) {
            String srcAttr = a.get("src");
            Drawable d = null;
            if (images != null) d = images.getDrawable(srcAttr);
            if (d == null) {
                d = new ColorDrawable(0xFF9E9E9E);
                d.setBounds(0, 0, 24, 24);
            }
            int len = out.length();
            out.append("\uFFFC");
            out.setSpan(new ImageSpan(d, srcAttr), len, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final HashMap<String, Integer> COLORS = new HashMap<>();
    static {
        String[][] c = {{"aqua", "00FFFF"}, {"black", "000000"}, {"blue", "0000FF"}, {"darkgray", "A9A9A9"}, {"darkgrey", "A9A9A9"}, {"fuchsia", "FF00FF"},
            {"gray", "808080"}, {"grey", "808080"}, {"green", "008000"}, {"lightgray", "D3D3D3"}, {"lightgrey", "D3D3D3"}, {"lime", "00FF00"},
            {"maroon", "800000"}, {"navy", "000080"}, {"olive", "808000"}, {"purple", "800080"}, {"red", "FF0000"}, {"silver", "C0C0C0"},
            {"teal", "008080"}, {"white", "FFFFFF"}, {"yellow", "FFFF00"}, {"orange", "FFA500"}};
        for (String[] x : c) COLORS.put(x[0], Integer.parseInt(x[1], 16));
    }

    static int color(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.ROOT);
        Integer n = COLORS.get(s);
        if (n != null) return n;
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                return Integer.parseInt(h.substring(0, Math.min(6, h.length())), 16);
            }
            if (s.startsWith("0x")) return Integer.parseInt(s.substring(2), 16);
            if (s.startsWith("rgb")) {
                Matcher m = Pattern.compile("rgba?\\((\\d+)\\D+(\\d+)\\D+(\\d+)").matcher(s);
                if (m.find()) return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) & 0xFFFFFF;
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    private static final HashMap<String, String> ENTITIES = new HashMap<>();
    static {
        String[][] e = {{"nbsp", "\u00A0"}, {"lt", "<"}, {"gt", ">"}, {"amp", "&"}, {"quot", "\""}, {"apos", "'"}, {"copy", "\u00A9"}, {"reg", "\u00AE"},
            {"hellip", "\u2026"}, {"mdash", "\u2014"}, {"ndash", "\u2013"}, {"laquo", "\u00AB"}, {"raquo", "\u00BB"}, {"euro", "\u20AC"},
            {"aacute", "á"}, {"eacute", "é"}, {"iacute", "í"}, {"oacute", "ó"}, {"uacute", "ú"}, {"ntilde", "ñ"}, {"Aacute", "Á"}, {"Eacute", "É"},
            {"Iacute", "Í"}, {"Oacute", "Ó"}, {"Uacute", "Ú"}, {"Ntilde", "Ñ"}, {"uuml", "ü"}, {"Uuml", "Ü"}, {"iexcl", "¡"}, {"iquest", "¿"},
            {"deg", "°"}, {"middot", "·"}, {"bull", "•"}, {"times", "×"}, {"divide", "÷"}, {"lsquo", "\u2018"}, {"rsquo", "\u2019"}, {"ldquo", "\u201C"}, {"rdquo", "\u201D"}};
        for (String[] x : e) ENTITIES.put(x[0], x[1]);
    }

    static String entity(String ent) {
        if (ent.startsWith("#")) {
            try {
                int cp = ent.startsWith("#x") || ent.startsWith("#X") ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
                return new String(Character.toChars(cp));
            } catch (Exception e) {
                return null;
            }
        }
        return ENTITIES.get(ent);
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i < 12) {
                    String v = entity(s.substring(i + 1, semi));
                    if (v != null) { b.append(v); i = semi; continue; }
                }
            }
            b.append(c);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- escribir

    @Deprecated public static String toHtml(Spanned text) { return toHtml(text, TO_HTML_PARAGRAPH_LINES_CONSECUTIVE); }

    public static String toHtml(Spanned text, int option) {
        StringBuilder out = new StringBuilder();
        withinDiv(out, text, 0, text.length(), option);
        return out.toString();
    }

    public static String escapeHtml(CharSequence text) {
        StringBuilder out = new StringBuilder();
        withinStyle(out, text, 0, text.length());
        return out.toString();
    }

    private static void withinDiv(StringBuilder out, Spanned text, int start, int end, int option) {
        int next;
        for (int i = start; i < end; i = next) {
            next = text.nextSpanTransition(i, end, QuoteSpan.class);
            QuoteSpan[] quotes = text.getSpans(i, next, QuoteSpan.class);
            for (QuoteSpan q : quotes) out.append("<blockquote>");
            if ((option & 1) == TO_HTML_PARAGRAPH_LINES_INDIVIDUAL) withinBlockquoteIndividual(out, text, i, next);
            else withinBlockquoteConsecutive(out, text, i, next);
            for (QuoteSpan q : quotes) out.append("</blockquote>\n");
        }
    }

    private static String textDirection(Spanned text, int start, int end) { return " dir=\"ltr\""; }

    private static String textStyles(Spanned text, int start, int end, boolean forceNoVerticalMargin, boolean includeTextAlign) {
        String margin = null, textAlign = null;
        if (forceNoVerticalMargin) margin = "margin-top:0; margin-bottom:0;";
        if (includeTextAlign) {
            AlignmentSpan[] as = text.getSpans(start, end, AlignmentSpan.class);
            for (int i = as.length - 1; i >= 0; i--) {
                AlignmentSpan s = as[i];
                if ((text.getSpanFlags(s) & Spanned.SPAN_PARAGRAPH) == Spanned.SPAN_PARAGRAPH) {
                    Layout.Alignment a = s.getAlignment();
                    if (a == Layout.Alignment.ALIGN_NORMAL) textAlign = "text-align:start;";
                    else if (a == Layout.Alignment.ALIGN_CENTER) textAlign = "text-align:center;";
                    else if (a == Layout.Alignment.ALIGN_OPPOSITE) textAlign = "text-align:end;";
                    break;
                }
            }
        }
        if (margin == null && textAlign == null) return "";
        StringBuilder style = new StringBuilder(" style=\"");
        if (margin != null && textAlign != null) style.append(margin).append(" ").append(textAlign);
        else if (margin != null) style.append(margin);
        else style.append(textAlign);
        return style.append("\"").toString();
    }

    private static void withinBlockquoteIndividual(StringBuilder out, Spanned text, int start, int end) {
        boolean isInList = false;
        int next;
        for (int i = start; i <= end; i = next) {
            next = TextUtils.indexOf(text, '\n', i);
            if (next < 0 || next > end) next = end;
            if (next == i) {
                if (isInList) { isInList = false; out.append("</ul>\n"); }
                out.append("<br>\n");
            } else {
                boolean isListItem = false;
                for (ParagraphStyle ps : text.getSpans(i, next, ParagraphStyle.class)) {
                    if ((text.getSpanFlags(ps) & Spanned.SPAN_PARAGRAPH) == Spanned.SPAN_PARAGRAPH && ps instanceof BulletSpan) { isListItem = true; break; }
                }
                if (isListItem && !isInList) { isInList = true; out.append("<ul").append(textStyles(text, i, next, true, false)).append(">\n"); }
                if (isInList && !isListItem) { isInList = false; out.append("</ul>\n"); }
                String tag = isListItem ? "li" : "p";
                out.append("<").append(tag).append(textDirection(text, i, next)).append(textStyles(text, i, next, !isListItem, true)).append(">");
                withinParagraph(out, text, i, next);
                out.append("</").append(tag).append(">\n");
                if (next == end && isInList) { isInList = false; out.append("</ul>\n"); }
            }
            next++;
        }
    }

    private static void withinBlockquoteConsecutive(StringBuilder out, Spanned text, int start, int end) {
        out.append("<p").append(textDirection(text, start, end)).append(">");
        int next;
        for (int i = start; i < end; i = next) {
            next = TextUtils.indexOf(text, '\n', i);
            if (next < 0 || next > end) next = end;
            int nl = 0;
            while (next < end && text.charAt(next) == '\n') { nl++; next++; }
            withinParagraph(out, text, i, next - nl);
            if (nl == 1) out.append("<br>\n");
            else {
                for (int j = 2; j < nl; j++) out.append("<br>");
                if (next != end) { out.append("</p>\n"); out.append("<p").append(textDirection(text, start, end)).append(">"); }
            }
        }
        out.append("</p>\n");
    }

    private static void withinParagraph(StringBuilder out, Spanned text, int start, int end) {
        int next;
        for (int i = start; i < end; i = next) {
            next = text.nextSpanTransition(i, end, CharacterStyle.class);
            CharacterStyle[] style = text.getSpans(i, next, CharacterStyle.class);
            for (CharacterStyle s : style) {
                if (s instanceof StyleSpan) {
                    int st = ((StyleSpan) s).getStyle();
                    if ((st & Typeface.BOLD) != 0) out.append("<b>");
                    if ((st & Typeface.ITALIC) != 0) out.append("<i>");
                }
                if (s instanceof TypefaceSpan && "monospace".equals(((TypefaceSpan) s).getFamily())) out.append("<tt>");
                if (s instanceof SuperscriptSpan) out.append("<sup>");
                if (s instanceof SubscriptSpan) out.append("<sub>");
                if (s instanceof UnderlineSpan) out.append("<u>");
                if (s instanceof StrikethroughSpan) out.append("<span style=\"text-decoration:line-through;\">");
                if (s instanceof URLSpan) out.append("<a href=\"").append(((URLSpan) s).getURL()).append("\">");
                if (s instanceof ImageSpan) {
                    out.append("<img src=\"").append(((ImageSpan) s).getSource()).append("\">");
                    i = next;
                }
                if (s instanceof AbsoluteSizeSpan) {
                    AbsoluteSizeSpan a = (AbsoluteSizeSpan) s;
                    float size = a.getSize();
                    if (!a.getDip()) size /= android.content.res.Resources.getSystem().getDisplayMetrics().density;
                    out.append(String.format(Locale.US, "<span style=\"font-size:%.0fpx\";>", size));
                }
                if (s instanceof RelativeSizeSpan) out.append(String.format(Locale.US, "<span style=\"font-size:%.2fem;\">", ((RelativeSizeSpan) s).getSizeChange()));
                if (s instanceof ForegroundColorSpan) out.append(String.format("<span style=\"color:#%06X;\">", 0xFFFFFF & ((ForegroundColorSpan) s).getForegroundColor()));
                if (s instanceof BackgroundColorSpan) out.append(String.format("<span style=\"background-color:#%06X;\">", 0xFFFFFF & ((BackgroundColorSpan) s).getBackgroundColor()));
            }
            withinStyle(out, text, i, next);
            for (int j = style.length - 1; j >= 0; j--) {
                CharacterStyle s = style[j];
                if (s instanceof BackgroundColorSpan) out.append("</span>");
                if (s instanceof ForegroundColorSpan) out.append("</span>");
                if (s instanceof RelativeSizeSpan) out.append("</span>");
                if (s instanceof AbsoluteSizeSpan) out.append("</span>");
                if (s instanceof URLSpan) out.append("</a>");
                if (s instanceof StrikethroughSpan) out.append("</span>");
                if (s instanceof UnderlineSpan) out.append("</u>");
                if (s instanceof SubscriptSpan) out.append("</sub>");
                if (s instanceof SuperscriptSpan) out.append("</sup>");
                if (s instanceof TypefaceSpan && "monospace".equals(((TypefaceSpan) s).getFamily())) out.append("</tt>");
                if (s instanceof StyleSpan) {
                    int st = ((StyleSpan) s).getStyle();
                    if ((st & Typeface.BOLD) != 0) out.append("</b>");
                    if ((st & Typeface.ITALIC) != 0) out.append("</i>");
                }
            }
        }
    }

    private static void withinStyle(StringBuilder out, CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') out.append("&amp;");
            else if (c >= 0xD800 && c <= 0xDFFF) {
                if (c < 0xDC00 && i + 1 < end) {
                    char d = text.charAt(i + 1);
                    if (d >= 0xDC00 && d <= 0xDFFF) {
                        i++;
                        int codepoint = 0x010000 | (int) c - 0xD800 << 10 | (int) d - 0xDC00;
                        out.append("&#").append(codepoint).append(";");
                    }
                }
            } else if (c > 0x7E || c < ' ') {
                out.append("&#").append((int) c).append(";");
            } else if (c == ' ') {
                while (i + 1 < end && text.charAt(i + 1) == ' ') { out.append("&nbsp;"); i++; }
                out.append(' ');
            } else out.append(c);
        }
    }

    private Html() {}
}
