package android.view;

import android.graphics.Outline;

public abstract class ViewOutlineProvider {
    public static final ViewOutlineProvider BACKGROUND = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg != null) bg.getOutline(o); else { o.setRect(0, 0, v.getWidth(), v.getHeight()); o.setAlpha(0f); }
        }
    };
    public static final ViewOutlineProvider BOUNDS = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) { o.setRect(0, 0, v.getWidth(), v.getHeight()); }
    };
    public static final ViewOutlineProvider PADDED_BOUNDS = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) { o.setRect(v.getPaddingLeft(), v.getPaddingTop(), v.getWidth() - v.getPaddingRight(), v.getHeight() - v.getPaddingBottom()); }
    };
    public abstract void getOutline(View view, Outline outline);
}
