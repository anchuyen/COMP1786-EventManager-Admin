package com.example.eventmanager.util;

import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Input validation rules used by the event form (coursework feature a).
 * <p>
 * Kept separate from the Activity so the rules are in one place, easy to read in the
 * report and easy to reuse (e.g. in the session form).
 * The date rule (DD/MM/YYYY) lives in {@link DateUtils#parseInputDate(String)}.
 */
public final class ValidationUtils {

    /** 24-hour time "HH:mm": hours 00-23, minutes 00-59 (e.g. 09:30, 18:05). */
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /**
     * Vietnamese mobile number after spaces, dots and dashes are removed:
     * starts with 0 or +84, then a mobile prefix 3/5/7/8/9, then 8 more digits.
     * Examples: 0912345678, +84912345678, 0387 654 321.
     */
    private static final Pattern VN_PHONE_PATTERN = Pattern.compile("^(0|\\+84)[35789]\\d{8}$");

    /** Characters people often type inside phone numbers; they are ignored. */
    private static final String PHONE_SEPARATORS = "[\\s.\\-()]";

    /** Private constructor: utility class, should not be instantiated. */
    private ValidationUtils() {
    }

    /** True if the text is a valid 24-hour time "HH:mm". */
    public static boolean isValidTime(@Nullable String text) {
        return text != null && TIME_PATTERN.matcher(text).matches();
    }

    /** True if the text is a valid e-mail address (Android's built-in pattern). */
    public static boolean isValidEmail(@Nullable String text) {
        return text != null && Patterns.EMAIL_ADDRESS.matcher(text).matches();
    }

    /**
     * Removes spaces, dots, dashes and brackets: "0912 345-678" becomes "0912345678".
     * The phone number is saved in this clean form.
     */
    @NonNull
    public static String normalisePhone(@NonNull String text) {
        return text.replaceAll(PHONE_SEPARATORS, "");
    }

    /** True if the text (after {@link #normalisePhone}) is a valid Vietnamese mobile number. */
    public static boolean isValidPhone(@Nullable String text) {
        return text != null && VN_PHONE_PATTERN.matcher(normalisePhone(text)).matches();
    }

    /** Formats hour and minute from the time picker as "HH:mm". */
    @NonNull
    public static String formatTime(int hour, int minute) {
        return String.format(java.util.Locale.UK, "%02d:%02d", hour, minute);
    }
}
