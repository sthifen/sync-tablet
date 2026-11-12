package android.animation;

public class ArgbEvaluator implements TypeEvaluator<Object> {
    private static final ArgbEvaluator INSTANCE = new ArgbEvaluator();
    public static ArgbEvaluator getInstance() { return INSTANCE; }
    @Override
    public Object evaluate(float f, Object start, Object end) {
        int s = (Integer) start, e = (Integer) end;
        float sa = (s >>> 24) / 255f, sr = ((s >> 16) & 0xff) / 255f, sg = ((s >> 8) & 0xff) / 255f, sb = (s & 0xff) / 255f;
        float ea = (e >>> 24) / 255f, er = ((e >> 16) & 0xff) / 255f, eg = ((e >> 8) & 0xff) / 255f, eb = (e & 0xff) / 255f;
        sr = (float) Math.pow(sr, 2.2); sg = (float) Math.pow(sg, 2.2); sb = (float) Math.pow(sb, 2.2);
        er = (float) Math.pow(er, 2.2); eg = (float) Math.pow(eg, 2.2); eb = (float) Math.pow(eb, 2.2);
        float a = sa + f * (ea - sa), r = sr + f * (er - sr), g = sg + f * (eg - sg), b = sb + f * (eb - sb);
        a *= 255f; r = (float) Math.pow(r, 1 / 2.2) * 255f; g = (float) Math.pow(g, 1 / 2.2) * 255f; b = (float) Math.pow(b, 1 / 2.2) * 255f;
        return Math.round(a) << 24 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
    }
}
