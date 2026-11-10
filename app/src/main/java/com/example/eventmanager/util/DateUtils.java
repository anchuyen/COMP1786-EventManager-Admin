package com.example.eventmanager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Helper methods for validating, converting and formatting event dates.
 * <p>
 * Dates are typed by the user as text (dd/MM/yyyy), stored as a long
 * (milliseconds) and displayed in a friendly format such as "Fri, 25 Dec 2026".
 */
public final class DateUtils {

    /** Format the user types or sees in the date field. */
    public static final String INPUT_PATTERN = "dd/MM/yyyy";

    /** Friendly format used in the event list. */
    private static final String DISPLAY_PATTERN = "EEE, d MMM yyyy";

    /** Exactly 2 digits / 2 digits / 4 digits, e.g. 05/09/2026. */
    private static final String INPUT_REGEX = "\\d{2}/\\d{2}/\\d{4}";

    /** Reasonable range of years for an event. */
    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;

    /** Private constructor: utility class, should not be instantiated. */
    private DateUtils() {
    }

    /**
     * Converts text in the format dd/MM/yyyy into milliseconds.
     * <p>
     * Validation is strict:
     * <ul>
     *     <li>the text must match dd/MM/yyyy exactly (e.g. "5/9/26" is rejected);</li>
     *     <li>the date must really exist (e.g. 31/02/2026 is rejected).</li>
     * </ul>
     *
     * @param text the date typed by the user
     * @return the date in milliseconds, or null if the text is not a valid date
     */
    @Nullable
    public static Long parseInputDate(@Nullable String text) {
        if (text == null || !text.matches(INPUT_REGEX)) {
            return null;
        }

        SimpleDateFormat format = new SimpleDateFormat(INPUT_PATTERN, Locale.UK);
        // Non-lenient: do NOT roll invalid dates over (31/02 would otherwise become 03/03)
        format.setLenient(false);

        try {
            Date date = format.parse(text);
            return (date == null) ? null : date.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** Returns true if the year of the given date is between MIN_YEAR and MAX_YEAR. */
    public static boolean isYearInRange(long dateMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dateMillis);
        int year = calendar.get(Calendar.YEAR);
        return year >= MIN_YEAR && year <= MAX_YEAR;
    }

    /**
     * Formats a date chosen in the calendar picker as dd/MM/yyyy.
     *
     * @param year       e.g. 2026
     * @param monthIndex 0 = January ... 11 = December (Android calendar convention)
     * @param day        day of the month
     */
    @NonNull
    public static String formatForInput(int year, int monthIndex, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, monthIndex, day);
        return new SimpleDateFormat(INPUT_PATTERN, Locale.UK).format(calendar.getTime());
    }

    /** Formats a stored date as dd/MM/yyyy, e.g. to fill the date field when editing. */
    @NonNull
    public static String formatForInput(long dateMillis) {
        return new SimpleDateFormat(INPUT_PATTERN, Locale.UK).format(new Date(dateMillis));
    }
    /** Formats a stored date for the event list, e.g. "Fri, 25 Dec 2026". */
    @NonNull
    public static String formatForDisplay(long dateMillis) {
        return new SimpleDateFormat(DISPLAY_PATTERN, Locale.UK).format(new Date(dateMillis));
    }
}