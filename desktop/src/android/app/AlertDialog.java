package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AlertDialog extends Dialog {
    public static final int THEME_DEVICE_DEFAULT_DARK = 4, THEME_DEVICE_DEFAULT_LIGHT = 5;

    CharSequence title, message;
    View view;
    CharSequence[] items;
    DialogInterface.OnClickListener itemsListener;
    final CharSequence[] buttonText = new CharSequence[3];
    final DialogInterface.OnClickListener[] buttonListener = new DialogInterface.OnClickListener[3];
    private final Button[] buttons = new Button[3];

    protected AlertDialog(Context c) { super(c); }
    protected AlertDialog(Context c, int theme) { super(c, theme); }

    @Override public void setTitle(CharSequence t) { title = t; }
    public void setMessage(CharSequence m) { message = m; }
    public void setView(View v) { view = v; }
    public void setButton(int which, CharSequence text, DialogInterface.OnClickListener l) { int i = index(which); buttonText[i] = text; buttonListener[i] = l; }
    public Button getButton(int which) { return buttons[index(which)]; }
    public android.widget.ListView getListView() { return null; }

    private static int index(int which) { return which == BUTTON_POSITIVE ? 0 : which == BUTTON_NEGATIVE ? 1 : 2; }

    @Override
    protected View buildContent() {
        Context c = context;
        float d = c.getResources().getDisplayMetrics().density;
        boolean night = c.getResources().getConfiguration().isNightModeActive();
        int fg = night ? 0xFFEEF3F4 : 0xFF1F2937, muted = night ? 0xFFB6C2C6 : 0xFF4B5563, accent = night ? 0xFF2DD4BF : 0xFF0F766E;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * d);
        col.setPadding(pad, pad, pad, Math.round(12 * d));
        if (title != null) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(20);
            t.setTextColor(fg);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (message != null) {
            TextView m = new TextView(c);
            m.setText(message);
            m.setTextSize(15);
            m.setTextColor(muted);
            m.setLineSpacing(0, 1.15f);
            m.setPadding(0, Math.round((title != null ? 12 : 0) * d), 0, 0);
            col.addView(m, new LinearLayout.LayoutParams(-1, -2));
        }
        if (items != null) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < items.length; i++) {
                TextView it = new TextView(c);
                it.setText(items[i]);
                it.setTextSize(16);
                it.setTextColor(fg);
                it.setPadding(Math.round(8 * d), Math.round(12 * d), Math.round(8 * d), Math.round(12 * d));
                it.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x22000000), null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
                final int which = i;
                it.setOnClickListener(v -> { if (itemsListener != null) itemsListener.onClick(this, which); dismiss(); });
                list.addView(it, new LinearLayout.LayoutParams(-1, -2));
            }
            ScrollView sv = new ScrollView(c);
            sv.addView(list);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Math.round(8 * d);
            col.addView(sv, lp);
        }
        if (view != null) {
            if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) view.getParent()).removeView(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Math.round(12 * d);
            col.addView(view, lp);
        }
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(16 * d), 0, 0);
        int[] order = {2, 1, 0};
        boolean any = false;
        for (int i : order) {
            if (buttonText[i] == null) continue;
            any = true;
            Button b = new Button(c);
            b.setText(buttonText[i]);
            b.setTextSize(14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(accent);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(20 * d);
            shape.setColor(0);
            b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf((accent & 0xFFFFFF) | 0x33000000), shape, null));
            final int which = i == 0 ? BUTTON_POSITIVE : i == 1 ? BUTTON_NEGATIVE : BUTTON_NEUTRAL;
            final DialogInterface.OnClickListener l = buttonListener[i];
            b.setOnClickListener(v -> { if (l != null) l.onClick(this, which); dismiss(); });
            buttons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Math.round(40 * d));
            lp.leftMargin = Math.round(8 * d);
            if (i == 2) { row.addView(b, lp); View gap = new View(c); row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f)); }
            else row.addView(b, lp);
        }
        if (any) col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        ScrollView outer = new ScrollView(c);
        outer.addView(col);
        return outer;
    }

    public static class Builder {
        private final Context context;
        private CharSequence title, message;
        private View view;
        private CharSequence[] items;
        private DialogInterface.OnClickListener itemsListener;
        private final CharSequence[] buttonText = new CharSequence[3];
        private final DialogInterface.OnClickListener[] buttonListener = new DialogInterface.OnClickListener[3];
        private boolean cancelable = true;
        private DialogInterface.OnDismissListener onDismiss;
        private DialogInterface.OnCancelListener onCancel;

        public Builder(Context c) { context = c; }
        public Builder(Context c, int theme) { context = c; }
        public Context getContext() { return context; }
        public Builder setTitle(CharSequence t) { title = t; return this; }
        public Builder setTitle(int id) { title = context.getString(id); return this; }
        public Builder setMessage(CharSequence m) { message = m; return this; }
        public Builder setMessage(int id) { message = context.getString(id); return this; }
        public Builder setView(View v) { view = v; return this; }
        public Builder setIcon(int id) { return this; }
        public Builder setIcon(android.graphics.drawable.Drawable d) { return this; }
        public Builder setCustomTitle(View v) { return this; }
        public Builder setItems(CharSequence[] i, DialogInterface.OnClickListener l) { items = i; itemsListener = l; return this; }
        public Builder setSingleChoiceItems(CharSequence[] i, int checked, DialogInterface.OnClickListener l) { items = i; itemsListener = l; return this; }
        public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { buttonText[0] = t; buttonListener[0] = l; return this; }
        public Builder setPositiveButton(int id, DialogInterface.OnClickListener l) { return setPositiveButton(context.getString(id), l); }
        public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { buttonText[1] = t; buttonListener[1] = l; return this; }
        public Builder setNegativeButton(int id, DialogInterface.OnClickListener l) { return setNegativeButton(context.getString(id), l); }
        public Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) { buttonText[2] = t; buttonListener[2] = l; return this; }
        public Builder setNeutralButton(int id, DialogInterface.OnClickListener l) { return setNeutralButton(context.getString(id), l); }
        public Builder setCancelable(boolean c) { cancelable = c; return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener l) { onDismiss = l; return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener l) { onCancel = l; return this; }

        public AlertDialog create() {
            AlertDialog d = new AlertDialog(context);
            d.title = title; d.message = message; d.view = view; d.items = items; d.itemsListener = itemsListener;
            System.arraycopy(buttonText, 0, d.buttonText, 0, 3);
            System.arraycopy(buttonListener, 0, d.buttonListener, 0, 3);
            d.setCancelable(cancelable);
            if (onDismiss != null) d.setOnDismissListener(onDismiss);
            if (onCancel != null) d.setOnCancelListener(onCancel);
            return d;
        }

        public AlertDialog show() { AlertDialog d = create(); d.show(); return d; }
    }
}
