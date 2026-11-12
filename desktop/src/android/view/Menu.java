package android.view;

public interface Menu {
    int NONE = 0, FIRST = 1;
    MenuItem add(CharSequence title);
    MenuItem add(int groupId, int itemId, int order, CharSequence title);
    int size();
    MenuItem getItem(int i);
    void clear();
}
