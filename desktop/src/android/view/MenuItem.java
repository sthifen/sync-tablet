package android.view;

public interface MenuItem {
    int SHOW_AS_ACTION_NEVER = 0, SHOW_AS_ACTION_IF_ROOM = 1, SHOW_AS_ACTION_ALWAYS = 2;
    interface OnMenuItemClickListener { boolean onMenuItemClick(MenuItem item); }
    int getItemId();
    CharSequence getTitle();
    MenuItem setOnMenuItemClickListener(OnMenuItemClickListener l);
    MenuItem setShowAsAction(int a);
}
