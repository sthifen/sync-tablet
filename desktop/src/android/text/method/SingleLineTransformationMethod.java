package android.text.method;

public class SingleLineTransformationMethod implements TransformationMethod {
    public static SingleLineTransformationMethod getInstance() { return new SingleLineTransformationMethod(); }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) { return s.toString().replace('\n', ' '); }
    @Override public void onFocusChanged(android.view.View v, CharSequence s, boolean f, int d, android.graphics.Rect r) {}
}
