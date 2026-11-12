package android.widget;

import android.content.Context;

public class Button extends TextView {
    public Button(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        setGravity(android.view.Gravity.CENTER);
        setAllCaps(false);
        float d = c.getResources().getDisplayMetrics().density;
        setMinimumHeight(Math.round(48 * d));
        setMinimumWidth(Math.round(64 * d));
        setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
    }
    public Button(Context c, android.util.AttributeSet a) { this(c); }
    public Button(Context c, android.util.AttributeSet a, int s) { this(c); }
}
