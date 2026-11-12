package android.widget;

import android.content.Context;

public class SeekBar extends AbsSeekBar {
    public interface OnSeekBarChangeListener {
        void onProgressChanged(SeekBar s, int progress, boolean fromUser);
        void onStartTrackingTouch(SeekBar s);
        void onStopTrackingTouch(SeekBar s);
    }

    private OnSeekBarChangeListener listener;

    public SeekBar(Context c) { super(c); }
    public SeekBar(Context c, android.util.AttributeSet a) { super(c); }
    public SeekBar(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) { listener = l; }
    @Override void onProgressRefresh(int p, boolean fromUser) { if (listener != null) listener.onProgressChanged(this, p, fromUser); }
    @Override void onStartTrackingTouch() { if (listener != null) listener.onStartTrackingTouch(this); }
    @Override void onStopTrackingTouch() { if (listener != null) listener.onStopTrackingTouch(this); }
}
