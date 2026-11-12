package android.widget;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Menú flotante dentro de la ventana (el de Android abre otra ventana). */
public class PopupMenu {
    public interface OnMenuItemClickListener { boolean onMenuItemClick(MenuItem item); }
    public interface OnDismissListener { void onDismiss(PopupMenu menu); }

    private final Context context;
    private final View anchor;
    private final MenuImpl menu = new MenuImpl();
    private OnMenuItemClickListener listener;
    private OnDismissListener dismissListener;
    private FrameLayout overlay;
    private int gravity = Gravity.NO_GRAVITY;

    public PopupMenu(Context c, View anchor) { context = c; this.anchor = anchor; }
    public PopupMenu(Context c, View anchor, int gravity) { this(c, anchor); this.gravity = gravity; }

    public Menu getMenu() { return menu; }
    public void setOnMenuItemClickListener(OnMenuItemClickListener l) { listener = l; }
    public void setOnDismissListener(OnDismissListener l) { dismissListener = l; }
    public void setGravity(int g) { gravity = g; }
    public void setForceShowIcon(boolean f) {}
    public void inflate(int res) {}

    public void show() {
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        for (Item it : menu.items) {
            labels.add(it.title.toString());
            acts.add(() -> {
                if (it.click != null && it.click.onMenuItemClick(it)) return;
                if (listener != null) listener.onMenuItemClick(it);
            });
        }
        overlay = open(anchor, loc[0], loc[1] + anchor.getHeight(), labels, acts, () -> { if (dismissListener != null) dismissListener.onDismiss(this); });
    }

    public void dismiss() { if (overlay != null && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay); overlay = null; }

    /** Un menú en (x, y) de la vista `v` (coordenadas de la vista). */
    public static void showAt(View v, float x, float y, List<String> labels, List<Runnable> actions) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        open(v, Math.round(loc[0] + x), Math.round(loc[1] + y), labels, actions, null);
    }

    private static FrameLayout open(View near, int x, int y, List<String> labels, List<Runnable> actions, Runnable onDismiss) {
        View root = near.getRootView();
        if (!(root instanceof FrameLayout)) return null;
        FrameLayout decor = (FrameLayout) root;
        Context c = near.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        boolean night = c.getResources().getConfiguration().isNightModeActive();
        FrameLayout overlay = new FrameLayout(c) {
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { decor.removeView(this); if (onDismiss != null) onDismiss.run(); }
                return true;
            }
        };
        overlay.setClickable(true);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF2A3236 : 0xFFFFFFFF);
        bg.setCornerRadius(10 * d);
        bg.setStroke(Math.max(1, Math.round(d)), night ? 0x22FFFFFF : 0x22000000);
        card.setBackground(bg);
        card.setElevation(10 * d);
        card.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        for (int i = 0; i < labels.size(); i++) {
            TextView t = new TextView(c);
            t.setText(labels.get(i));
            t.setTextSize(14);
            t.setTextColor(night ? 0xFFEEF3F4 : 0xFF1F2937);
            t.setPadding(Math.round(16 * d), Math.round(9 * d), Math.round(28 * d), Math.round(9 * d));
            t.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x22000000), null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            Runnable act = actions.get(i);
            t.setOnClickListener(v -> { decor.removeView(overlay); if (onDismiss != null) onDismiss.run(); act.run(); });
            card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        overlay.addView(card, lp);
        decor.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            int px = Math.max(4, Math.min(x, decor.getWidth() - w - 4)), py = y;
            if (py + h > decor.getHeight() - 4) py = Math.max(4, y - h);
            if (v.getTranslationX() != px - l || v.getTranslationY() != py - t) { v.setTranslationX(px - l); v.setTranslationY(py - t); }
        });
        return overlay;
    }

    private static final class Item implements MenuItem {
        final int id;
        final CharSequence title;
        OnMenuItemClickListener click;
        Item(int id, CharSequence t) { this.id = id; title = t; }
        @Override public int getItemId() { return id; }
        @Override public CharSequence getTitle() { return title; }
        @Override public MenuItem setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener l) { click = l::onMenuItemClick; return this; }
        @Override public MenuItem setShowAsAction(int a) { return this; }
    }

    private interface OnMenuItemClickListenerInternal { boolean onMenuItemClick(MenuItem i); }

    private static final class MenuImpl implements Menu {
        final ArrayList<Item> items = new ArrayList<>();
        @Override public MenuItem add(CharSequence t) { Item i = new Item(0, t); items.add(i); return i; }
        @Override public MenuItem add(int g, int id, int order, CharSequence t) { Item i = new Item(id, t); items.add(i); return i; }
        @Override public int size() { return items.size(); }
        @Override public MenuItem getItem(int i) { return items.get(i); }
        @Override public void clear() { items.clear(); }
    }
}
