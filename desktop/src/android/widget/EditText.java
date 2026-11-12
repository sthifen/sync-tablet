package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Editable;

public class EditText extends TextView {
    public EditText(Context c) {
        super(c);
        editableMode = true;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        float d = c.getResources().getDisplayMetrics().density;
        setTextSize(18);
        setPadding(Math.round(4 * d), Math.round(10 * d), Math.round(4 * d), Math.round(13 * d));
        setBackground(new Underline(d));
        setText("");
        setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        setMinimumHeight(Math.round(48 * d));
    }

    public EditText(Context c, android.util.AttributeSet a) { this(c); }
    public EditText(Context c, android.util.AttributeSet a, int s) { this(c); }

    @Override public Editable getText() { return getEditableText(); }
    @Override public void setText(CharSequence t, BufferType type) { super.setText(t, BufferType.EDITABLE); }
    @Override public void setSelection(int start, int stop) { super.setSelection(start, stop); }
    @Override public void setSelection(int index) { super.setSelection(index); }
    @Override public void selectAll() { super.selectAll(); }
    @Override public void extendSelection(int i) { super.extendSelection(i); }

    /** El fondo del EditText de Material: una raya abajo que se vuelve del color de acento con el foco. */
    private static final class Underline extends Drawable {
        private final float d;
        private boolean focused, enabled = true;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Underline(float d) { this.d = d; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] s) {
            boolean f = has(s, android.R.attr.state_focused), e = has(s, android.R.attr.state_enabled);
            boolean changed = f != focused || e != enabled;
            focused = f; enabled = e;
            return changed;
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            p.setColor(focused ? 0xFF2DD4BF : enabled ? 0x99FFFFFF : 0x42FFFFFF);
            float h = (focused ? 2f : 1f) * d;
            float bottom = b.bottom - 8 * d;
            c.drawRect(b.left + 4 * d, bottom - h, b.right - 4 * d, bottom, p);
        }
        @Override public void setColorFilter(ColorFilter f) {}
    }
}
