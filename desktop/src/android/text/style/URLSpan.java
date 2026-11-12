package android.text.style;

import android.content.Intent;
import android.net.Uri;
import android.view.View;

public class URLSpan extends ClickableSpan implements ParcelableSpan {
    private final String url;
    public URLSpan(String url) { this.url = url; }
    public String getURL() { return url; }
    @Override public void onClick(View w) { w.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    @Override public int getSpanTypeId() { return 11; }
}
