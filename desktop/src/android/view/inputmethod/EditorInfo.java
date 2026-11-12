package android.view.inputmethod;

public class EditorInfo {
    public static final int IME_ACTION_UNSPECIFIED = 0, IME_ACTION_NONE = 1, IME_ACTION_GO = 2, IME_ACTION_SEARCH = 3, IME_ACTION_SEND = 4,
            IME_ACTION_NEXT = 5, IME_ACTION_DONE = 6, IME_ACTION_PREVIOUS = 7, IME_MASK_ACTION = 0xff, IME_FLAG_NO_EXTRACT_UI = 0x10000000,
            IME_FLAG_NO_FULLSCREEN = 0x2000000, IME_FLAG_NO_ENTER_ACTION = 0x40000000, IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000, IME_NULL = 0;
    public int inputType, imeOptions;
    public String packageName;
    public CharSequence hintText;
}
