package android.text;

public final class TextDirectionHeuristics {
    public static final TextDirectionHeuristic LTR = (cs, s, c) -> false, RTL = (cs, s, c) -> true, FIRSTSTRONG_LTR = LTR, FIRSTSTRONG_RTL = LTR, ANYRTL_LTR = LTR, LOCALE = LTR;
    private TextDirectionHeuristics() {}
}
