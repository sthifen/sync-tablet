package android.content.res;

public class ColorStateList {
    private final int[][] states;
    private final int[] colors;
    private final int def;

    public ColorStateList(int[][] states, int[] colors) {
        this.states = states;
        this.colors = colors;
        int d = colors.length > 0 ? colors[colors.length - 1] : 0;
        for (int i = 0; i < states.length; i++) if (states[i].length == 0) { d = colors[i]; break; }
        def = d;
    }

    public static ColorStateList valueOf(int color) { return new ColorStateList(new int[][] {new int[0]}, new int[] {color}); }
    public int getDefaultColor() { return def; }
    public boolean isStateful() { return states.length > 1 || (states.length == 1 && states[0].length > 0); }
    public boolean isOpaque() { for (int c : colors) if ((c >>> 24) != 0xFF) return false; return true; }
    public ColorStateList withAlpha(int a) {
        int[] cs = colors.clone();
        for (int i = 0; i < cs.length; i++) cs[i] = (a << 24) | (cs[i] & 0xFFFFFF);
        return new ColorStateList(states, cs);
    }

    public int getColorForState(int[] stateSet, int defaultColor) {
        if (stateSet == null) return def;
        for (int i = 0; i < states.length; i++) {
            boolean ok = true;
            for (int s : states[i]) {
                boolean want = s > 0, has = false;
                for (int x : stateSet) if (x == Math.abs(s)) { has = true; break; }
                if (has != want) { ok = false; break; }
            }
            if (ok) return colors[i];
        }
        return defaultColor;
    }
}
