package android.text.format;

public class DateFormat {
    public static boolean is24HourFormat(android.content.Context c) { return true; }
    public static CharSequence format(CharSequence fmt, long millis) { return new java.text.SimpleDateFormat(fmt.toString(), new java.util.Locale("es")).format(new java.util.Date(millis)); }
    public static CharSequence format(CharSequence fmt, java.util.Date d) { return format(fmt, d.getTime()); }
    public static java.text.DateFormat getDateFormat(android.content.Context c) { return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, new java.util.Locale("es")); }
    public static java.text.DateFormat getTimeFormat(android.content.Context c) { return new java.text.SimpleDateFormat("H:mm"); }
}
