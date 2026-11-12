package android.graphics;

public class PorterDuff {
    public enum Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY }

    static java.awt.Composite composite(Mode m, float alpha) {
        int rule;
        switch (m) {
            case CLEAR: rule = java.awt.AlphaComposite.CLEAR; break;
            case SRC: rule = java.awt.AlphaComposite.SRC; break;
            case DST: rule = java.awt.AlphaComposite.DST; break;
            case DST_OVER: rule = java.awt.AlphaComposite.DST_OVER; break;
            case SRC_IN: rule = java.awt.AlphaComposite.SRC_IN; break;
            case DST_IN: rule = java.awt.AlphaComposite.DST_IN; break;
            case SRC_OUT: rule = java.awt.AlphaComposite.SRC_OUT; break;
            case DST_OUT: rule = java.awt.AlphaComposite.DST_OUT; break;
            case SRC_ATOP: rule = java.awt.AlphaComposite.SRC_ATOP; break;
            case DST_ATOP: rule = java.awt.AlphaComposite.DST_ATOP; break;
            case XOR: rule = java.awt.AlphaComposite.XOR; break;
            default: rule = java.awt.AlphaComposite.SRC_OVER;
        }
        return java.awt.AlphaComposite.getInstance(rule, alpha);
    }
}
