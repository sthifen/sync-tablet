package android.widget;

import android.content.Context;

public class Toast {
    public static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    private CharSequence text;
    private int duration;
    private final Context context;

    public Toast(Context c) { context = c; }

    public static Toast makeText(Context c, CharSequence text, int duration) {
        Toast t = new Toast(c);
        t.text = text;
        t.duration = duration;
        return t;
    }

    public static Toast makeText(Context c, int resId, int duration) { return makeText(c, c.getString(resId), duration); }

    public void show() {
        android.app.Desktop d = android.app.Desktop.get();
        if (d != null && text != null) d.root().toast(text.toString(), duration == LENGTH_LONG ? 3500 : 2000);
    }

    public void cancel() {}
    public void setText(CharSequence t) { text = t; }
    public void setText(int id) { text = context.getString(id); }
    public void setDuration(int d) { duration = d; }
    public int getDuration() { return duration; }
    public void setGravity(int g, int x, int y) {}
    public void setView(android.view.View v) {}
}
