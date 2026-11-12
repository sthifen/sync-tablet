package android.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.MainQueue;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.KeyListener;
import android.text.method.MovementMethod;
import android.text.method.TransformationMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewRoot;
import android.view.inputmethod.EditorInfo;
import java.util.ArrayList;

public class TextView extends View implements ViewRoot.TextInput {
    public enum BufferType { NORMAL, SPANNABLE, EDITABLE }

    public interface OnEditorActionListener { boolean onEditorAction(TextView v, int actionId, KeyEvent event); }

    public static final int AUTO_SIZE_TEXT_TYPE_NONE = 0, AUTO_SIZE_TEXT_TYPE_UNIFORM = 1;

    private static final int VERY_WIDE = 1 << 20;

    final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private CharSequence text = "";
    private Editable editable;
    boolean editableMode;
    private CharSequence hint;
    private ColorStateList textColors = ColorStateList.valueOf(0xFFFFFFFF), hintColors = ColorStateList.valueOf(0x80FFFFFF);
    private int linkColor = 0xFF2DD4BF, highlightColor = 0x6633B5E5, curColor = 0xFFFFFFFF;
    private int gravity = Gravity.TOP | Gravity.START;
    private int maxLines = Integer.MAX_VALUE, minLines = 0, maxWidth = Integer.MAX_VALUE, maxHeight = Integer.MAX_VALUE, minWidthPx, ems = -1, maxEms = -1, minEms = -1;
    private boolean singleLine, horizontallyScrolling, includePad = true, allCaps, selectable, cursorVisible = true, selectAllOnFocus, linksClickable = true;
    private TextUtils.TruncateAt ellipsize;
    private float spacingAdd = 0, spacingMult = 1;
    private int inputType = InputType.TYPE_NULL, imeOptions;
    private final ArrayList<TextWatcher> watchers = new ArrayList<>();
    private OnEditorActionListener editorAction;
    private InputFilter[] filters = new InputFilter[0];
    private Layout layout, hintLayout;
    private int layoutWidth = -1;
    private final Drawable[] compound = new Drawable[4];
    private int compoundPadding;
    private float shadowRadius, shadowDx, shadowDy;
    private int shadowColor;
    private KeyListener keyListener;
    private MovementMethod movement;
    private TransformationMethod transformation;
    private boolean password;
    private final ChangeWatcher changeWatcher = new ChangeWatcher();
    private boolean blinkOn = true;
    private final Runnable blink = this::blink;
    private CharSequence error;
    private int autoSizeMin = -1, autoSizeMax = -1;

    public TextView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        paint.density = d;
        paint.setTextSize(14 * c.getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(Typeface.DEFAULT);
    }

    public TextView(Context c, android.util.AttributeSet a) { this(c); }
    public TextView(Context c, android.util.AttributeSet a, int s) { this(c); }
    public TextView(Context c, android.util.AttributeSet a, int s, int r) { this(c); }

    // ---------------------------------------------------------------- texto

    public final void setText(CharSequence t) { setText(t, BufferType.NORMAL); }
    public final void setText(int resid) { setText(getContext().getString(resid)); }
    public final void setText(char[] t, int start, int len) { setText(new String(t, start, len)); }
    public final void setTextKeepState(CharSequence t) { int s = getSelectionStart(), e = getSelectionEnd(); setText(t); if (editable != null && s >= 0) Selection.setSelection(editable, Math.min(s, editable.length()), Math.min(e, editable.length())); }

    public void setText(CharSequence t, BufferType type) {
        if (t == null) t = "";
        for (InputFilter f : filters) {
            CharSequence out = f.filter(t, 0, t.length(), new SpannableStringBuilder(""), 0, 0);
            if (out != null) t = out;
        }
        int oldLen = text.length();
        CharSequence old = text;
        for (TextWatcher w : new ArrayList<>(watchers)) w.beforeTextChanged(old, 0, oldLen, t.length());
        if (editableMode || type == BufferType.EDITABLE) {
            if (editable != null) editable.removeSpan(changeWatcher);
            editable = new SpannableStringBuilder(t);
            editable.setFilters(filters);
            editable.setSpan(changeWatcher, 0, editable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE | (100 << Spanned.SPAN_PRIORITY_SHIFT));
            Selection.setSelection(editable, 0);
            text = editable;
        } else if (type == BufferType.SPANNABLE && !(t instanceof Spannable)) {
            text = new android.text.SpannableString(t);
        } else {
            text = t instanceof Spanned ? t : t.toString();
            editable = null;
        }
        undo.clear();
        redo.clear();
        nullLayouts();
        for (TextWatcher w : new ArrayList<>(watchers)) w.onTextChanged(text, 0, oldLen, text.length());
        if (editable != null) for (TextWatcher w : new ArrayList<>(watchers)) w.afterTextChanged(editable);
    }

    public CharSequence getText() { return text; }
    public Editable getEditableText() { return editable; }
    public int length() { return text.length(); }
    public void append(CharSequence t) { append(t, 0, t.length()); }
    public void append(CharSequence t, int start, int end) {
        if (editable != null) editable.append(t, start, end);
        else {
            SpannableStringBuilder b = new SpannableStringBuilder(text);
            b.append(t, start, end);
            setText(b);
        }
    }

    public void setHint(CharSequence h) { hint = h; hintLayout = null; requestLayout(); invalidate(); }
    public void setHint(int resid) { setHint(getContext().getString(resid)); }
    public CharSequence getHint() { return hint; }
    public void setError(CharSequence e) { error = e; if (e != null && isFocused()) android.widget.Toast.makeText(getContext(), e, Toast.LENGTH_SHORT).show(); }
    public void setError(CharSequence e, Drawable icon) { setError(e); }
    public CharSequence getError() { return error; }

    private void nullLayouts() {
        layout = null;
        hintLayout = null;
        requestLayout();
        invalidate();
    }

    /** Lo que se ve: mayúsculas, contraseña o una línea. */
    private CharSequence displayText() {
        CharSequence t = text;
        if (transformation != null) t = transformation.getTransformation(t, this);
        if (password) {
            char[] dots = new char[t.length()];
            java.util.Arrays.fill(dots, '•');
            return new String(dots);
        }
        if (allCaps) return t.toString().toUpperCase();
        if (singleLine && t.toString().indexOf('\n') >= 0) {
            if (t instanceof Spanned) {
                SpannableStringBuilder b = new SpannableStringBuilder(t);
                for (int i = 0; i < b.length(); i++) if (b.charAt(i) == '\n') b.replace(i, i + 1, " ");
                // replace() puede llamar al vigilante: es una copia, no pasa nada.
                return b;
            }
            return t.toString().replace('\n', ' ');
        }
        return t;
    }

    // ---------------------------------------------------------------- aspecto

