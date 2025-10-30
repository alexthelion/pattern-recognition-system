package hashita.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for time conversions
 * Provides Israeli time formatting for alerts
 *
 * ✅ FIXED: Returns empty strings instead of null to prevent NPE with Map.of()
 */
public class TimeUtils {

    private static final ZoneId ISRAEL_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    // Formatters
    private static final DateTimeFormatter IL_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter IL_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter IL_FULL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * Convert UTC timestamp to Israeli time (HH:mm format)
     *
     * @param utcTimestamp UTC timestamp
     * @return Israeli time string (e.g., "01:55") or empty string if null
     */
    public static String toIsraeliTime(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return "";  // ✅ FIXED: Return empty string instead of null
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        return israelTime.format(IL_TIME_FORMATTER);
    }

    /**
     * Convert UTC timestamp to Israeli datetime (yyyy-MM-dd HH:mm:ss format)
     *
     * @param utcTimestamp UTC timestamp
     * @return Israeli datetime string (e.g., "2025-10-29 01:55:00") or empty string if null
     */
    public static String toIsraeliDateTime(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return "";  // ✅ FIXED: Return empty string instead of null
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        return israelTime.format(IL_DATETIME_FORMATTER);
    }

    /**
     * Convert UTC timestamp to Israeli ISO timestamp
     *
     * @param utcTimestamp UTC timestamp
     * @return Israeli ISO timestamp (e.g., "2025-10-29T01:55:00+02:00") or empty string if null
     */
    public static String toIsraeliISO(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return "";  // ✅ FIXED: Return empty string instead of null
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        return israelTime.format(IL_FULL_FORMATTER);
    }

    /**
     * Get timezone offset for Israel at a given time
     *
     * @param utcTimestamp UTC timestamp
     * @return Timezone offset (e.g., "+02:00" or "+03:00" during DST)
     */
    public static String getIsraeliOffset(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return "+02:00";  // ✅ FIXED: Return default instead of null
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        return israelTime.getOffset().toString();
    }

    /**
     * Check if given time is during Israeli market hours
     * (Israeli market: 10:00 - 16:45 Israel time)
     *
     * @param utcTimestamp UTC timestamp
     * @return true if during Israeli market hours
     */
    public static boolean isDuringIsraeliMarketHours(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return false;
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        int hour = israelTime.getHour();
        int minute = israelTime.getMinute();

        // Market hours: 10:00 - 16:45
        if (hour < 10 || hour > 16) {
            return false;
        }
        if (hour == 16 && minute > 45) {
            return false;
        }

        return true;
    }

    /**
     * Check if given time is during US market hours in Israeli time
     * (US market: 16:30 - 23:00 Israel time, or 17:30 - 00:00 during DST)
     *
     * @param utcTimestamp UTC timestamp
     * @return true if during US market hours
     */
    public static boolean isDuringUSMarketHoursIsraelTime(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            return false;
        }

        ZonedDateTime israelTime = utcTimestamp.atZone(ISRAEL_ZONE);
        int hour = israelTime.getHour();
        int minute = israelTime.getMinute();

        // US market opens at 9:30 AM ET = 16:30 or 17:30 Israel time (depends on DST)
        // US market closes at 4:00 PM ET = 23:00 or 00:00 Israel time

        // Simplified: Check if between 16:00 and 23:59 (covers both DST scenarios)
        if (hour >= 16 && hour <= 23) {
            return true;
        }
        if (hour == 0 && minute == 0) {
            return true; // Midnight edge case
        }

        return false;
    }
}