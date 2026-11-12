package android.animation;

public class IntEvaluator implements TypeEvaluator<Integer> {
    @Override public Integer evaluate(float f, Integer s, Integer e) { return (int) (s + f * (e - s)); }
}
