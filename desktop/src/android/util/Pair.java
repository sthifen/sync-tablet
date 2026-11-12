package android.util;

public class Pair<F, S> {
    public final F first;
    public final S second;
    public Pair(F f, S s) { first = f; second = s; }
    public static <A, B> Pair<A, B> create(A a, B b) { return new Pair<>(a, b); }
    @Override public boolean equals(Object o) { return o instanceof Pair && java.util.Objects.equals(((Pair<?, ?>) o).first, first) && java.util.Objects.equals(((Pair<?, ?>) o).second, second); }
    @Override public int hashCode() { return java.util.Objects.hash(first, second); }
}
