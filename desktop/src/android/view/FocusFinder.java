package android.view;

import java.util.ArrayList;

/** Siguiente vista con foco (Tab): en orden del árbol. */
final class FocusFinder {
    static View find(ViewGroup root, View focused, int direction) {
        ArrayList<View> all = new ArrayList<>();
        collect(root, all);
        if (all.isEmpty()) return null;
        int i = all.indexOf(focused);
        boolean back = direction == View.FOCUS_BACKWARD || direction == View.FOCUS_UP || direction == View.FOCUS_LEFT;
        if (i < 0) return all.get(back ? all.size() - 1 : 0);
        return all.get((i + (back ? all.size() - 1 : 1)) % all.size());
    }

    private static void collect(View v, ArrayList<View> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.isFocusable() && v.isEnabled() && v.onCheckIsTextEditor()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
