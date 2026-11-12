package android.view;

import android.content.Context;

/** La raíz de cada actividad: un FrameLayout con el contenido y lo que flota encima (diálogos, avisos). */
public final class DecorView extends android.widget.FrameLayout {
    private View content;

    public DecorView(Context c) { super(c); }

    public void setContent(View v, ViewGroup.LayoutParams p) {
        if (content != null) removeView(content);
        content = v;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        addView(v, 0, p);
    }

    public View content() { return content; }

    public void dispatchConfigurationChangedPublic(android.content.res.Configuration c) { ((View) this).dispatchConfigurationChangedTree(c); }
}
