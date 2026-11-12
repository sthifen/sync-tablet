package android.text.style;

import android.text.Layout;

public interface AlignmentSpan extends ParagraphStyle {
    Layout.Alignment getAlignment();

    class Standard implements AlignmentSpan, ParcelableSpan {
        private final Layout.Alignment alignment;
        public Standard(Layout.Alignment a) { alignment = a; }
        @Override public Layout.Alignment getAlignment() { return alignment; }
        @Override public int getSpanTypeId() { return 1; }
    }
}
