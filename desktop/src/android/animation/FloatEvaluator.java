package android.animation;

public class FloatEvaluator implements TypeEvaluator<Number> {
    @Override public Float evaluate(float f, Number s, Number e) { float a = s.floatValue(); return a + f * (e.floatValue() - a); }
}
