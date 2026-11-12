package android.animation;

import java.lang.reflect.Method;

public final class ObjectAnimator extends ValueAnimator {
    private Object target;
    private String property;
    private android.util.Property<Object, Float> prop;
    private boolean intValues;

    public static ObjectAnimator ofFloat(Object target, String property, float... values) {
        ObjectAnimator a = new ObjectAnimator();
        a.target = target; a.property = property; a.setFloatValues(values);
        a.hook();
        return a;
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectAnimator ofFloat(T target, android.util.Property<T, Float> p, float... values) {
        ObjectAnimator a = new ObjectAnimator();
        a.target = target; a.prop = (android.util.Property<Object, Float>) (Object) p; a.setFloatValues(values);
        a.hook();
        return a;
    }

    public static ObjectAnimator ofInt(Object target, String property, int... values) {
        ObjectAnimator a = new ObjectAnimator();
        a.target = target; a.property = property; a.setIntValues(values); a.intValues = true;
        a.hook();
        return a;
    }

    public static ObjectAnimator ofArgb(Object target, String property, int... values) {
        ObjectAnimator a = ofInt(target, property, values);
        a.setEvaluator(ArgbEvaluator.getInstance());
        return a;
    }

    public static ObjectAnimator ofPropertyValuesHolder(Object target, PropertyValuesHolder... h) {
        ObjectAnimator a = new ObjectAnimator();
        a.target = target;
        if (h.length > 0) { a.property = h[0].name; a.setFloatValues(h[0].floats); }
        a.hook();
        return a;
    }

    @Override public void setTarget(Object t) { target = t; }
    public void setPropertyName(String n) { property = n; }
    @Override public ObjectAnimator setDuration(long d) { super.setDuration(d); return this; }
    public void setAutoCancel(boolean c) {}

    private void hook() {
        addUpdateListener(a -> {
            Object v = a.getAnimatedValue();
            if (prop != null) { prop.set(target, (Float) v); return; }
            if (target == null || property == null) return;
            String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            try {
                Method m = intValues ? target.getClass().getMethod(setter, int.class) : target.getClass().getMethod(setter, float.class);
                m.invoke(target, v);
            } catch (Exception e) {
                android.util.Log.w("SyncTablet", "ObjectAnimator: " + setter, e);
            }
        });
    }
}
