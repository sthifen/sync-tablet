package android.os;

import java.util.Locale;

public final class LocaleList {
    private final Locale[] list;
    public LocaleList(Locale... l) { list = l; }
    public static LocaleList getDefault() { return new LocaleList(Locale.getDefault()); }
    public static LocaleList getAdjustedDefault() { return getDefault(); }
    public Locale get(int i) { return list[i]; }
    public int size() { return list.length; }
    public boolean isEmpty() { return list.length == 0; }
    public String toLanguageTags() { StringBuilder b = new StringBuilder(); for (Locale l : list) { if (b.length() > 0) b.append(','); b.append(l.toLanguageTag()); } return b.toString(); }
}
