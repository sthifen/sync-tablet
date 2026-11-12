package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Un diálogo como capa sobre la actividad: fondo oscurecido y una tarjeta en el centro. */
public class Dialog implements DialogInterface {
    protected final Context context;
    private FrameLayout overlay;
    protected FrameLayout card;
    private View content;
    private boolean cancelable = true, cancelOnTouchOutside = true, showing;
    private OnDismissListener onDismiss;
    private OnCancelListener onCancel;
    private OnShowListener onShow;
    private final OnBackInvokedCallback back = this::backPressed;
    private final android.view.Window window;

    public Dialog(Context c) { this(c, 0); }
    public Dialog(Context c, int theme) { context = c; window = new android.view.Window(c); }

    public final Context getContext() { return context; }
    public android.view.Window getWindow() { return window; }
    public void setContentView(View v) { content = v; }
    public void setCancelable(boolean c) { cancelable = c; }
    public void setCanceledOnTouchOutside(boolean c) { cancelOnTouchOutside = c; if (c) cancelable = true; }
    public void setOnDismissListener(OnDismissListener l) { onDismiss = l; }
    public void setOnCancelListener(OnCancelListener l) { onCancel = l; }
    public void setOnShowListener(OnShowListener l) { onShow = l; }
    public void setTitle(CharSequence t) {}
    public boolean isShowing() { return showing; }
    @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) { return content == null ? null : (T) content.findViewById(id); }

    private Activity activity() {
        Context c = context;
        if (c instanceof Activity) return (Activity) c;
        Desktop d = Desktop.get();
        return d == null ? null : d.top();
    }

    protected View buildContent() { return content; }

    public void show() {
        if (showing) return;
        Activity a = activity();
        if (a == null) return;
        View body = buildContent();
        if (body == null) return;
        float d = context.getResources().getDisplayMetrics().density;
        boolean night = context.getResources().getConfiguration().isNightModeActive();
        overlay = new FrameLayout(context) {
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && cancelable && cancelOnTouchOutside) cancel();
                return true;
            }
        };
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        card = new FrameLayout(context) {
            @Override public boolean onTouchEvent(MotionEvent e) { return true; }
        };
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF232B2F : 0xFFFFFFFF);
        bg.setCornerRadius(24 * d);
        card.setBackground(bg);
        card.setElevation(24 * d);
        card.setClipToOutline(true);
        if (body.getParent() instanceof ViewGroup) ((ViewGroup) body.getParent()).removeView(body);
        card.addView(body, new FrameLayout.LayoutParams(-1, -2));
        int maxW = Math.round(560 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER) {};
        lp.setMargins(Math.round(24 * d), Math.round(24 * d), Math.round(24 * d), Math.round(24 * d));
        overlay.addView(card, lp);
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) card.getLayoutParams();
            int side = Math.max(Math.round(24 * d), (w - maxW) / 2);
            if (p.leftMargin != side) { p.leftMargin = side; p.rightMargin = side; card.requestLayout(); }
        });
        a.getWindow().decor().addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
        showing = true;
        onStart();
        if (onShow != null) onShow.onShow(this);
    }

    protected void onStart() {}
    protected void onStop() {}

    private void backPressed() { if (cancelable) cancel(); }

    @Override public void cancel() { if (!showing) return; if (onCancel != null) onCancel.onCancel(this); dismiss(); }

    @Override
    public void dismiss() {
        if (!showing) return;
        showing = false;
        Activity a = activity();
        if (a != null) a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        if (overlay != null && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        onStop();
        if (onDismiss != null) onDismiss.onDismiss(this);
    }

    public void hide() { if (overlay != null) overlay.setVisibility(View.GONE); }
    public boolean onKeyDown(int code, KeyEvent e) { return false; }
}
