package android.content;

import java.util.ArrayList;

public class ClipData {
    public static class Item {
        private final CharSequence text;
        private final android.net.Uri uri;
        private final String html;
        public Item(CharSequence t) { text = t; uri = null; html = null; }
        public Item(CharSequence t, String html) { text = t; uri = null; this.html = html; }
        public Item(android.net.Uri u) { text = null; uri = u; html = null; }
        public CharSequence getText() { return text; }
        public String getHtmlText() { return html; }
        public android.net.Uri getUri() { return uri; }
        public Intent getIntent() { return null; }
        public CharSequence coerceToText(Context c) { return text != null ? text : uri != null ? uri.toString() : ""; }
    }

    private final ClipDescription description;
    private final ArrayList<Item> items = new ArrayList<>();

    public ClipData(CharSequence label, String[] mime, Item item) { description = new ClipDescription(label, mime); items.add(item); }
    public ClipData(ClipDescription d, Item item) { description = d; items.add(item); }
    public static ClipData newPlainText(CharSequence label, CharSequence text) { return new ClipData(label, new String[] {ClipDescription.MIMETYPE_TEXT_PLAIN}, new Item(text)); }
    public static ClipData newHtmlText(CharSequence label, CharSequence text, String html) { return new ClipData(label, new String[] {ClipDescription.MIMETYPE_TEXT_HTML}, new Item(text, html)); }
    public static ClipData newUri(ContentResolver r, CharSequence label, android.net.Uri uri) { return new ClipData(label, new String[] {"text/uri-list"}, new Item(uri)); }
    public static ClipData newRawUri(CharSequence label, android.net.Uri uri) { return new ClipData(label, new String[] {"text/uri-list"}, new Item(uri)); }
    public void addItem(Item i) { items.add(i); }
    public int getItemCount() { return items.size(); }
    public Item getItemAt(int i) { return items.get(i); }
    public ClipDescription getDescription() { return description; }
}
