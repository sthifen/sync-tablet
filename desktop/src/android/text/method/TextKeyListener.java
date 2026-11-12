package android.text.method;

public class TextKeyListener implements KeyListener {
    public enum Capitalize { NONE, SENTENCES, WORDS, CHARACTERS }
    public static TextKeyListener getInstance() { return new TextKeyListener(); }
    public static TextKeyListener getInstance(boolean autotext, Capitalize cap) { return new TextKeyListener(); }
    @Override public int getInputType() { return android.text.InputType.TYPE_CLASS_TEXT; }
}