    public void setTextSize(float sp) { setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); }
    public void setTextSize(int unit, float size) {
        float px = TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics());
        if (px != paint.getTextSize()) { paint.setTextSize(px); nullLayouts(); }
    }
    public float getTextSize() { return paint.getTextSize(); }
    public float getTextScaleX() { return paint.getTextScaleX(); }
    public void setTextScaleX(float s) { paint.setTextScaleX(s); nullLayouts(); }
    public void setTypeface(Typeface tf) { if (paint.getTypeface() != tf) { paint.setTypeface(tf); nullLayouts(); } }
    public void setTypeface(Typeface tf, int style) {
        if (style > 0) {
            Typeface t = tf == null ? Typeface.defaultFromStyle(style) : Typeface.create(tf, style);
            setTypeface(t);
            int need = style & ~(t == null ? 0 : t.getStyle());
            paint.setFakeBoldText((need & Typeface.BOLD) != 0);
            paint.setTextSkewX((need & Typeface.ITALIC) != 0 ? -0.25f : 0);
        } else {
            paint.setFakeBoldText(false);
            paint.setTextSkewX(0);
            setTypeface(tf);
        }
    }
    public Typeface getTypeface() { return paint.getTypeface(); }
    public void setTextColor(int c) { textColors = ColorStateList.valueOf(c); updateColors(); }
    public void setTextColor(ColorStateList c) { if (c != null) { textColors = c; updateColors(); } }
    public final ColorStateList getTextColors() { return textColors; }
    public final int getCurrentTextColor() { return curColor; }
    public final void setHintTextColor(int c) { hintColors = ColorStateList.valueOf(c); invalidate(); }
    public final void setHintTextColor(ColorStateList c) { hintColors = c; invalidate(); }
    public final ColorStateList getHintTextColors() { return hintColors; }
    public final int getCurrentHintTextColor() { return hintColors.getColorForState(getDrawableState(), hintColors.getDefaultColor()); }
    public final void setLinkTextColor(int c) { linkColor = c; invalidate(); }
    public void setHighlightColor(int c) { highlightColor = c; invalidate(); }
    public int getHighlightColor() { return highlightColor; }
    public void setShadowLayer(float radius, float dx, float dy, int color) { shadowRadius = radius; shadowDx = dx; shadowDy = dy; shadowColor = color; invalidate(); }
    public float getShadowRadius() { return shadowRadius; }
    public void setPaintFlags(int flags) { if (paint.getFlags() != flags) { paint.setFlags(flags | Paint.ANTI_ALIAS_FLAG); nullLayouts(); } }
    public int getPaintFlags() { return paint.getFlags(); }
    public TextPaint getPaint() { return paint; }
    public void setLetterSpacing(float s) { paint.setLetterSpacing(s); nullLayouts(); }
    public float getLetterSpacing() { return paint.getLetterSpacing(); }
    public void setFontFeatureSettings(String s) {}
    public void setElegantTextHeight(boolean e) {}
    public void setFallbackLineSpacing(boolean e) {}
    public void setLineSpacing(float add, float mult) { spacingAdd = add; spacingMult = mult; nullLayouts(); }
    public float getLineSpacingExtra() { return spacingAdd; }
    public float getLineSpacingMultiplier() { return spacingMult; }
    public void setLineHeight(int px) {
        int fontHeight = paint.getFontMetricsInt(null);
        if (px != fontHeight) setLineSpacing(px - fontHeight, 1f);
    }
    public int getLineHeight() { return Math.round(paint.getFontMetricsInt(null) * spacingMult + spacingAdd); }
    public void setIncludeFontPadding(boolean p) { includePad = p; nullLayouts(); }
    public boolean getIncludeFontPadding() { return includePad; }
    public void setAllCaps(boolean a) { allCaps = a; nullLayouts(); }
    public boolean isAllCaps() { return allCaps; }
    public void setGravity(int g) {
        if ((g & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.START;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.TOP;
        if (g != gravity) { gravity = g; nullLayouts(); }
    }
    public int getGravity() { return gravity; }
    public void setBreakStrategy(int s) {}
    public void setHyphenationFrequency(int h) {}
    public void setJustificationMode(int m) {}
    public void setTextLocale(java.util.Locale l) {}
    public void setAutoSizeTextTypeWithDefaults(int type) { if (type == AUTO_SIZE_TEXT_TYPE_UNIFORM) { autoSizeMin = Math.round(12 * getResources().getDisplayMetrics().scaledDensity); autoSizeMax = Math.round(112 * getResources().getDisplayMetrics().scaledDensity); } else autoSizeMin = autoSizeMax = -1; nullLayouts(); }
    public void setAutoSizeTextTypeUniformWithConfiguration(int min, int max, int step, int unit) {
        autoSizeMin = Math.round(TypedValue.applyDimension(unit, min, getResources().getDisplayMetrics()));
        autoSizeMax = Math.round(TypedValue.applyDimension(unit, max, getResources().getDisplayMetrics()));
        nullLayouts();
    }

    private void updateColors() {
        int c = textColors.getColorForState(getDrawableState(), textColors.getDefaultColor());
        if (c != curColor) curColor = c;
        invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateColors();
        for (Drawable d : compound) if (d != null && d.isStateful()) d.setState(getDrawableState());
    }

    // ---------------------------------------------------------------- líneas y tamaño

    public void setSingleLine() { setSingleLine(true); }
    public void setSingleLine(boolean s) {
        singleLine = s;
        horizontallyScrolling = s;
        if (s) { maxLines = 1; minLines = 1; } else { maxLines = Integer.MAX_VALUE; minLines = 0; }
        nullLayouts();
    }
    public boolean isSingleLine() { return singleLine; }
    public void setHorizontallyScrolling(boolean h) { horizontallyScrolling = h; nullLayouts(); }
    public boolean isHorizontallyScrollable() { return horizontallyScrolling; }
    public void setMaxLines(int n) { maxLines = n; nullLayouts(); }
    public int getMaxLines() { return maxLines; }
    public void setMinLines(int n) { minLines = n; nullLayouts(); }
    public int getMinLines() { return minLines; }
    public void setLines(int n) { maxLines = minLines = n; nullLayouts(); }
    public void setMaxWidth(int px) { maxWidth = px; ems = maxEms = -1; nullLayouts(); }
    public int getMaxWidth() { return maxWidth; }
    public void setMinWidth(int px) { minWidthPx = px; nullLayouts(); }
    public int getMinWidth() { return minWidthPx; }
    public void setMaxHeight(int px) { maxHeight = px; nullLayouts(); }
    public int getMaxHeight() { return maxHeight; }
    public void setMinHeight(int px) { setMinimumHeight(px); }
    public int getMinHeight() { return getMinimumHeight(); }
    public void setHeight(int px) { setMinimumHeight(px); maxHeight = px; nullLayouts(); }
    public void setWidth(int px) { minWidthPx = px; maxWidth = px; nullLayouts(); }
    public void setEms(int e) { ems = e; nullLayouts(); }
    public void setMaxEms(int e) { maxEms = e; nullLayouts(); }
    public void setMinEms(int e) { minEms = e; nullLayouts(); }
    public void setEllipsize(TextUtils.TruncateAt w) { ellipsize = w; nullLayouts(); }
    public TextUtils.TruncateAt getEllipsize() { return ellipsize; }
    public void setMarqueeRepeatLimit(int n) {}

    public void setCompoundDrawables(Drawable l, Drawable t, Drawable r, Drawable b) {
        compound[0] = l; compound[1] = t; compound[2] = r; compound[3] = b;
        for (Drawable d : compound) if (d != null) d.setCallback(this);
        nullLayouts();
    }
    public void setCompoundDrawablesWithIntrinsicBounds(Drawable l, Drawable t, Drawable r, Drawable b) {
        for (Drawable d : new Drawable[] {l, t, r, b}) if (d != null) d.setBounds(0, 0, Math.max(0, d.getIntrinsicWidth()), Math.max(0, d.getIntrinsicHeight()));
        setCompoundDrawables(l, t, r, b);
    }
    public void setCompoundDrawablesWithIntrinsicBounds(int l, int t, int r, int b) {
        setCompoundDrawablesWithIntrinsicBounds(l == 0 ? null : getResources().getDrawable(l, null), t == 0 ? null : getResources().getDrawable(t, null),
                r == 0 ? null : getResources().getDrawable(r, null), b == 0 ? null : getResources().getDrawable(b, null));
    }
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable s, Drawable t, Drawable e, Drawable b) { setCompoundDrawablesWithIntrinsicBounds(s, t, e, b); }
    public void setCompoundDrawablesRelative(Drawable s, Drawable t, Drawable e, Drawable b) { setCompoundDrawables(s, t, e, b); }
    public Drawable[] getCompoundDrawables() { return compound.clone(); }
    public Drawable[] getCompoundDrawablesRelative() { return compound.clone(); }
    public void setCompoundDrawablePadding(int p) { compoundPadding = p; nullLayouts(); }
    public int getCompoundDrawablePadding() { return compoundPadding; }
    public void setCompoundDrawableTintList(ColorStateList c) { for (Drawable d : compound) if (d != null) d.setTintList(c); invalidate(); }

    public int getCompoundPaddingLeft() { return mPaddingLeft + (compound[0] == null ? 0 : compound[0].getBounds().width() + compoundPadding); }
    public int getCompoundPaddingRight() { return mPaddingRight + (compound[2] == null ? 0 : compound[2].getBounds().width() + compoundPadding); }
    public int getCompoundPaddingTop() { return mPaddingTop + (compound[1] == null ? 0 : compound[1].getBounds().height() + compoundPadding); }
    public int getCompoundPaddingBottom() { return mPaddingBottom + (compound[3] == null ? 0 : compound[3].getBounds().height() + compoundPadding); }
    public int getCompoundPaddingStart() { return getCompoundPaddingLeft(); }
    public int getCompoundPaddingEnd() { return getCompoundPaddingRight(); }
    public int getExtendedPaddingTop() { return getCompoundPaddingTop(); }
    public int getExtendedPaddingBottom() { return getCompoundPaddingBottom(); }
    public int getTotalPaddingLeft() { return getCompoundPaddingLeft(); }
    public int getTotalPaddingRight() { return getCompoundPaddingRight(); }
    public int getTotalPaddingTop() { return getExtendedPaddingTop() + verticalOffset(); }
    public int getTotalPaddingBottom() { return getExtendedPaddingBottom(); }
    public int getTotalPaddingStart() { return getTotalPaddingLeft(); }
    public int getTotalPaddingEnd() { return getTotalPaddingRight(); }

    private Layout.Alignment alignment() {
        int ta = getTextAlignment();
        switch (ta) {
            case TEXT_ALIGNMENT_CENTER: return Layout.Alignment.ALIGN_CENTER;
            case TEXT_ALIGNMENT_TEXT_END: case TEXT_ALIGNMENT_VIEW_END: return Layout.Alignment.ALIGN_OPPOSITE;
            case TEXT_ALIGNMENT_TEXT_START: case TEXT_ALIGNMENT_VIEW_START: return Layout.Alignment.ALIGN_NORMAL;
            default:
        }
        int h = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (h == Gravity.CENTER_HORIZONTAL) return Layout.Alignment.ALIGN_CENTER;
        if (h == Gravity.RIGHT) return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }

    private boolean oneLineScrolling() { return horizontallyScrolling || (singleLine && ellipsize == null); }

    private Layout makeLayout(CharSequence t, int want, boolean forHint) {
        if (want < 0) want = 0;
        boolean scroll = oneLineScrolling() && (editableMode || ellipsize == null || (ellipsize == TextUtils.TruncateAt.MARQUEE));
        int w = want;
        TextPaint p = paint;
        if (scroll) {
            float des = Layout.getDesiredWidth(t, p);
            w = Math.max(want, (int) Math.ceil(des) + 2);
        }
        int lines = maxLines == Integer.MAX_VALUE ? 0 : maxLines;
        TextUtils.TruncateAt el = editableMode || ellipsize == TextUtils.TruncateAt.MARQUEE ? null : ellipsize;
        if (singleLine && el != null) lines = 1;
        StaticLayout.Builder b = StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(1, w)).setAlignment(alignment())
                .setLineSpacing(spacingAdd, spacingMult).setIncludePad(includePad);
        if (el != null && lines > 0) b.setMaxLines(lines).setEllipsize(el).setEllipsizedWidth(want);
        return b.build();
    }

    private void autoSize(int want, int availH) {
        if (autoSizeMax <= 0 || want <= 0) return;
        float size = autoSizeMax;
        CharSequence t = displayText();
        while (size > autoSizeMin) {
            paint.setTextSize(size);
            Layout l = makeLayout(t, want, false);
            boolean fits = (maxLines == Integer.MAX_VALUE || l.getLineCount() <= maxLines) && (availH <= 0 || l.getHeight() <= availH);
            float widest = 0;
            for (int i = 0; i < l.getLineCount(); i++) widest = Math.max(widest, l.getLineWidth(i));
            if (fits && widest <= want + 0.5f) break;
            size -= Math.max(1, size * 0.06f);
        }
        paint.setTextSize(Math.max(autoSizeMin, size));
    }

    private void ensureLayout(int want) {
        if (layout == null || layoutWidth != want) {
            layoutWidth = want;
            layout = makeLayout(displayText(), want, false);
            hintLayout = hint == null ? null : makeLayout(allCaps ? hint.toString().toUpperCase() : hint, want, true);
        }
    }

    public final Layout getLayout() { return layout; }
    public int getLineCount() { return layout == null ? 0 : layout.getLineCount(); }
    public int getLineBounds(int line, Rect bounds) {
        if (layout == null) { if (bounds != null) bounds.set(0, 0, 0, 0); return 0; }
        int baseline = layout.getLineBaseline(line);
        if (bounds != null) {
            layout.getLineBounds(line, bounds);
            bounds.offset(getCompoundPaddingLeft(), getExtendedPaddingTop() + verticalOffset());
        }
        return baseline + getExtendedPaddingTop() + verticalOffset();
    }

    @Override
    public int getBaseline() {
        if (layout == null) return -1;
        return getExtendedPaddingTop() + verticalOffset() + layout.getLineBaseline(0);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int wMode = MeasureSpec.getMode(ws), wSize = MeasureSpec.getSize(ws);
        int hMode = MeasureSpec.getMode(hs), hSize = MeasureSpec.getSize(hs);
        int hPad = getCompoundPaddingLeft() + getCompoundPaddingRight();
        int vPad = getCompoundPaddingTop() + getCompoundPaddingBottom();
        int width;
        if (wMode == MeasureSpec.EXACTLY) width = wSize;
        else {
            CharSequence t = displayText();
            int des = (int) Math.ceil(Layout.getDesiredWidth(t, paint));
            if (hint != null) des = Math.max(des, (int) Math.ceil(Layout.getDesiredWidth(hint, paint)));
            if (editableMode) des += 2;
            width = des + hPad;
            if (ems > 0) width = Math.round(ems * paint.getTextSize()) + hPad;
            if (maxEms > 0) width = Math.min(width, Math.round(maxEms * paint.getTextSize()) + hPad);
            if (minEms > 0) width = Math.max(width, Math.round(minEms * paint.getTextSize()) + hPad);
            width = Math.min(width, maxWidth);
            width = Math.max(width, Math.max(minWidthPx, getSuggestedMinimumWidth()));
            if (compound[1] != null) width = Math.max(width, compound[1].getBounds().width() + mPaddingLeft + mPaddingRight);
            if (compound[3] != null) width = Math.max(width, compound[3].getBounds().width() + mPaddingLeft + mPaddingRight);
            if (wMode == MeasureSpec.AT_MOST) width = Math.min(wSize, width);
        }
        int want = width - hPad;
        if (autoSizeMax > 0) { autoSize(want, hMode == MeasureSpec.EXACTLY ? hSize - vPad : -1); layout = null; }
        ensureLayout(want);
        int height;
        if (hMode == MeasureSpec.EXACTLY) height = hSize;
        else {
            height = desiredHeight() + vPad;
            height = Math.min(height, maxHeight);
            height = Math.max(height, getSuggestedMinimumHeight());
            if (compound[0] != null) height = Math.max(height, compound[0].getBounds().height() + mPaddingTop + mPaddingBottom);
            if (compound[2] != null) height = Math.max(height, compound[2].getBounds().height() + mPaddingTop + mPaddingBottom);
            if (hMode == MeasureSpec.AT_MOST) height = Math.min(hSize, height);
        }
        setMeasuredDimension(width, height);
        clampScroll();
    }

    private int desiredHeight() {
        int h = desiredHeight(layout);
        if (text.length() == 0 && hintLayout != null) h = Math.max(h, desiredHeight(hintLayout));
        return h;
    }

    private int desiredHeight(Layout l) {
        if (l == null) return 0;
        int count = l.getLineCount();
        int h;
        if (maxLines != Integer.MAX_VALUE && count > maxLines) h = l.getLineTop(maxLines) + (includePad ? l.getBottomPadding() : 0);
        else h = l.getHeight();
        if (count < minLines) h += getLineHeight() * (minLines - count);
        return h;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (layout != null && layoutWidth != w - getCompoundPaddingLeft() - getCompoundPaddingRight()) { layout = null; }
    }

    private int boxWidth() { return getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight(); }
    private int boxHeight() { return getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom(); }

    private int verticalOffset() {
        Layout l = text.length() == 0 && hintLayout != null ? hintLayout : layout;
        if (l == null) return 0;
        int v = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (v == Gravity.TOP) return 0;
        int box = boxHeight();
        int th = desiredHeight(l);
        if (th >= box) return 0;
        return v == Gravity.BOTTOM ? box - th : (box - th) / 2;
    }

    /** Desplazamiento de la primera línea alineada (cuando la línea de una sola es más ancha que la caja). */
    private float hAlignShift(Layout l) {
        if (l == null || !oneLineScrolling()) return 0;
        int box = boxWidth();
        if (l.getWidth() <= box) return 0;
        float w = 0;
        for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
        if (w >= box) return 0;
        // El texto cabe pero la capa es más ancha: se alinea en la caja.
        switch (alignment()) {
            case ALIGN_CENTER: return (box - w) / 2f - (l.getWidth() - w) / 2f;
            case ALIGN_OPPOSITE: case ALIGN_RIGHT: return box - w - (l.getWidth() - w);
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- dibujo

    @Override
    protected void onDraw(Canvas c) {
        if (layout == null) ensureLayout(Math.max(0, boxWidth()));
        int left = getCompoundPaddingLeft(), top = getExtendedPaddingTop(), right = getWidth() - getCompoundPaddingRight(), bottom = getHeight() - getExtendedPaddingBottom();
        drawCompound(c);
        c.save();
        c.clipRect(mScrollX + left, mScrollY + top, mScrollX + right, mScrollY + bottom);
        int voff = verticalOffset();
        c.translate(left, top + voff);
        boolean empty = text.length() == 0;
        Layout l = layout;
        if (empty && hintLayout != null) {
            hintLayout.getPaint().setColor(getCurrentHintTextColor());
            c.save();
            c.translate(hAlignShift(hintLayout), 0);
            drawLayoutWithColor(c, hintLayout, getCurrentHintTextColor());
            c.restore();
        }
        float shift = hAlignShift(l);
        c.translate(shift, 0);
        if (isFocused() && editable != null && hasSelection()) {
            Path sel = new Path();
            l.getSelectionPath(getSelectionStart(), getSelectionEnd(), sel);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(highlightColor);
            c.drawPath(sel, hp);
        }
        if (!empty) {
            if (shadowRadius > 0 || shadowDx != 0 || shadowDy != 0) paint.setShadowLayer(Math.max(0.5f, shadowRadius), shadowDx, shadowDy, shadowColor);
            else paint.clearShadowLayer();
            paint.linkColor = linkColor;
            drawLayoutWithColor(c, l, curColor);
            paint.clearShadowLayer();
        }
        if (composing != null && !composing.isEmpty() && isFocused()) drawComposing(c, l);
        if (showCaret()) {
            int off = getSelectionEnd();
            float x = l.getPrimaryHorizontal(off);
            int line = l.getLineForOffset(off);
            Paint cp = new Paint();
            cp.setColor(caretColor());
            float w = Math.max(1f, 1.5f * getResources().getDisplayMetrics().density);
            c.drawRect(x - w / 2, l.getLineTop(line), x + w / 2, l.getLineBottom(line), cp);
        }
        c.restore();
    }

    private int caretColor() { return (curColor & 0xFFFFFF) == 0xFFFFFF || (curColor >>> 24) < 0x40 ? 0xFF2DD4BF : curColor; }

    private void drawLayoutWithColor(Canvas c, Layout l, int color) {
        l.getPaint().setColor(color);
        l.draw(c);
    }

    private void drawComposing(Canvas c, Layout l) {
        int off = getSelectionEnd();
        float x = l.getPrimaryHorizontal(off);
        int line = l.getLineForOffset(off);
        Paint p = new Paint(paint);
        p.setColor(curColor);
        p.setUnderlineText(true);
        float base = l.getLineBaseline(line);
        Paint bg = new Paint();
        bg.setColor(highlightColor);
        float w = p.measureText(composing);
        c.drawRect(x, l.getLineTop(line), x + w, l.getLineBottom(line), bg);
        c.drawText(composing, x, base, p);
    }

    private void drawCompound(Canvas c) {
        int vspace = getHeight() - mPaddingTop - mPaddingBottom, hspace = getWidth() - mPaddingLeft - mPaddingRight;
        if (compound[0] != null) { c.save(); c.translate(mScrollX + mPaddingLeft, mScrollY + mPaddingTop + (vspace - compound[0].getBounds().height()) / 2f); compound[0].draw(c); c.restore(); }
        if (compound[2] != null) { c.save(); c.translate(mScrollX + getWidth() - mPaddingRight - compound[2].getBounds().width(), mScrollY + mPaddingTop + (vspace - compound[2].getBounds().height()) / 2f); compound[2].draw(c); c.restore(); }
        if (compound[1] != null) { c.save(); c.translate(mScrollX + mPaddingLeft + (hspace - compound[1].getBounds().width()) / 2f, mScrollY + mPaddingTop); compound[1].draw(c); c.restore(); }
        if (compound[3] != null) { c.save(); c.translate(mScrollX + mPaddingLeft + (hspace - compound[3].getBounds().width()) / 2f, mScrollY + getHeight() - mPaddingBottom - compound[3].getBounds().height()); compound[3].draw(c); c.restore(); }
    }

    @Override protected boolean verifyDrawable(Drawable who) { for (Drawable d : compound) if (d == who) return true; return super.verifyDrawable(who); }

    // ---------------------------------------------------------------- selección

    public int getSelectionStart() { return Selection.getSelectionStart(text) < 0 ? -1 : Math.min(Selection.getSelectionStart(text), Selection.getSelectionEnd(text)); }
    public int getSelectionEnd() { return Selection.getSelectionEnd(text) < 0 ? -1 : Math.max(Selection.getSelectionStart(text), Selection.getSelectionEnd(text)); }
    private int selAnchor() { return Selection.getSelectionStart(text); }
    private int selCaret() { return Selection.getSelectionEnd(text); }
    public boolean hasSelection() { int s = Selection.getSelectionStart(text), e = Selection.getSelectionEnd(text); return s >= 0 && s != e; }
    public boolean isTextSelectable() { return selectable; }
    public void setTextIsSelectable(boolean s) {
        selectable = s;
        if (s) { setFocusableInTouchMode(true); setFocusable(true); setClickable(true); setLongClickable(true); if (!(text instanceof Spannable)) setText(text, BufferType.SPANNABLE); }
    }
    public void setSelectAllOnFocus(boolean s) { selectAllOnFocus = s; }
    public void setCursorVisible(boolean v) { cursorVisible = v; invalidate(); }
    public boolean isCursorVisible() { return cursorVisible; }
    public void setTextCursorDrawable(Drawable d) {}
    public void setTextCursorDrawable(int id) {}
    public void setTextSelectHandle(Drawable d) {}
    public void setShowSoftInputOnFocus(boolean s) {}
    public final void setLinksClickable(boolean c) { linksClickable = c; }
    public final void setAutoLinkMask(int m) {}
    public void setFreezesText(boolean f) {}
    public void setCustomSelectionActionModeCallback(Object cb) {}
    public void setCustomInsertionActionModeCallback(Object cb) {}

    void select(int anchor, int caret) {
        if (!(text instanceof Spannable)) return;
        Selection.setSelection((Spannable) text, anchor, caret);
        restartBlink();
        bringPointIntoView(caret);
        invalidate();
    }

    public void setSelection(int start, int stop) { if (text instanceof Spannable) select(start, stop); }
    public void setSelection(int index) { setSelection(index, index); }
    public void selectAll() { setSelection(0, text.length()); }
    public void extendSelection(int index) { if (text instanceof Spannable) select(selAnchor() < 0 ? index : selAnchor(), index); }

    public int getOffsetForPosition(float x, float y) {
        if (layout == null) return -1;
        float lx = x - getCompoundPaddingLeft() + mScrollX - hAlignShift(layout);
        float ly = y - getExtendedPaddingTop() - verticalOffset() + mScrollY;
        int line = layout.getLineForVertical((int) Math.max(0, ly));
        return layout.getOffsetForHorizontal(line, lx);
    }

    public boolean bringPointIntoView(int offset) {
        if (layout == null || getWidth() == 0) { pendingBring = offset; return false; }
        offset = Math.max(0, Math.min(offset, text.length()));
        int line = layout.getLineForOffset(offset);
        float x = layout.getPrimaryHorizontal(offset) + hAlignShift(layout);
        int top = layout.getLineTop(line), bottom = layout.getLineBottom(line);
        int box = boxWidth(), boxH = boxHeight();
        int sx = mScrollX, sy = mScrollY;
        float slop = paint.getTextSize() * 0.5f;
        if (layout.getWidth() > box) {
            if (x - sx < 0) sx = (int) Math.max(0, x - slop);
            else if (x - sx > box - 2) sx = (int) Math.min(layout.getWidth() - box, x - box + slop);
        } else sx = 0;
        int th = layout.getHeight();
        if (th > boxH && boxH > 0) {
            if (top - sy < 0) sy = top;
            else if (bottom - sy > boxH) sy = bottom - boxH;
        } else sy = 0;
        boolean changed = sx != mScrollX || sy != mScrollY;
        if (changed) scrollTo(sx, sy);
        // Dentro de un ScrollView: que el padre lo traiga a la vista.
        int px = Math.round(x) + getCompoundPaddingLeft() - mScrollX, py = top + getExtendedPaddingTop() + verticalOffset() - mScrollY;
        Rect r = new Rect(px - 2, py, px + 2, py + (bottom - top));
        requestRectangleOnScreen(r);
        return changed;
    }

    private int pendingBring = -1;

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingBring >= 0) { int p = pendingBring; pendingBring = -1; post(() -> bringPointIntoView(p)); }
    }

    private void clampScroll() {
        if (layout == null) return;
        int maxX = Math.max(0, layout.getWidth() - Math.max(0, getMeasuredWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight()));
        if (!oneLineScrolling()) maxX = 0;
        if (mScrollX > maxX) mScrollX = maxX;
    }

    public boolean moveCursorToVisibleOffset() { return false; }

    // ---------------------------------------------------------------- vigilancia del texto

    public void addTextChangedListener(TextWatcher w) { watchers.add(w); }
    public void removeTextChangedListener(TextWatcher w) { watchers.remove(w); }

    private final class ChangeWatcher implements TextWatcher, SpanWatcher, NoCopySpan {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            for (TextWatcher w : new ArrayList<>(watchers)) w.beforeTextChanged(s, start, count, after);
        }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            layout = null;
            hintLayout = null;
            requestLayout();
            invalidate();
            for (TextWatcher w : new ArrayList<>(watchers)) w.onTextChanged(s, start, before, count);
        }
        @Override public void afterTextChanged(android.text.Editable s) {
            for (TextWatcher w : new ArrayList<>(watchers)) w.afterTextChanged(s);
        }
        @Override public void onSpanAdded(Spannable t, Object what, int s, int e) { spanChanged(what); }
        @Override public void onSpanRemoved(Spannable t, Object what, int s, int e) { spanChanged(what); }
        @Override public void onSpanChanged(Spannable t, Object what, int os, int oe, int ns, int ne) { spanChanged(what); }
    }

    private void spanChanged(Object what) {
        if (what == Selection.SELECTION_START || what == Selection.SELECTION_END) { invalidate(); onSelectionChanged(getSelectionStart(), getSelectionEnd()); return; }
        if (what instanceof NoCopySpan) return;
        layout = null;
        requestLayout();
        invalidate();
    }

    protected void onSelectionChanged(int selStart, int selEnd) {}
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {}

    // ---------------------------------------------------------------- tipo de entrada

    public void setInputType(int type) {
        inputType = type;
        int cls = type & InputType.TYPE_MASK_CLASS, variation = type & InputType.TYPE_MASK_VARIATION;
        password = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean multi = cls == InputType.TYPE_CLASS_TEXT && (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (editableMode) setSingleLine(!multi);
        nullLayouts();
    }
    public void setRawInputType(int type) { inputType = type; }
    public int getInputType() { return inputType; }
    public void setImeOptions(int o) { imeOptions = o; }
    public int getImeOptions() { return imeOptions; }
    public void setImeActionLabel(CharSequence label, int id) {}
    public void setImeHintLocales(Object l) {}
    public void setPrivateImeOptions(String o) {}
    public void setOnEditorActionListener(OnEditorActionListener l) { editorAction = l; }
    public void onEditorAction(int actionCode) {
        if (editorAction != null && editorAction.onEditorAction(this, actionCode, null)) return;
        if (actionCode == EditorInfo.IME_ACTION_NEXT) { View n = focusSearch(FOCUS_FORWARD); if (n != null) n.requestFocus(); }
        else if (actionCode == EditorInfo.IME_ACTION_DONE) clearFocus();
    }
    public void setFilters(InputFilter[] f) { filters = f == null ? new InputFilter[0] : f; if (editable != null) editable.setFilters(filters); }
    public InputFilter[] getFilters() { return filters; }
    public void setKeyListener(KeyListener k) {
        keyListener = k;
        if (k == null && editableMode) { editableMode = false; setFocusableInTouchMode(false); }
        else if (k != null && !editableMode) { editableMode = true; setFocusableInTouchMode(true); setText(text); }
        if (k != null) inputType = k.getInputType();
    }
    public final KeyListener getKeyListener() { return keyListener; }
    public final void setMovementMethod(MovementMethod m) { movement = m; if (m != null && !(text instanceof Spannable)) setText(text, BufferType.SPANNABLE); }
    public final MovementMethod getMovementMethod() { return movement; }
    public final void setTransformationMethod(TransformationMethod m) { transformation = m; password = m instanceof android.text.method.PasswordTransformationMethod; nullLayouts(); }
    public final TransformationMethod getTransformationMethod() { return transformation; }
    public void setEditableFactory(Editable.Factory f) {}
    public void setSpannableFactory(android.text.Spannable.Factory f) {}

    @Override public boolean onCheckIsTextEditor() { return editableMode; }

    private boolean isEditor() { return editableMode && isEnabled() && editable != null; }

    // ---------------------------------------------------------------- foco y cursor

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (gain && editable != null) {
            if (selectAllOnFocus) selectAll();
            else if (getSelectionStart() < 0) Selection.setSelection(editable, editable.length());
            restartBlink();
        } else {
            MainQueue.remove(this, blink, null);
            composing = null;
            if (!gain && editable != null && hasSelection() && !selectable) { /* Android conserva la selección */ }
        }
        invalidate();
    }

    private boolean showCaret() {
        return isFocused() && isEditor() && cursorVisible && blinkOn && !hasSelection() && getSelectionEnd() >= 0 && hasWindowFocus();
    }

    private void restartBlink() {
        blinkOn = true;
        MainQueue.remove(this, blink, null);
        if (isFocused() && isEditor()) MainQueue.post(this, blink, 500, null);
        invalidate();
    }

    private void blink() {
        if (!isFocused() || !isAttachedToWindow()) return;
        blinkOn = !blinkOn;
        invalidate();
        MainQueue.post(this, blink, 500, null);
    }

    @Override
    public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); restartBlink(); }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent e, int i) {
        PointerIcon p = super.onResolvePointerIcon(e, i);
        if (p != null) return p;
        if ((editableMode || selectable) && isEnabled()) return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_TEXT);
        return null;
    }

    // ---------------------------------------------------------------- ratón

    private long lastDown;
    private int clicks;
    private float lastDownX, lastDownY;
    private boolean dragging, downInSelection;
    private int dragAnchor;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean editor = isEditor() || (selectable && text instanceof Spannable);
        if (!editor) {
            if (movement instanceof android.text.method.LinkMovementMethod && text instanceof Spanned && linksClickable) {
                int off = getOffsetForPosition(e.getX(), e.getY());
                ClickableSpan[] links = off >= 0 ? ((Spanned) text).getSpans(off, off, ClickableSpan.class) : new ClickableSpan[0];
                if (links.length > 0) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) links[0].onClick(this);
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }
        int action = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                    if (!isFocused()) requestFocus();
                    showMenu(x, y);
                    return true;
                }
                long now = SystemClock.uptimeMillis();
                boolean near = Math.abs(x - lastDownX) < 8 * getResources().getDisplayMetrics().density && Math.abs(y - lastDownY) < 8 * getResources().getDisplayMetrics().density;
                clicks = now - lastDown < 400 && near ? clicks + 1 : 1;
                lastDown = now;
                lastDownX = x; lastDownY = y;
                if (!isFocused()) requestFocus();
                getParent().requestDisallowInterceptTouchEvent(true);
                int off = getOffsetForPosition(x, y);
                if (off < 0) off = 0;
                boolean shift = (e.getMetaState() & KeyEvent.META_SHIFT_ON) != 0;
                if (clicks == 2) {
                    int[] w = wordAt(off);
                    select(w[0], w[1]);
                    dragAnchor = w[0];
                } else if (clicks >= 3) {
                    int s = off, en = off;
                    while (s > 0 && text.charAt(s - 1) != '\n') s--;
                    while (en < text.length() && text.charAt(en) != '\n') en++;
                    select(s, en);
                    dragAnchor = s;
                } else if (shift && selAnchor() >= 0) {
                    select(selAnchor(), off);
                    dragAnchor = selAnchor();
                } else {
                    select(off, off);
                    dragAnchor = off;
                }
                dragging = false;
                setPressed(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int off = getOffsetForPosition(x, y);
                if (off < 0) return true;
                float slop = 4 * getResources().getDisplayMetrics().density;
                if (!dragging && (Math.abs(x - lastDownX) > slop || Math.abs(y - lastDownY) > slop)) dragging = true;
                if (dragging) {
                    if (clicks == 2) {
                        int[] w = wordAt(off);
                        if (off < dragAnchor) select(Math.max(w[0], 0) == w[0] ? wordAt(dragAnchor)[1] : dragAnchor, w[0]);
                        else select(dragAnchor, w[1]);
                    } else select(dragAnchor, off);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (!dragging && clicks == 1 && hasOnClickListeners()) performClick();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL && layout != null && !oneLineScrolling()) {
            int over = layout.getHeight() - boxHeight();
            if (over > 0) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                int ny = Math.max(0, Math.min(over, mScrollY - Math.round(v * 48 * getResources().getDisplayMetrics().density)));
                if (ny != mScrollY) { scrollTo(mScrollX, ny); return true; }
            }
        }
        return super.onGenericMotionEvent(e);
    }

    private int[] wordAt(int off) {
        int s = off, en = off, n = text.length();
        if (s < n && !Character.isLetterOrDigit(text.charAt(s)) && (s == 0 || !Character.isLetterOrDigit(text.charAt(s - 1)))) return new int[] {s, Math.min(n, s + 1)};
        while (s > 0 && Character.isLetterOrDigit(text.charAt(s - 1))) s--;
        while (en < n && Character.isLetterOrDigit(text.charAt(en))) en++;
        return new int[] {s, en};
    }

    /** Menú del botón derecho: cortar, copiar, pegar, seleccionar todo. */
    private void showMenu(float x, float y) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Runnable> acts = new java.util.ArrayList<>();
        if (hasSelection() && isEditor()) { labels.add("Cortar"); acts.add(() -> onTextContextMenuItem(android.R.id.cut)); }
        if (hasSelection()) { labels.add("Copiar"); acts.add(() -> onTextContextMenuItem(android.R.id.copy)); }
        if (isEditor()) { labels.add("Pegar"); acts.add(() -> onTextContextMenuItem(android.R.id.paste)); }
        labels.add("Seleccionar todo");
        acts.add(this::selectAll);
        PopupMenu.showAt(this, x, y, labels, acts);
    }

    public boolean onTextContextMenuItem(int id) {
        int s = Math.max(0, getSelectionStart()), en = Math.max(0, getSelectionEnd());
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (id == android.R.id.copy || id == android.R.id.cut) {
            if (s == en) return false;
            cm.setPrimaryClip(ClipData.newPlainText(null, text.subSequence(s, en).toString()));
            if (id == android.R.id.cut && isEditor()) edit(s, en, "");
            return true;
        }
        if (id == android.R.id.paste && isEditor()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;
            CharSequence t = clip.getItemAt(0).coerceToText(getContext());
            if (singleLine) t = t.toString().replace("\r\n", " ").replace('\n', ' ');
            edit(s, en, t.toString());
            return true;
        }
        if (id == android.R.id.selectAll) { selectAll(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- escribir

    private static final class Snapshot {
        final CharSequence text;
        final int anchor, caret;
        Snapshot(CharSequence t, int a, int c) { text = t; anchor = a; caret = c; }
    }

    private final ArrayList<Snapshot> undo = new ArrayList<>(), redo = new ArrayList<>();
    private long lastEditTime;
    private boolean lastWasTyping;
    private String composing;

    private void snapshot(boolean typing) {
        long now = SystemClock.uptimeMillis();
        // Las letras seguidas se deshacen juntas, como en Android.
        if (typing && lastWasTyping && now - lastEditTime < 1500 && !undo.isEmpty()) { lastEditTime = now; return; }
        undo.add(new Snapshot(new SpannableStringBuilder(editable), selAnchor(), selCaret()));
        if (undo.size() > 200) undo.remove(0);
        redo.clear();
        lastEditTime = now;
        lastWasTyping = typing;
    }

    private void restore(Snapshot s) {
        // Se cambia el contenido del mismo Editable: quien lo tenga sigue viéndolo.
        SpannableStringBuilder copy = new SpannableStringBuilder(s.text);
        editable.removeSpan(changeWatcher);
        int oldLen = editable.length();
        for (TextWatcher w : new ArrayList<>(watchers)) w.beforeTextChanged(editable, 0, oldLen, copy.length());
        editable.replace(0, editable.length(), copy);
        for (Object sp : editable.getSpans(0, editable.length(), Object.class)) if (!(sp instanceof NoCopySpan)) editable.removeSpan(sp);
        for (Object sp : copy.getSpans(0, copy.length(), Object.class)) {
            if (sp instanceof NoCopySpan) continue;
            editable.setSpan(sp, copy.getSpanStart(sp), copy.getSpanEnd(sp), copy.getSpanFlags(sp));
        }
        editable.setSpan(changeWatcher, 0, editable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE | (100 << Spanned.SPAN_PRIORITY_SHIFT));
        layout = null;
        requestLayout();
        for (TextWatcher w : new ArrayList<>(watchers)) w.onTextChanged(editable, 0, oldLen, editable.length());
        for (TextWatcher w : new ArrayList<>(watchers)) w.afterTextChanged(editable);
        select(Math.min(s.anchor, editable.length()), Math.min(s.caret, editable.length()));
    }

    private void edit(int s, int e, String with) {
        if (editable == null) return;
        snapshot(false);
        int a = Math.min(s, e), b = Math.max(s, e);
        editable.replace(a, b, with);
        int caret = Math.min(editable.length(), a + with.length());
        select(caret, caret);
        lastWasTyping = false;
    }

    @Override
    public void commitText(String s) {
        if (!isEditor()) return;
        composing = null;
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
            StringBuilder ok = new StringBuilder();
            for (char ch : s.toCharArray()) {
                if (Character.isDigit(ch)) ok.append(ch);
                else if (cls == InputType.TYPE_CLASS_PHONE && "+-()*# ".indexOf(ch) >= 0) ok.append(ch);
                else if ((inputType & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 && (ch == '.' || ch == ',')) ok.append(ch);
                else if ((inputType & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0 && ch == '-') ok.append(ch);
            }
            s = ok.toString();
            if (s.isEmpty()) return;
        }
        if (keyListener instanceof android.text.method.DigitsKeyListener) s = ((android.text.method.DigitsKeyListener) keyListener).accept(s);
        if (s.isEmpty()) return;
        snapshot(true);
        int a = Math.max(0, getSelectionStart()), b = Math.max(0, getSelectionEnd());
        editable.replace(a, b, s);
        int caret = Math.min(editable.length(), a + s.length());
        Selection.setSelection(editable, caret);
        restartBlink();
        bringPointIntoView(caret);
    }

    @Override
    public void setComposing(String s) { composing = s == null || s.isEmpty() ? null : s; invalidate(); }

    @Override
    public RectF caretBounds() {
        if (layout == null) return new RectF(0, 0, 1, paint.getTextSize());
        int off = Math.max(0, getSelectionEnd());
        int line = layout.getLineForOffset(off);
        float x = layout.getPrimaryHorizontal(off) + getCompoundPaddingLeft() - mScrollX + hAlignShift(layout);
        float top = layout.getLineTop(line) + getExtendedPaddingTop() + verticalOffset() - mScrollY;
        return new RectF(x, top, x + 1, top + layout.getLineBottom(line) - layout.getLineTop(line));
    }

    private boolean multiLine() { return !singleLine && (maxLines != 1); }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (!isEditor() && !(selectable && text instanceof Spannable)) return super.onKeyDown(code, e);
        boolean ctrl = e.isCtrlPressed(), shift = e.isShiftPressed();
        int len = text.length();
        int anchor = Math.max(0, selAnchor()), caret = Math.max(0, selCaret());
        boolean editor = isEditor();
        if (ctrl) {
            switch (code) {
                case KeyEvent.KEYCODE_A: selectAll(); return true;
                case KeyEvent.KEYCODE_C: return onTextContextMenuItem(android.R.id.copy);
                case KeyEvent.KEYCODE_X: return editor && onTextContextMenuItem(android.R.id.cut);
                case KeyEvent.KEYCODE_V: return editor && onTextContextMenuItem(android.R.id.paste);
                case KeyEvent.KEYCODE_Z:
                    if (!editor) return false;
                    if (shift) { if (!redo.isEmpty()) { undo.add(new Snapshot(new SpannableStringBuilder(editable), selAnchor(), selCaret())); restore(redo.remove(redo.size() - 1)); } }
                    else if (!undo.isEmpty()) { redo.add(new Snapshot(new SpannableStringBuilder(editable), selAnchor(), selCaret())); restore(undo.remove(undo.size() - 1)); }
                    lastWasTyping = false;
                    return true;
                case KeyEvent.KEYCODE_Y:
                    if (editor && !redo.isEmpty()) { undo.add(new Snapshot(new SpannableStringBuilder(editable), selAnchor(), selCaret())); restore(redo.remove(redo.size() - 1)); }
                    return editor;
                default:
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                int to;
                if (hasSelection() && !shift) to = getSelectionStart();
                else to = ctrl ? wordLeft(caret) : layoutLeft(caret);
                select(shift ? anchor : to, to);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int to;
                if (hasSelection() && !shift) to = getSelectionEnd();
                else to = ctrl ? wordRight(caret) : layoutRight(caret);
                select(shift ? anchor : to, to);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (layout == null || !multiLine()) return false;
                int line = layout.getLineForOffset(caret);
                int target = line + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (target < 0 || target >= layout.getLineCount()) {
                    int to = target < 0 ? 0 : len;
                    if (to == caret && !shift) return false;
                    select(shift ? anchor : to, to);
                    return true;
                }
                if (Float.isNaN(wantX)) wantX = layout.getPrimaryHorizontal(caret);
                float keep = wantX;
                int to = layout.getOffsetForHorizontal(target, keep);
                select(shift ? anchor : to, to);
                wantX = keep;
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_HOME: {
                int to = ctrl || layout == null ? 0 : layout.getLineStart(layout.getLineForOffset(caret));
                select(shift ? anchor : to, to);
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_END: {
                int to;
                if (ctrl || layout == null) to = len;
                else {
                    int line = layout.getLineForOffset(caret);
                    to = layout.getLineEnd(line);
                    if (to > layout.getLineStart(line) && to <= len && to > 0 && (text.charAt(to - 1) == '\n' || (line < layout.getLineCount() - 1 && text.charAt(to - 1) == ' '))) to--;
                }
                select(shift ? anchor : to, to);
                return true;
            }
            case KeyEvent.KEYCODE_DEL: {
                if (!editor) return false;
                if (hasSelection()) { edit(getSelectionStart(), getSelectionEnd(), ""); return true; }
                if (caret == 0) return false;
                int from = ctrl ? wordLeft(caret) : prevChar(caret);
                snapshot(true);
                editable.delete(from, caret);
                select(from, from);
                return true;
            }
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                if (!editor) return false;
                if (hasSelection()) { edit(getSelectionStart(), getSelectionEnd(), ""); return true; }
                if (caret >= len) return false;
                int to = ctrl ? wordRight(caret) : nextChar(caret);
                snapshot(true);
                editable.delete(caret, to);
                select(caret, caret);
                return true;
            }
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                if (!editor) return false;
                if (multiLine() && !(editorAction != null && (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && singleLine)) {
                    commitText("\n");
                    return true;
                }
                int action = imeOptions & EditorInfo.IME_MASK_ACTION;
                if (editorAction != null && editorAction.onEditorAction(this, action == 0 ? EditorInfo.IME_NULL : action, e)) return true;
                if (action == EditorInfo.IME_ACTION_NEXT) { View n = focusSearch(FOCUS_FORWARD); if (n != null) n.requestFocus(); return true; }
                return false;
            }
            case KeyEvent.KEYCODE_TAB: {
                View n = focusSearch(shift ? FOCUS_BACKWARD : FOCUS_FORWARD);
                if (n != null && n != this) { n.requestFocus(); return true; }
                return false;
            }
            default:
                return super.onKeyDown(code, e);
        }
    }

    private float wantX = Float.NaN;

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) wantX = Float.NaN;
        return super.onKeyUp(code, e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP && e.getKeyCode() != KeyEvent.KEYCODE_DPAD_DOWN) wantX = Float.NaN;
        return super.dispatchKeyEvent(e);
    }

    private int prevChar(int o) { return o >= 2 && Character.isLowSurrogate(text.charAt(o - 1)) && Character.isHighSurrogate(text.charAt(o - 2)) ? o - 2 : Math.max(0, o - 1); }
    private int nextChar(int o) { return o + 1 < text.length() && Character.isHighSurrogate(text.charAt(o)) && Character.isLowSurrogate(text.charAt(o + 1)) ? o + 2 : Math.min(text.length(), o + 1); }
    private int layoutLeft(int o) { return prevChar(o); }
    private int layoutRight(int o) { return nextChar(o); }
    private int wordLeft(int o) {
        while (o > 0 && !Character.isLetterOrDigit(text.charAt(o - 1))) o--;
        while (o > 0 && Character.isLetterOrDigit(text.charAt(o - 1))) o--;
        return o;
    }
    private int wordRight(int o) {
        int n = text.length();
        while (o < n && !Character.isLetterOrDigit(text.charAt(o))) o++;
        while (o < n && Character.isLetterOrDigit(text.charAt(o))) o++;
        return o;
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); MainQueue.remove(this, blink, null); }

    // ---------------------------------------------------------------- desplazamiento

    @Override protected int computeHorizontalScrollRange() { return layout == null ? getWidth() : layout.getWidth() + getCompoundPaddingLeft() + getCompoundPaddingRight(); }
    @Override protected int computeVerticalScrollRange() { return layout == null ? getHeight() : layout.getHeight() + getExtendedPaddingTop() + getExtendedPaddingBottom(); }
    public void setScroller(android.widget.Scroller s) {}
}
