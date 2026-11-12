package android.content;

public interface DialogInterface {
    int BUTTON_POSITIVE = -1, BUTTON_NEGATIVE = -2, BUTTON_NEUTRAL = -3;
    interface OnClickListener { void onClick(DialogInterface d, int which); }
    interface OnMultiChoiceClickListener { void onClick(DialogInterface d, int which, boolean checked); }
    interface OnDismissListener { void onDismiss(DialogInterface d); }
    interface OnCancelListener { void onCancel(DialogInterface d); }
    interface OnShowListener { void onShow(DialogInterface d); }
    interface OnKeyListener { boolean onKey(DialogInterface d, int code, android.view.KeyEvent e); }
    void cancel();
    void dismiss();
}
