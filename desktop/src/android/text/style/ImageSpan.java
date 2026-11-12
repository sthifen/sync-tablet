package android.text.style;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

public class ImageSpan extends DynamicDrawableSpan {
    private Drawable drawable;
    private final Uri uri;
    private final String source;
    private final Context context;

    public ImageSpan(Bitmap b) { this(null, b, ALIGN_BOTTOM); }
    public ImageSpan(Bitmap b, int a) { this(null, b, a); }
    public ImageSpan(Context c, Bitmap b) { this(c, b, ALIGN_BOTTOM); }
    public ImageSpan(Context c, Bitmap b, int a) {
        super(a);
        context = c;
        drawable = new BitmapDrawable(b);
        drawable.setBounds(0, 0, b.getWidth(), b.getHeight());
        uri = null; source = null;
    }
    public ImageSpan(Drawable d) { this(d, ALIGN_BOTTOM); }
    public ImageSpan(Drawable d, int a) { super(a); drawable = d; uri = null; source = null; context = null; }
    public ImageSpan(Drawable d, String source) { this(d, source, ALIGN_BOTTOM); }
    public ImageSpan(Drawable d, String source, int a) { super(a); drawable = d; this.source = source; uri = null; context = null; }
    public ImageSpan(Context c, Uri uri) { this(c, uri, ALIGN_BOTTOM); }
    public ImageSpan(Context c, Uri uri, int a) { super(a); context = c; this.uri = uri; source = uri == null ? null : uri.toString(); }
    public ImageSpan(Context c, int resourceId) { this(c, resourceId, ALIGN_BOTTOM); }
    public ImageSpan(Context c, int resourceId, int a) { super(a); context = c; uri = null; source = null; drawable = c.getResources().getDrawable(resourceId, null); }

    @Override
    public Drawable getDrawable() {
        if (drawable != null) return drawable;
        Drawable d = null;
        if (uri != null && context != null && uri != Uri.EMPTY && !uri.toString().isEmpty()) {
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                if (b != null) { d = new BitmapDrawable(context.getResources(), b); d.setBounds(0, 0, b.getWidth(), b.getHeight()); }
            } catch (Exception ignored) {}
        }
        if (d == null) { d = new android.graphics.drawable.ColorDrawable(0); d.setBounds(0, 0, 1, 1); }
        drawable = d;
        return d;
    }

    public String getSource() { return source; }
}
