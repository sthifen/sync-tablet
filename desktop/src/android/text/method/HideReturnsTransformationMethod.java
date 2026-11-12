package android.text.method;

public class HideReturnsTransformationMethod implements TransformationMethod {
    public static HideReturnsTransformationMethod getInstance() { return new HideReturnsTransformationMethod(); }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) { return s; }
    @Override public void onFocusChanged(android.view.View v, CharSequence s, boolean f, int d, android.graphics.Rect r) {}
}
