package android.view.inputmethod;

public interface InputConnection {
    boolean commitText(CharSequence text, int newCursorPosition);
    boolean deleteSurroundingText(int before, int after);
    boolean sendKeyEvent(android.view.KeyEvent e);
    boolean finishComposingText();
    boolean setComposingText(CharSequence text, int newCursorPosition);
}
