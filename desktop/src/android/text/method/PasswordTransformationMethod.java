package android.text.method;

public class PasswordTransformationMethod implements TransformationMethod {
    public static PasswordTransformationMethod getInstance() { return new PasswordTransformationMethod(); }
    @Override public CharSequence getTransformation(CharSequence s, android.view.View v) { return s; }
    @Override public void onFocusChanged(android.view.View v, CharSequence s, boolean f, int d, android.graphics.Rect r) {}
}
