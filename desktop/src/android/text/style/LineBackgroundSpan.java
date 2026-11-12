package android.text.style;

import android.graphics.Canvas;
import android.graphics.Paint;

public interface LineBackgroundSpan extends ParagraphStyle {
    void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom, CharSequence text, int start, int end, int lineNumber);
}
