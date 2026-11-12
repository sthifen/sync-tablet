package android.text.format;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Fechas relativas en español, como las dice Android ("hace 5 minutos", "ayer"). */
public class DateUtils {
    public static final long SECOND_IN_MILLIS = 1000, MINUTE_IN_MILLIS = 60000, HOUR_IN_MILLIS = 3600000, DAY_IN_MILLIS = 86400000,
            WEEK_IN_MILLIS = 604800000, YEAR_IN_MILLIS = 31449600000L;
    public static final int FORMAT_SHOW_TIME = 1, FORMAT_SHOW_WEEKDAY = 2, FORMAT_SHOW_YEAR = 4, FORMAT_NO_YEAR = 8, FORMAT_SHOW_DATE = 16,
            FORMAT_NO_MONTH_DAY = 32, FORMAT_NO_NOON = 512, FORMAT_NO_MIDNIGHT = 2048, FORMAT_ABBREV_TIME = 16384, FORMAT_ABBREV_WEEKDAY = 32768,
            FORMAT_ABBREV_MONTH = 65536, FORMAT_NUMERIC_DATE = 131072, FORMAT_ABBREV_RELATIVE = 262144, FORMAT_ABBREV_ALL = 524288, FORMAT_24HOUR = 128;

    private static final Locale ES = new Locale("es");

    public static CharSequence getRelativeTimeSpanString(long time) { return getRelativeTimeSpanString(time, System.currentTimeMillis(), MINUTE_IN_MILLIS); }
    public static CharSequence getRelativeTimeSpanString(long time, long now, long minResolution) { return getRelativeTimeSpanString(time, now, minResolution, FORMAT_ABBREV_MONTH); }

    public static CharSequence getRelativeTimeSpanString(long time, long now, long minResolution, int flags) {
        boolean abbrev = (flags & (FORMAT_ABBREV_RELATIVE | FORMAT_ABBREV_ALL)) != 0;
        boolean past = now >= time;
        long duration = Math.abs(now - time);
        if (duration < MINUTE_IN_MILLIS && minResolution < MINUTE_IN_MILLIS) {
            long n = duration / SECOND_IN_MILLIS;
            return rel(past, n, abbrev ? "s" : n == 1 ? "segundo" : "segundos");
        }
        if (duration < HOUR_IN_MILLIS && minResolution < HOUR_IN_MILLIS) {
            long n = duration / MINUTE_IN_MILLIS;
            if (n == 0) return past ? "hace 0 minutos" : "dentro de 0 minutos";
            return rel(past, n, abbrev ? "min" : n == 1 ? "minuto" : "minutos");
        }
        if (duration < DAY_IN_MILLIS && minResolution < DAY_IN_MILLIS) {
            long n = duration / HOUR_IN_MILLIS;
            return rel(past, n, abbrev ? "h" : n == 1 ? "hora" : "horas");
        }
        if (duration < WEEK_IN_MILLIS && minResolution < WEEK_IN_MILLIS) {
            long days = dayDistance(time, now);
            if (days == 1) return past ? "ayer" : "mañana";
            if (days == 2) return past ? "anteayer" : "pasado mañana";
            return rel(past, days, days == 1 ? "día" : "días");
        }
        return formatDateRange(time, flags);
    }

    private static String rel(boolean past, long n, String unit) { return past ? "hace " + n + " " + unit : "dentro de " + n + " " + unit; }

    private static long dayDistance(long a, long b) {
        Calendar ca = Calendar.getInstance(), cb = Calendar.getInstance();
        ca.setTimeInMillis(Math.min(a, b)); cb.setTimeInMillis(Math.max(a, b));
        long days = 0;
        while (ca.get(Calendar.YEAR) != cb.get(Calendar.YEAR) || ca.get(Calendar.DAY_OF_YEAR) != cb.get(Calendar.DAY_OF_YEAR)) { ca.add(Calendar.DAY_OF_YEAR, 1); days++; if (days > 400) break; }
        return days;
    }

    private static String formatDateRange(long time, int flags) {
        Calendar c = Calendar.getInstance(), now = Calendar.getInstance();
        c.setTimeInMillis(time);
        boolean sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR);
        String pattern = sameYear ? "d MMM" : "d MMM yyyy";
        return new SimpleDateFormat(pattern, ES).format(new Date(time)).replace(".", "");
    }

    public static String formatDateTime(android.content.Context c, long millis, int flags) {
        StringBuilder p = new StringBuilder();
        if ((flags & FORMAT_SHOW_WEEKDAY) != 0) p.append((flags & (FORMAT_ABBREV_WEEKDAY | FORMAT_ABBREV_ALL)) != 0 ? "EEE, " : "EEEE, ");
        boolean date = (flags & FORMAT_SHOW_DATE) != 0 || (flags & FORMAT_SHOW_TIME) == 0;
        if (date) {
            if ((flags & FORMAT_NUMERIC_DATE) != 0) p.append("d/M");
            else p.append((flags & (FORMAT_ABBREV_MONTH | FORMAT_ABBREV_ALL)) != 0 ? "d MMM" : "d 'de' MMMM");
            if ((flags & FORMAT_SHOW_YEAR) != 0) p.append((flags & FORMAT_NUMERIC_DATE) != 0 ? "/yyyy" : " 'de' yyyy");
        }
        if ((flags & FORMAT_SHOW_TIME) != 0) { if (p.length() > 0) p.append(", "); p.append("H:mm"); }
        return new SimpleDateFormat(p.toString(), ES).format(new Date(millis)).replace(".", "");
    }

    public static boolean isToday(long when) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(when);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    public static String formatElapsedTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
