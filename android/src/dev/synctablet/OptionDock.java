package dev.synctablet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Las opciones de la hoja, en el mismo cristal que el dock de figuras: nace abajo
 * como una píldora, crece hasta su contenido y, abierto, pasa de un contenido a
 * otro sin cerrarse. Con teclado sube junto a él.
 */
final class OptionDock {
    /** Una tarjeta: su dibujo, su nombre y qué hace. */
    static final class Tile {
        final View preview;
        final String label;
        final boolean active;
        final Runnable action;
        /** La acción abre otro contenido: el dock sigue abierto. */
        boolean stays;

        Tile(View preview, String label, boolean active, Runnable action) {
            this.preview = preview;
            this.label = label;
            this.active = active;
            this.action = action;
        }

        Tile stays() {
            stays = true;
            return this;
        }
    }

    /** Un botón de abajo. */
    static final class Action {
        final String label;
        final boolean strong;
        final Runnable run;
        boolean stays;

        Action(String label, boolean strong, Runnable run) {
            this.label = label;
            this.strong = strong;
            this.run = run;
        }

        Action stays() {
            stays = true;
            return this;
        }
    }

    private static final int OPEN_MS = 340, CHANGE_MS = 280, CLOSE_MS = 220;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);

    private final Activity activity;
    private final FrameLayout root;
    private final GlassLayout glass;
    private final TextView title;
    private final ScrollView body;
    private final LinearLayout actions;
    private final View scrim;
    private final FrameLayout.LayoutParams lp;
    private ValueAnimator animator, lift;
    private Runnable onClose;
    private Object backCallback;
    private int bottom, keyboard;
    private boolean open;

    OptionDock(Activity activity, FrameLayout root, View behind) {
        this.activity = activity;
        this.root = root;
        scrim = new View(activity);
        scrim.setBackgroundColor(0x1F000000);
        scrim.setAlpha(0);
        scrim.setVisibility(View.GONE);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> close());
        scrim.setElevation(dp(20));
        scrim.setOutlineProvider(null);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glass = new GlassLayout(activity, behind, Ui.GLASS_LIGHT, dp(28));
        glass.setOrientation(LinearLayout.VERTICAL);
        glass.setElevation(dp(24));
        glass.setClickable(true);
        glass.setVisibility(View.GONE);
        title = Ui.text(activity, "", 15, Ui.ICON, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(20), dp(16), dp(20), dp(6));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        glass.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body = new ScrollView(activity);
        body.setVerticalScrollBarEnabled(false);
        body.setClipToPadding(false);
        glass.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(14), dp(4), dp(14), dp(14));
        glass.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lp = new FrameLayout.LayoutParams(dp(150), dp(56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(glass, lp);
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    boolean isOpen() {
        return open;
    }

    /** El cristal se repinta con lo que hay detrás. */
    void invalidateGlass() {
        if (open) glass.postInvalidateOnAnimation();
    }

    /** Barras del sistema y teclado, en píxeles. */
    void setInsets(int systemBottom, int ime) {
        boolean changed = ime != keyboard;
        bottom = systemBottom;
        keyboard = ime;
        int target = Math.max(systemBottom, ime) + dp(18);
        if (!open) {
            lp.bottomMargin = target;
            return;
        }
        if (!changed) return;
        if (lift != null) lift.cancel();
        lift = ValueAnimator.ofInt(lp.bottomMargin, target);
        lift.setDuration(220);
        lift.setInterpolator(EASE);
        lift.addUpdateListener(a -> {
            lp.bottomMargin = (int) a.getAnimatedValue();
            lp.height = Math.min(lp.height, maxHeight());
            glass.requestLayout();
        });
        lift.start();
    }

    private int maxWidth() {
        return Math.min(root.getWidth() - dp(32), dp(880));
    }

    private int maxHeight() {
        return Math.max(dp(160), root.getHeight() - Math.max(bottom, keyboard) - dp(18) - dp(96));
    }

    // ---- Contenidos ----

    /** Tarjetas para elegir. */
    void tiles(String heading, List<Tile> tiles, Action... buttons) {
        show(heading, grid(tiles), buttons);
    }

    private View grid(List<Tile> tiles) {
        int cell = dp(116), gap = dp(5), pad = dp(14);
        int fit = Math.max(1, Math.min(tiles.size(), (maxWidth() - 2 * pad) / (cell + 2 * gap)));
        // Filas parejas: mejor 4 + 3 que 6 + 1.
        int rows = (tiles.size() + fit - 1) / fit, cols = (tiles.size() + rows - 1) / rows;
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(cols);
        grid.setPadding(pad, dp(4), pad, dp(10));
        ArrayList<View> views = new ArrayList<>();
        for (Tile t : tiles) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(6), dp(8), dp(6), dp(8));
            item.setBackground(Ui.round(t.active ? 0x331F2937 : 0x12111827, dp(16)));
            item.addView(t.preview, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView name = Ui.text(activity, t.label, 12, Ui.ICON, t.active);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0);
            item.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            item.setContentDescription(t.label);
            item.setClickable(true);
            item.setOnClickListener(v -> {
                if (t.stays) {
                    t.action.run();
                } else {
                    close();
                    t.action.run();
                }
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = cell;
            p.setMargins(gap, gap, gap, gap);
            grid.addView(item, p);
            views.add(item);
        }
        grid.setTag(views);
        return grid;
    }

    /** Una pregunta de sí o no. */
    void confirm(String heading, String message, String yes, Runnable run) {
        TextView text = Ui.text(activity, message, 14, Ui.ICON_MUTED, false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(2), dp(24), dp(12));
        show(heading, text, new Action("Cancelar", false, null), new Action(yes, true, run));
    }

    /** Cualquier contenido, con sus botones. */
    void show(String heading, View content, Action... buttons) {
        title.setText(heading);
        body.removeAllViews();
        body.addView(content);
        body.scrollTo(0, 0);
        actions.removeAllViews();
        for (Action a : buttons) actions.addView(button(a));
        actions.setVisibility(buttons.length == 0 ? View.GONE : View.VISIBLE);
        if (buttons.length == 0) title.setPadding(dp(20), dp(16), dp(20), dp(6));
        // Lo que va a medir.
        int widest = maxWidth();
        content.measure(View.MeasureSpec.makeMeasureSpec(widest, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.min(widest, Math.max(dp(340), content.getMeasuredWidth()));
        int exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(exact, free);
        title.measure(exact, free);
        actions.measure(exact, free);
        int height = Math.min(maxHeight(), title.getMeasuredHeight() + content.getMeasuredHeight()
                + (buttons.length == 0 ? dp(10) : actions.getMeasuredHeight()));
        boolean first = !open;
        open = true;
        if (first) watchBack(true);
        animate(first ? dp(150) : lp.width, first ? dp(56) : lp.height, width, height, first);
        reveal(content);
    }

    private void animate(int fromW, int fromH, int toW, int toH, boolean first) {
        if (animator != null) animator.cancel();
        glass.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        lp.bottomMargin = Math.max(bottom, keyboard) + dp(18);
        float fromScrim = scrim.getAlpha();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(first ? OPEN_MS : CHANGE_MS);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            lp.width = Math.round(fromW + (toW - fromW) * k);
            lp.height = Math.round(fromH + (toH - fromH) * k);
            scrim.setAlpha(fromScrim + (1 - fromScrim) * k);
            if (first) glass.setAlpha(Math.min(1, k * 3));
            glass.requestLayout();
        });
        animator.start();
    }

    /** El contenido llega poco después que el cristal; las tarjetas, una tras otra. */
    @SuppressWarnings("unchecked")
    private void reveal(View content) {
        Object tag = content.getTag();
        if (tag instanceof List) {
            int i = 0;
            for (View v : (List<View>) tag) {
                v.setAlpha(0);
                v.setTranslationY(dp(12));
                v.animate().alpha(1).translationY(0).setStartDelay(70 + 16L * Math.min(i, 14)).setDuration(220).setInterpolator(EASE).start();
                i++;
            }
        } else {
            content.setAlpha(0);
            content.animate().alpha(1).setStartDelay(90).setDuration(200).start();
        }
    }

    void close() {
        if (!open) return;
        open = false;
        watchBack(false);
        hideKeyboard();
        Runnable after = onClose;
        onClose = null;
        if (animator != null) animator.cancel();
        int fromW = lp.width, fromH = lp.height, toW = dp(150), toH = dp(56);
        float fromScrim = scrim.getAlpha();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(CLOSE_MS);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            lp.width = Math.round(fromW + (toW - fromW) * k);
            lp.height = Math.round(fromH + (toH - fromH) * k);
            scrim.setAlpha(fromScrim * (1 - k));
            glass.setAlpha(1 - k);
            glass.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                glass.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
                body.removeAllViews();
            }
        });
        animator.start();
        if (after != null) after.run();
    }

    /** Con el dock abierto, Atrás lo cierra. Android 13 en adelante avisa así, no con onBackPressed. */
    private void watchBack(boolean on) {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        android.window.OnBackInvokedDispatcher back = activity.getOnBackInvokedDispatcher();
        if (on) {
            android.window.OnBackInvokedCallback closeDock = this::close;
            backCallback = closeDock;
            back.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeDock);
        } else if (backCallback != null) {
            back.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    /** Se llama al cerrarse, sea como sea. */
    void onClose(Runnable r) {
        onClose = r;
    }

    // ---- Piezas ----

    private TextView button(Action a) {
        TextView t = chip(a.label, a.strong);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.setMargins(dp(5), 0, dp(5), 0);
        t.setLayoutParams(p);
        t.setOnClickListener(v -> {
            if (a.stays) {
                a.run.run();
            } else {
                close();
                if (a.run != null) a.run.run();
            }
        });
        return t;
    }

    /** Un botón de texto para el cristal. */
    TextView chip(String label, boolean strong) {
        TextView t = Ui.text(activity, label, 14, strong ? 0xFFFFFFFF : Ui.ICON, strong);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(42));
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(Ui.round(strong ? Ui.ACCENT_DEEP : 0x14111827, dp(14)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    void setChip(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : Ui.ICON);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(Ui.round(on ? Ui.ACCENT_DEEP : 0x14111827, dp(14)));
    }

    /** Un campo de texto para el cristal. */
    EditText field(String text, String hint) {
        EditText e = new EditText(activity);
        e.setText(text);
        e.setHint(hint);
        e.setTextColor(Ui.ICON);
        e.setHintTextColor(0x88111827);
        e.setTextSize(16);
        e.setBackground(Ui.round(0x14111827, dp(14)));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    /** Abre el teclado para ese campo, cuando el dock ya está en su sitio. */
    void focus(EditText e) {
        e.postDelayed(() -> {
            e.requestFocus();
            InputMethodManager im = activity.getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    private void hideKeyboard() {
        InputMethodManager im = activity.getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(glass.getWindowToken(), 0);
    }
}
